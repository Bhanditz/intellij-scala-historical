package org.jetbrains.plugins.scala
package lang
package refactoring
package scopeSuggester

import java.util

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.search.{GlobalSearchScope, PackageScope, PsiSearchHelper}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{PsiElement, PsiFile, PsiPackage}
import com.intellij.util.Processor
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScTypeAlias
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.{ScExtendsBlock, ScTemplateBody}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef._
import org.jetbrains.plugins.scala.lang.psi.impl.ScPackageImpl
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.templates.ScTemplateBodyImpl
import org.jetbrains.plugins.scala.lang.psi.types.{ScProjectionType, ScTypeParameterType}
import org.jetbrains.plugins.scala.lang.refactoring.namesSuggester.NameSuggester
import org.jetbrains.plugins.scala.lang.refactoring.util._
import com.intellij.openapi.util.text.StringUtil
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


/**
 * Created by Kate Ustyuzhanina on 8/12/15.
 */
object ScopeSuggester {
  def suggestScopes(conflictsReporter: ConflictsReporter,
                    project: Project,
                    editor: Editor,
                    file: PsiFile,
                    curerntElement: ScTypeElement): util.ArrayList[ScopeItem] = {

    def getParent(element: PsiElement, isScriptFile: Boolean): PsiElement = {
      if (isScriptFile)
        PsiTreeUtil.getParentOfType(element, classOf[ScTemplateBody], classOf[ScalaFile])
      else
        PsiTreeUtil.getParentOfType(element, classOf[ScTemplateBody])
    }

    val isScriptFile = curerntElement.getContainingFile.asInstanceOf[ScalaFile].isScriptFile()
    var parent = getParent(curerntElement, isScriptFile)


    var noContinue = false
    var result: List[ScopeItem] = List()
    while (parent != null && !noContinue) {
      var occInCompanionObj: Array[ScTypeElement] = Array[ScTypeElement]()
      val name = parent match {
        case fileType: ScalaFile => "file " + fileType.getName
        case _ =>
          PsiTreeUtil.getParentOfType(parent, classOf[ScTemplateDefinition]) match {
            case classType: ScClass =>
              "class " + classType.name
            case objectType: ScObject =>
              occInCompanionObj = getOccurrencesFromCompanionObject(curerntElement, objectType)
              "object " + objectType.name
            case traitType: ScTrait =>
              "trait " + traitType.name
          }
      }

      //parent != null here
      //check can we use upper scope
      noContinue = curerntElement.calcType match {
        case projectionType: ScProjectionType
          if parent.asInstanceOf[ScTemplateBody].isAncestorOf(projectionType.actualElement) =>
          true
        case _ => false
      }

      val occurrences = ScalaRefactoringUtil.getTypeElementOccurrences(curerntElement, parent)
      val validator = ScalaTypeValidator(conflictsReporter, project, editor, file, curerntElement, parent, occurrences.isEmpty)
      val possibleNames = NameSuggester.namesByType(curerntElement.calcType)(validator)

      val scope = new ScopeItem(name, parent, occurrences, occInCompanionObj, validator, possibleNames.toList.reverse.toArray)
      scope.computeRanges()
      result = result :+ scope
      parent = getParent(parent, isScriptFile)
    }

    //gathering occurrences in current package
    val packageName = curerntElement.getContainingFile match {
      case scalaFile: ScalaFile =>
        scalaFile.getPackageName
    }

    //forbid to use typeParameter type outside the class
    if (!packageName.equals("") && !curerntElement.calcType.isInstanceOf[ScTypeParameterType] && !noContinue) {
      result = result :+ handlePackage(curerntElement, packageName, conflictsReporter, project, editor)
    }

    import scala.collection.JavaConversions.asJavaCollection
    new util.ArrayList[ScopeItem](result.toIterable)
  }

  private def getOccurrencesFromCompanionObject(typeElement: ScTypeElement,
                                                objectType: ScObject): Array[ScTypeElement] = {
    val parent: PsiElement = objectType.getParent
    val name = objectType.name
    val companion = parent.getChildren.find({
      case classType: ScClass if classType.name == name =>
        true
      case traitType: ScTrait if traitType.name == name =>
        true
      case _ => false
    })

    if (companion.isDefined)
      ScalaRefactoringUtil.getTypeElementOccurrences(typeElement, companion.get)
    else
      Array[ScTypeElement]()
  }

  private def handlePackage(typeElement: ScTypeElement, packageName: String, conflictsReporter: ConflictsReporter,
                            project: Project, editor: Editor): ScopeItem = {


    def getFilesToSearchIn(currentPackage: PsiPackage): Array[PsiFile] = {
      def oneRound(word: String, bufResult: ArrayBuffer[ArrayBuffer[PsiFile]]) = {
        val buffer = new ArrayBuffer[PsiFile]()

        val processor = new Processor[PsiFile] {
          override def process(file: PsiFile): Boolean = {
            buffer += file
            true
          }
        }

        val helper: PsiSearchHelper = PsiSearchHelper.SERVICE.getInstance(typeElement.getProject)
        helper.processAllFilesWithWord(word, PackageScope.packageScope(currentPackage, false), processor, true)
        bufResult += buffer
      }

      val typeName = typeElement.calcType.presentableText
      var words = Array[String]()
      words = StringUtil.getWordsIn(typeName).toArray(words)

      val resultBuffer = new ArrayBuffer[ArrayBuffer[PsiFile]]()
      words.foreach(oneRound(_, resultBuffer))

      var intersectionResult = resultBuffer(0)
      def intersect(inBuffer: ArrayBuffer[PsiFile]) = {
        intersectionResult = intersectionResult.intersect(inBuffer)
      }

      resultBuffer.foreach((element: ArrayBuffer[PsiFile]) => intersect(element))
      intersectionResult.toList.reverse.toArray
    }

    val projectSearchScope = GlobalSearchScope.projectScope(typeElement.getProject)
    val packageReal = ScPackageImpl.findPackage(typeElement.getProject, packageName)
    val packageObject = packageReal.findPackageObject(projectSearchScope)

    val fileEncloser = if (packageObject.isDefined)
      PsiTreeUtil.getChildOfType(PsiTreeUtil.getChildOfType(packageObject.get, classOf[ScExtendsBlock]), classOf[ScTemplateBody])
    else
      null

    val allOcurrences: mutable.MutableList[Array[ScTypeElement]] = mutable.MutableList()
    val allValidators: mutable.MutableList[ScalaTypeValidator] = mutable.MutableList()

    def handleOneFile(file: PsiFile) {
      if (packageObject.isDefined && packageObject.get.getContainingFile == file) {
      } else {
        val occurrences = ScalaRefactoringUtil.getTypeElementOccurrences(typeElement, file)
        allOcurrences += occurrences
        val parent = if (file.asInstanceOf[ScalaFile].isScriptFile()) file else PsiTreeUtil.findChildOfType(file, classOf[ScTemplateBody])
        allValidators += ScalaTypeValidator(conflictsReporter, project, editor, file, typeElement, parent, occurrences.isEmpty)
      }
    }

    getFilesToSearchIn(packageReal).foreach(handleOneFile)

    val occurrences = allOcurrences.foldLeft(Array[ScTypeElement]())((a, b) => a ++ b)
    val validator = ScalaCompositeValidator(allValidators.toList, conflictsReporter, project, typeElement,
      occurrences.isEmpty, fileEncloser, fileEncloser)

    val possibleNames = NameSuggester.namesByType(typeElement.calcType)(validator)
    val result = new ScopeItem("package " + packageName, fileEncloser, occurrences, Array[ScTypeElement](),
      validator, possibleNames.toList.reverse.toArray)

    result.computeRanges()
    result
  }
}

class ScopeItem(val name: String,
                var fileEncloser: PsiElement,
                var usualOccurrences: Array[ScTypeElement],
                var occurrencesInCompanion: Array[ScTypeElement],
                val typeValidator: ScalaValidator,
                val availableNames: Array[String]) {

  var occurrencesFromInheretors: Array[ScTypeElement] = Array[ScTypeElement]()

  var typeAlias: ScTypeAlias = null
  var typeAliasFile: PsiFile = null
  var occurrencesRanges: Array[TextRange] = Array[TextRange]()
  var typeAliasOffset: TextRange = null

  def computeRanges() = {
    occurrencesRanges = usualOccurrences.map(_.getTextRange)
  }

  def computeTypeAliasOffset() = {
    typeAliasOffset = typeAlias.getTextRange
  }

  def setFileEncloser(encloser: PsiElement): Unit = {
    if (fileEncloser == null) {
      fileEncloser = encloser
    }
  }

  def setInheretedOccurrences(occurrences: Array[ScTypeElement]) = {
    if (occurrences != null) {
      occurrencesFromInheretors = occurrences
    }
  }

  def setTypeAlias(inTypeAlias: ScTypeAlias) = {
    if (inTypeAlias != null) {
      typeAlias = inTypeAlias
      typeAliasFile = typeAlias.getContainingFile
    }
  }

  def redefineUsualOccurrences(file: PsiFile): Unit = {
    def findOneOccurrence(range: TextRange): ScTypeElement = {
      PsiTreeUtil.findElementOfClassAtRange(file, range.getStartOffset, range.getEndOffset, classOf[ScTypeElement])
    }

    usualOccurrences = occurrencesRanges.map(findOneOccurrence)
  }

  def copy(): ScopeItem = {
    val item = new ScopeItem(name, fileEncloser, usualOccurrences, occurrencesInCompanion, typeValidator, availableNames)
    item.typeAlias = typeAlias
    item.typeAliasFile = typeAliasFile
    item.occurrencesRanges = occurrencesRanges
    item.typeAliasOffset = typeAliasOffset
    item.occurrencesFromInheretors = occurrencesFromInheretors
    item.computeRanges()
    item.computeTypeAliasOffset()
    item
  }

  override def toString: String = name
}
