import java.nio.file.Paths
import javax.inject.Singleton

import net.codingwell.scalaguice.ScalaModule
import org.apache.commons.vfs2.{FileName, FileObject}
import org.http4s.rho._
import org.http4s.server.SSLSupport.StoreInfo
import org.http4s.server.blaze._
import org.http4s.server.{Server, ServerApp}
import org.json4s._
import org.json4s.native.Serialization.write
import org.metaborg.core.editor.{IEditorRegistry, NullEditorRegistry}
import org.metaborg.core.language.{ILanguageImpl, LanguageFileSelector}
import org.metaborg.core.project.{IProjectService, SimpleProjectService}
import org.metaborg.core.source.{ISourceLocation, ISourceRegion, SourceRegion}
import org.metaborg.spoofax.core.syntax.JSGLRSourceRegionFactory
import org.metaborg.spoofax.core.unit.{ISpoofaxAnalyzeUnit, ISpoofaxParseUnit}
import org.metaborg.spoofax.core.{Spoofax, SpoofaxModule}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scalaz.concurrent.Task

object Main extends ServerApp {
  val REPOS_PATH = "/Users/martijn/Documents"
  val cache = mutable.Map[String, Seq[(ISourceRegion, Iterable[ISourceLocation])]]()

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
      Ok(cachedAnalysis(project, rest.mkString("/")))
    }
  }

  /**
    * Retrieve the cached analysis result (if available) or compute the
    * analysis.
    *
    * @param projectName
    * @param filePath
    * @return
    */
  def cachedAnalysis(projectName: String, filePath: String): String = {
    val key = projectName + "/" + filePath
    val resolutions = cache.getOrElseUpdate(key, analysis(projectName, filePath))

    val projectPath = REPOS_PATH + "/" + projectName
    val projectPathName = spoofax.resourceService.resolve(projectPath).getName

    serialize(projectPathName, resolutions)
  }

  /**
    * Compute and return the analysis result
    *
    * TODO: Do this for *all* files, so that we can cache it for all files, and then filter it on on a page request.
    *
    * @param projectName
    * @param filePath
    * @return
    */
  def analysis(projectName: String, filePath: String): Seq[(ISourceRegion, Iterable[ISourceLocation])] = {
    val languagePath = "/Users/martijn/Projects/MiniJava"
    val languageImpl = utils.loadLanguage(languagePath)

    val projectPath = REPOS_PATH + "/" + projectName
    val projectFileObject = spoofax.resourceService.resolve(projectPath)

    val selector = new LanguageFileSelector(spoofax.languageIdentifierService, languageImpl)
    val files = spoofax.resourceService.resolve(projectPath).findFiles(selector)
    val parseUnits = parse(languageImpl, files)

    val project = utils.getOrCreateProject(projectFileObject)
    val context = spoofax.contextService.get(projectFileObject, project, languageImpl)

    // TODO: Abstract try-with-resource
    val lock = context.write()
    val analysisResults = spoofax.analysisService.analyzeAll(parseUnits.toIterable.asJava, context).results()
    lock.close()

    // TODO: Get resolutions for the *given* file, not for all files. Right now, we just re-do the analysis?
    val file = spoofax.resourceService.resolve(projectPath + "/" + filePath)
    val parseUnit = parse(languageImpl, file)

    val lockB = context.write()
    val analysisResult = spoofax.analysisService.analyze(parseUnit, context).result()
    lockB.close()

    resolutions(analysisResult, languageImpl)
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
  def resolutions(analyzeUnit: ISpoofaxAnalyzeUnit, languageImpl: ILanguageImpl): Seq[(ISourceRegion, Iterable[ISourceLocation])] =
    new Tokenizer().tokenizeAll(analyzeUnit.input, languageImpl).flatMap(token => {
      val referenceLocation = JSGLRSourceRegionFactory.fromToken(token)
      val resolutionOpt = Option(spoofax.resolverService.resolve(referenceLocation.startOffset(), analyzeUnit))

      resolutionOpt.map(resolution =>
        referenceLocation -> resolution.targets.asScala
      )
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
