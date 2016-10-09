import java.nio.file.Paths
import javax.inject.Singleton

import net.codingwell.scalaguice.ScalaModule
import org.apache.commons.vfs2.{FileName, FileObject}
import org.http4s.server.SSLSupport.StoreInfo
import org.http4s.server.blaze._
import org.http4s.server.{Server, ServerApp}
import org.http4s.rho._
import org.json4s._
import org.json4s.native.Serialization.write
import org.metaborg.core.editor.{IEditorRegistry, NullEditorRegistry}
import org.metaborg.core.language.ILanguageImpl
import org.metaborg.core.project.{IProjectService, SimpleProjectService}
import org.metaborg.core.source.{ISourceLocation, ISourceRegion, SourceRegion}
import org.metaborg.spoofax.core.syntax.JSGLRSourceRegionFactory
import org.metaborg.spoofax.core.unit.{ISpoofaxAnalyzeUnit, ISpoofaxParseUnit}
import org.metaborg.spoofax.core.{Spoofax, SpoofaxModule}
import org.metaborg.util.resource.ExtensionFileSelector

import scala.collection.JavaConverters._
import scalaz.concurrent.Task

object Main extends ServerApp {
  type Ref = ISourceRegion
  type Dec = ISourceLocation

  /**
    * Custom serializer for serializing FileObjects to their name relative to
    * the given basename.
    *
    * @param baseName
    */
  class FileObjectSerializer(baseName: FileName) extends CustomSerializer[FileObject](format => (PartialFunction.empty, {
    case fileObject: FileObject =>
      JString(baseName.getRelativeName(fileObject.getName))
  }))

  /**
    * The Spoofax facade
    */
  val spoofax = new Spoofax(new SpoofaxModule with ScalaModule {
    override def bindProject() {
      bind[SimpleProjectService].in[Singleton]
      bind[IProjectService].to[SimpleProjectService]
    }

    override def bindEditor() {
      bind[IEditorRegistry].to[NullEditorRegistry].in[Singleton]
    }
  })

  /**
    * Some utilities that use the Spoofax facade
    */
  val utils = new Utils(spoofax)

  /**
    * The analysis endpoint
    *
    * TODO: Wrap service to show exceptions.?
    */
  val analysisService = new RhoService {
    GET / 'project / * |>> { (project: String, rest: List[String]) =>
      Ok(analysis(project, rest.mkString))
    }
  }

  /**
    * Compute and return the analysis result
    *
    * @param project
    * @param file
    * @return
    */
  def analysis(project: String, file: String): String = {
    val minijavaPath = "/Users/martijn/Projects/MiniJava"
    val minijavaImpl = utils.loadLanguage(minijavaPath)

    val projectPath = "/Users/martijn/Documents/minijava/"
    val projectFileObject = spoofax.resourceService.resolve(projectPath)

    // TODO: Get extension from language config, or better, make it a "LanguageFilter"
    val files = spoofax.resourceService.resolve(projectPath).findFiles(new ExtensionFileSelector("mjv"))
    val parseUnits = parse(minijavaImpl, files)

    val project = utils.getOrCreateProject(projectFileObject)
    val context = spoofax.contextService.get(projectFileObject, project, minijavaImpl)

    // TODO: Abstract try-with-resource
    val lock = context.write()
    val analysisResults = spoofax.analysisService.analyzeAll(parseUnits.toIterable.asJava, context).results()
    lock.close()

    // TODO: Get resolutions for the *given* file, not for all files...
    val result = analysisResults.asScala.flatMap(
      resolutions(_, minijavaImpl)
    )

    serialize(projectFileObject.getName, result)
  }

  /**
    * Serialize content with FileObjects serialized relative to the given
    * filename.
    *
    * @param fileName
    * @param content
    * @return
    */
  def serialize[A <: AnyRef](fileName: FileName, content: A) = {
    implicit val formats = DefaultFormats + new FileObjectSerializer(fileName)

    write(content)
  }

  /**
    * Get resolutions for given file
    */
  def resolutions(analyzeUnit: ISpoofaxAnalyzeUnit, languageImpl: ILanguageImpl): Seq[(Ref, Iterable[Dec])] =
    new Tokenizer().tokenizeAll(analyzeUnit.input, languageImpl).flatMap(token => {
      val referenceLocation = JSGLRSourceRegionFactory.fromToken(token)
      val resolutionOpt = Option(spoofax.resolverService.resolve(referenceLocation.startOffset(), analyzeUnit))

      resolutionOpt.map(resolution => {
        val declarationLocations = resolution.targets.asScala

        referenceLocation -> declarationLocations
      })
    })

  /**
    * Parse given array of files in given language.
    *
    * @param languageImpl
    * @param files
    * @return
    */
  def parse(languageImpl: ILanguageImpl, files: Array[FileObject]): Array[ISpoofaxParseUnit] =
    files.map(parse(languageImpl, _))

  /**
    * Parse given file in given language.
    *
    * @param languageImpl
    * @param file
    * @return
    */
  def parse(languageImpl: ILanguageImpl, file: FileObject) = {
    val inputText = spoofax.sourceTextService.text(file)
    val inputUnit = spoofax.unitService.inputUnit(file, inputText, languageImpl, null)
    val parseUnit = spoofax.syntaxService.parse(inputUnit)

    parseUnit
  }

  /**
    * Convenience method that accepts a FileObject
    *
    * @param resource
    * @param sourceRegion
    * @return
    * @deprecated Not needed since JSGLRSourceRegionFactory provides this..
    */
  def denormalize(resource: FileObject, sourceRegion: ISourceRegion): ISourceRegion =
    denormalize(spoofax.sourceTextService.text(resource), sourceRegion)

  /**
    * Transform a source region that is defined in terms of offset into a
    * source region defined in rows and columns. We adopt the same convention
    * as Spoofax of starting at row 0, column 0.
    *
    * @param text
    * @param sourceRegion
    * @return
    */
  def denormalize(text: String, sourceRegion: ISourceRegion): ISourceRegion = {
    val partial = text.substring(0, sourceRegion.startOffset())

    val (row, column) = partial.foldLeft((0, 0)) {
      case ((row, column), '\n') =>
        (row + 1, 0)
      case ((row, column), _) =>
        (row, column + 1)
    }

    new SourceRegion(0, row, column, 0, row, column)
  }

  /**
    * The path to the Java Keytool
    */
  val keypath = Paths.get("keystore.jks").toAbsolutePath.toString

  /**
    * Define how to start the server
    *
    * @param args
    * @return
    */
  override def server(args: List[String]): Task[Server] =
    BlazeBuilder
      .withSSL(StoreInfo(keypath, "gJEYT9oh7RxutD"), keyManagerPassword = "gJEYT9oh7RxutD")
      .mountService(analysisService.toService(), "/api")
      .bindHttp(8080, "localhost")
      .start
}
