package org.jetbrains.plugins.scala
package lang
package refactoring
package scopeSuggester

import java.util

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.search.{GlobalSearchScope, PackageScope, PsiSearchHelper}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{PsiElement, PsiFile, PsiPackage}
import com.intellij.util.Processor
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.{ScExtendsBlock, ScTemplateBody}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef._
import org.jetbrains.plugins.scala.lang.psi.impl.ScPackageImpl
import org.jetbrains.plugins.scala.lang.refactoring.namesSuggester.NameSuggester
import org.jetbrains.plugins.scala.lang.refactoring.util._

import scala.collection.mutable


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


    var result: List[ScopeItem] = List()
    while (parent != null) {
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

      val occurrences = ScalaRefactoringUtil.getTypeElementOccurrences(curerntElement, parent)
      val validator = ScalaTypeValidator(conflictsReporter, project, editor, file, curerntElement, parent, occurrences.isEmpty)
      val possibleNames = NameSuggester.namesByType(curerntElement.calcType)(validator)

      result = result :+ new ScopeItem(name, parent, occurrences, occInCompanionObj, validator, possibleNames.toList.reverse.toArray)
      parent = getParent(parent, isScriptFile)
    }

    //gathering occurrences in current package
    val packageName = curerntElement.getContainingFile match {
      case scalaFile: ScalaFile =>
        scalaFile.getPackageName
    }
    if (!packageName.equals("")) {
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

    val projectSearchScope = GlobalSearchScope.projectScope(typeElement.getProject)
    val packageReal = ScPackageImpl.findPackage(typeElement.getProject, packageName)
    val packageObject = packageReal.findPackageObject(projectSearchScope)

    val fileEncloser = if (packageObject.isDefined)
      PsiTreeUtil.getChildOfType(PsiTreeUtil.getChildOfType(packageObject.get, classOf[ScExtendsBlock]), classOf[ScTemplateBody])
    else
      null

    val allOcurrences: mutable.MutableList[Array[ScTypeElement]] = mutable.MutableList()
    val allValidators: mutable.MutableList[ScalaTypeValidator] = mutable.MutableList()

    val processor = new Processor[PsiFile] {
      override def process(file: PsiFile): Boolean = {
        val occurrences = ScalaRefactoringUtil.getTypeElementOccurrences(typeElement, file)
        allOcurrences += occurrences
        allValidators += ScalaTypeValidator(conflictsReporter, project, editor, file, typeElement, file, occurrences.isEmpty)
        true
      }
    }

    val helper: PsiSearchHelper = PsiSearchHelper.SERVICE.getInstance(typeElement.getProject)
    helper.processAllFilesWithWord(typeElement.calcType.canonicalText, PackageScope.packageScope(packageReal, false), processor, true)

    val occurrences = allOcurrences.foldLeft(Array[ScTypeElement]())((a, b) => a ++ b)
    val validator = ScalaCompositeValidator(allValidators.toList, conflictsReporter, project, typeElement,
      occurrences.isEmpty, fileEncloser, fileEncloser)

    val possibleNames = NameSuggester.namesByType(typeElement.calcType)(validator)
    val result = new ScopeItem("package " + packageName, fileEncloser, occurrences, Array[ScTypeElement](),
      validator, possibleNames.toList.reverse.toArray)

    result
  }
}

class ScopeItem(name: String,
                encloser: PsiElement,
                inOccurrences: Array[ScTypeElement],
                inOccInCompanionObj: Array[ScTypeElement],
                inValidator: ScalaValidator,
                inAvailablenames: Array[String]) {
  var fileEncloser: PsiElement = encloser
  val scopeName: String = name
  val occurrences: Array[ScTypeElement] = inOccurrences
  val occInCompanionObj: Array[ScTypeElement] = inOccInCompanionObj
  val validator: ScalaValidator = inValidator
  val possibleNames: Array[String] = inAvailablenames
  var occurrencesFromInheretors: Array[ScTypeElement] = Array[ScTypeElement]()

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

  override def toString: String = name
}
