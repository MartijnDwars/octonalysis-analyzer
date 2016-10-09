import java.nio.charset.StandardCharsets

import com.google.common.collect.Iterables
import org.apache.commons.io.IOUtils
import org.apache.commons.vfs2.FileObject
import org.metaborg.core.context.IContext
import org.metaborg.core.language.ILanguageImpl
import org.metaborg.core.project.{IProject, SimpleProjectService}
import org.metaborg.spoofax.core.Spoofax
import org.spoofax.interpreter.terms.{IStrategoList, IStrategoString, IStrategoTerm}

import scala.collection.JavaConverters._

class Utils(s: Spoofax) {
  /**
    * Load a language implementation.
    *
    * @param path
    * @return
    */
  def loadLanguage(path: String): ILanguageImpl = {
    val languageLocation = s.resourceService.resolve(path)
    val languageComponents = s.discoverLanguages(languageLocation)

    val component = Iterables.get(languageComponents, 0)
    val languageImpl = Iterables.get(component.contributesTo(), 0)

    languageImpl
  }

  /**
    * Parse a file in the language to an AST.
    *
    * @param languageImpl
    * @param filePath
    * @return
    */
  def parseFile(languageImpl: ILanguageImpl, filePath: String): IStrategoTerm = {
    val file = s.resourceService.resolve(filePath)
    val text = IOUtils.toString(file.getContent.getInputStream, StandardCharsets.UTF_8)
    val inputUnit = s.unitService.inputUnit(text, languageImpl, null)
    val parseResult = s.syntaxService.parse(inputUnit)

    if (!parseResult.success()) {
      throw new RuntimeException(s"Unsuccessful parse of $filePath in language ${languageImpl.id()}.")
    }

    parseResult.ast()
  }

  /**
    * Get a context by first creating a project at the given location.
    *
    * @param location
    * @param languageImpl
    * @return
    */
  def getContext(location: String, languageImpl: ILanguageImpl): IContext = {
    val resource = languageImpl.locations().asScala.head

    val projectService = s.injector.getInstance(classOf[SimpleProjectService])
    projectService.create(resource)

    val project = projectService.get(resource)
    val context = s.contextService.getTemporary(resource, project, languageImpl)

    context
  }

  /**
    * Get existing project or create a new  project at the given location
    *
    * @return
    */
  def getOrCreateProject(location: FileObject): IProject = {
    if (s.projectService.get(location) == null) {
      s.injector.getInstance(classOf[SimpleProjectService]).create(location)
    }

    s.projectService.get(location)
  }
}
