package org.jetbrains.plugins.scala
package lang
package refactoring
package introduceVariable


import java.util

import com.intellij.internal.statistic.UsageTrigger
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.impl.StartMarkAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util._
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.impl.source.codeStyle.CodeEditUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{xml => _, _}
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.introduce.inplace.OccurrencesChooser
import org.jetbrains.plugins.scala.extensions.childOf
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.{ScClassParents, ScExtendsBlock, ScTemplateBody}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScMember, ScObject}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.{ScEarlyDefinitions, ScNamedElement}
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.lang.psi.types.ScType
import org.jetbrains.plugins.scala.lang.refactoring.namesSuggester.NameSuggester
import org.jetbrains.plugins.scala.lang.refactoring.scopeSuggester.{ScopeItem, ScopeSuggester}
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaRefactoringUtil.{IntroduceException, showErrorMessage}
import org.jetbrains.plugins.scala.lang.refactoring.util.{DialogConflictsReporter, ScalaRefactoringUtil, ScalaVariableValidator}
import org.jetbrains.plugins.scala.settings.ScalaApplicationSettings
import org.jetbrains.plugins.scala.util.ScalaUtils

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer


/**
 * User: Alexander Podkhalyuzin
 * Date: 23.06.2008
 */

class ScalaIntroduceVariableHandler extends RefactoringActionHandler with DialogConflictsReporter {

  val REFACTORING_NAME = ScalaBundle.message("introduce.variable.title")
  private var occurrenceHighlighters = Seq.empty[RangeHighlighter]

  def invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext) {
    val element = PsiTreeUtil.findElementOfClassAtOffset(file, editor.getCaretModel.getOffset, classOf[ScTypeElement], false)

    if (element != null) {
      ScalaRefactoringUtil.afterTypeAliasChoosing(project, editor, file, dataContext, "Introduce Type Alias") {
        ScalaRefactoringUtil.trimSpacesAndComments(editor, file)
        invoke(project, editor, file, editor.getSelectionModel.getSelectionStart, editor.getSelectionModel.getSelectionEnd)
      }
    } else {
      val canBeIntroduced: ScExpression => Boolean = ScalaRefactoringUtil.checkCanBeIntroduced(_)
      ScalaRefactoringUtil.afterExpressionChoosing(project, editor, file, dataContext, "Introduce Variable", canBeIntroduced) {
        ScalaRefactoringUtil.trimSpacesAndComments(editor, file)
        invoke(project, editor, file, editor.getSelectionModel.getSelectionStart, editor.getSelectionModel.getSelectionEnd)
      }
    }
  }


  def invoke(project: Project, editor: Editor, file: PsiFile, startOffset: Int, endOffset: Int) {

    try {
      UsageTrigger.trigger(ScalaBundle.message("introduce.variable.id"))

      PsiDocumentManager.getInstance(project).commitAllDocuments()
      ScalaRefactoringUtil.checkFile(file, project, editor, REFACTORING_NAME)
      def handleExpression() = {
        val (expr: ScExpression, types: Array[ScType]) = ScalaRefactoringUtil.getExpression(project, editor, file, startOffset, endOffset).
          getOrElse(showErrorMessage(ScalaBundle.message("cannot.refactor.not.expression"), project, editor, REFACTORING_NAME))

        ScalaRefactoringUtil.checkCanBeIntroduced(expr, showErrorMessage(_, project, editor, REFACTORING_NAME))

        val fileEncloser = ScalaRefactoringUtil.fileEncloser(startOffset, file)
        val occurrences: Array[TextRange] = ScalaRefactoringUtil.getOccurrenceRanges(ScalaRefactoringUtil.unparExpr(expr), fileEncloser)
        val validator = ScalaVariableValidator(this, project, editor, file, expr, occurrences)

        def runWithDialog() {
          val dialog = getDialog(project, editor, expr, types, occurrences, declareVariable = false, validator)
          if (!dialog.isOK) {
            occurrenceHighlighters.foreach(_.dispose())
            occurrenceHighlighters = Seq.empty
            return
          }
          val varName: String = dialog.getEnteredName
          val varType: ScType = dialog.getSelectedType
          val isVariable: Boolean = dialog.isDeclareVariable
          val replaceAllOccurrences: Boolean = dialog.isReplaceAllOccurrences
          runRefactoring(startOffset, endOffset, file, editor, expr, occurrences, varName, varType, replaceAllOccurrences, isVariable)
        }

        def runInplace() {
          val callback = new Pass[OccurrencesChooser.ReplaceChoice] {
            def pass(replaceChoice: OccurrencesChooser.ReplaceChoice) {
              val replaceAll = OccurrencesChooser.ReplaceChoice.NO != replaceChoice
              val suggestedNames: Array[String] = NameSuggester.suggestNames(expr, validator)
              import scala.collection.JavaConversions.asJavaCollection
              val suggestedNamesSet = new util.LinkedHashSet[String](suggestedNames.toIterable)
              val asVar = ScalaApplicationSettings.getInstance().INTRODUCE_VARIABLE_IS_VAR
              val forceInferType = expr match {
                case _: ScFunctionExpr => Some(true)
                case _ => None
              }
              val needExplicitType = forceInferType.getOrElse(ScalaApplicationSettings.getInstance().INTRODUCE_VARIABLE_EXPLICIT_TYPE)
              val selectedType = if (needExplicitType) types(0) else null
              val introduceRunnable: Computable[SmartPsiElementPointer[PsiElement]] =
                introduceVariable(startOffset, endOffset, file, editor, expr, occurrences, suggestedNames(0), selectedType,
                  replaceAll, asVar)
              CommandProcessor.getInstance.executeCommand(project, new Runnable {
                def run() {
                  val newDeclaration: PsiElement = ApplicationManager.getApplication.runWriteAction(introduceRunnable).getElement
                  val namedElement: PsiNamedElement = newDeclaration match {
                    case holder: ScDeclaredElementsHolder =>
                      holder.declaredElements.headOption.orNull
                    case enum: ScEnumerator => enum.pattern.bindings.headOption.orNull
                    case _ => null
                  }
                  if (namedElement != null && namedElement.isValid) {
                    editor.getCaretModel.moveToOffset(namedElement.getTextOffset)
                    editor.getSelectionModel.removeSelection()
                    if (ScalaRefactoringUtil.isInplaceAvailable(editor)) {
                      PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument)
                      PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.getDocument)
                      val checkedExpr = if (expr.isValid) expr else null
                      val variableIntroducer =
                        new ScalaInplaceVariableIntroducer(project, editor, checkedExpr, types, namedElement,
                          REFACTORING_NAME, replaceAll, asVar, forceInferType)
                      variableIntroducer.performInplaceRefactoring(suggestedNamesSet)
                    }
                  }
                }
              }, REFACTORING_NAME, null)
            }
          }

          val chooser = new OccurrencesChooser[TextRange](editor) {
            override def getOccurrenceRange(occurrence: TextRange) = occurrence
          }

          if (occurrences.isEmpty) {
            callback.pass(OccurrencesChooser.ReplaceChoice.NO)
          } else {
            chooser.showChooser(new TextRange(startOffset, endOffset), occurrences.toList.asJava, callback)
          }
        }

        if (ScalaRefactoringUtil.isInplaceAvailable(editor)) runInplace()
        else runWithDialog()
      }

      def handleTypeElement() = {
        val typeElement: ScTypeElement = ScalaRefactoringUtil.getTypeEement(project, editor, file, startOffset, endOffset).
          getOrElse(showErrorMessage(ScalaBundle.message("cannot.refactor.not.valid.type"), project, editor, REFACTORING_NAME))

//        if ((StartMarkAction.canStart(project) == null) && IntroduceTypeAliasData.isData) {
//          IntroduceTypeAliasData.clearData()
//        }

        if (IntroduceTypeAliasData.possibleScopes == null) {
          IntroduceTypeAliasData.setPossibleScopes(ScopeSuggester.suggestScopes(this, project, editor, file, typeElement))
        }

        if (IntroduceTypeAliasData.possibleScopes.isEmpty) {
          showErrorMessage(ScalaBundle.message("cannot.refactor.not.script.file"), project, editor, REFACTORING_NAME)
        }

        def runWithDialog() {
          val typeElementHelper = if (!typeElement.isValid) {
            PsiTreeUtil.findElementOfClassAtOffset(file, editor.getCaretModel.getOffset, classOf[ScTypeElement], false)
          } else {
            typeElement
          }
          val dialog = getDialogForTypes(project, editor, typeElementHelper, IntroduceTypeAliasData.possibleScopes)
          if (!dialog.isOK) {
            occurrenceHighlighters.foreach(_.dispose())
            occurrenceHighlighters = Seq.empty
            return
          }

          val typeName: String = dialog.getEnteredName
          val replaceAllOccurrences: Boolean = dialog.isReplaceAllOccurrences

          val occurrences: OccurrenceHandler = OccurrenceHandler(typeElementHelper,
            dialog.isReplaceAllOccurrences,
            dialog.isReplaceOccurrenceIncompanionObject,
            dialog.isReplaceOccurrenceInInheritors, dialog.getSelectedScope)

          val parent = dialog.getSelectedScope.fileEncloser
          runRefactoringForTypes(startOffset, endOffset, file, editor, typeElementHelper, typeName, occurrences,
            replaceAllOccurrences, parent)
          IntroduceTypeAliasData.clearData()
        }

        // replace all occurrences, don't replace occurences available from companion object or inheritors
        // suggest to choose scope
        def runInplace() = {

          def handleScope(scopeItem: ScopeItem, needReplacement: Boolean) {
            val replaceAllOccurrences = true
            val suggestedNames = scopeItem.availableNames
            import scala.collection.JavaConversions.asJavaCollection
            val suggestedNamesSet = new util.LinkedHashSet[String](suggestedNames.toIterable)

            val allOccurrences = OccurrenceHandler(typeElement, replaceAllOccurrences, false, false, scopeItem)


            //remember file before replacement
            if ((scopeItem.fileEncloser == null) && scopeItem.name.substring(0, 7) == "package") {
              scopeItem.setFileEncloser(ScalaRefactoringUtil.getPackageObjectBody(typeElement))
            }

            val introduceRunnable: Computable[(SmartPsiElementPointer[PsiElement], SmartPsiElementPointer[PsiElement])] =
              if (needReplacement) {
                introduceTypeAlias(startOffset, endOffset, file, editor, typeElement,
                  allOccurrences, suggestedNames(0), replaceAllOccurrences, scopeItem.fileEncloser, scopeItem)
              } else {
                null
              }


            CommandProcessor.getInstance.executeCommand(project, new Runnable {
              def run() {
                val computable = if (needReplacement) {
                  ApplicationManager.getApplication.runWriteAction(introduceRunnable)
                }
                else {
                  null
                }

                val namedElement: ScNamedElement = if (needReplacement) {
                  computable._1.getElement match {
                    case typeAlias: ScTypeAliasDefinition =>
                      typeAlias
                    case _ => null
                  }
                } else {
                  if (scopeItem.name.substring(0, 7) == "package") {
                    scopeItem.typeAlias
                  } else {
                    IntroduceTypeAliasData.getNamedElement(file)
                  }
                }

                val mtypeElement = if (needReplacement) {
                  computable._2.getElement match {
                    case typeElement: ScTypeElement =>
                      typeElement
                    case _ => null
                  }
                } else {
                  typeElement
                }

                if (mtypeElement != null && mtypeElement.isValid) {

                  editor.getCaretModel.moveToOffset(mtypeElement.getTextOffset)
                  editor.getSelectionModel.removeSelection()
                  if (ScalaRefactoringUtil.isInplaceAvailable(editor)) {

                    PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument)
                    PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.getDocument)

                    val typeAliasIntroducer =
                      ScalaInplaceTypeAliasIntroducer(namedElement, namedElement, editor, namedElement.getName,
                        namedElement.getName, scopeItem, file)

                    val needModalDialog = typeAliasIntroducer.performInplaceRefactoring(suggestedNamesSet)

                    println("MODAL " +  needModalDialog)
                    if (!needModalDialog) {
                      runWithDialog()
                    }
                  }
                }
              }
            }, REFACTORING_NAME, null)


          }

          val elements = IntroduceTypeAliasData.scopeElements
          val currentScope = if (elements.nonEmpty) {
            elements.last
          } else {
            null
          }

          if ((StartMarkAction.canStart(project) != null) && (currentScope != null)) {
            handleScope(currentScope, needReplacement = false)
          } else {
            var array = Array[ScopeItem]()
            array = IntroduceTypeAliasData.possibleScopes.toArray(array)

            IntroduceTypeAliasData.setInintialInfo(editor.getDocument.getText, editor.getCaretModel.getOffset)

            ScalaRefactoringUtil.afterScopeChoosing(project, editor, file, array, "type alias") {
              scopeItem =>
                if (!scopeItem.usualOccurrences.isEmpty) {
                  handleScope(scopeItem, needReplacement = true)
                }
            }
          }
        }



        if (ScalaRefactoringUtil.isInplaceAvailable(editor)) runInplace()
        else runWithDialog()
      }

      PsiTreeUtil.getParentOfType(file.findElementAt(startOffset), classOf[ScExpression], classOf[ScTypeElement]) match {
        case x: ScExpression => handleExpression()
        case x: ScTypeElement => handleTypeElement()
        case _ =>
      }

    }

    catch {
      case _: IntroduceException => return
    }
  }


  //returns smart pointer to ScDeclaredElementsHolder or ScEnumerator
  def runRefactoringInside(startOffset: Int, endOffset: Int, file: PsiFile, editor: Editor, expression_ : ScExpression,
                           occurrences_ : Array[TextRange], varName: String, varType: ScType,
                           replaceAllOccurrences: Boolean, isVariable: Boolean): SmartPsiElementPointer[PsiElement] = {

    def isIntroduceEnumerator(parExpr: PsiElement, prev: PsiElement, firstOccurenceOffset: Int): Option[ScForStatement] = {
      val result = prev match {
        case forSt: ScForStatement if forSt.body.orNull == parExpr => None
        case forSt: ScForStatement => Some(forSt)
        case _: ScEnumerator | _: ScGenerator => Option(prev.getParent.getParent.asInstanceOf[ScForStatement])
        case guard: ScGuard if guard.getParent.isInstanceOf[ScEnumerators] => Option(prev.getParent.getParent.asInstanceOf[ScForStatement])
        case _ =>
          parExpr match {
            case forSt: ScForStatement => Some(forSt) //there are occurrences both in body and in enumerators
            case _ => None
          }
      }
      for {
      //check that first occurence is after first generator
        forSt <- result
        enums <- forSt.enumerators
        generator = enums.generators.head
        if firstOccurenceOffset > generator.getTextRange.getEndOffset
      } yield forSt
    }
    def addPrivateIfNotLocal(declaration: PsiElement) {
      declaration match {
        case member: ScMember if !member.isLocal =>
          member.setModifierProperty("private", value = true)
        case _ =>
      }
    }
    def replaceRangeByDeclaration(range: TextRange, element: PsiElement): PsiElement = {
      val (start, end) = (range.getStartOffset, range.getEndOffset)
      val text: String = element.getText
      val document = editor.getDocument
      document.replaceString(start, end, text)
      PsiDocumentManager.getInstance(element.getProject).commitDocument(document)
      val newEnd = start + text.length
      editor.getCaretModel.moveToOffset(newEnd)
      val decl = PsiTreeUtil.findElementOfClassAtOffset(file, start, classOf[ScMember], /*strictStart =*/ false)
      lazy val enum = PsiTreeUtil.findElementOfClassAtOffset(file, start, classOf[ScEnumerator], /*strictStart =*/ false)
      Option(decl).getOrElse(enum)
    }

    object inExtendsBlock {
      def unapply(e: PsiElement): Option[ScExtendsBlock] = {
        e match {
          case extBl: ScExtendsBlock =>
            Some(extBl)
          case elem if PsiTreeUtil.getParentOfType(elem, classOf[ScClassParents]) != null =>
            PsiTreeUtil.getParentOfType(elem, classOf[ScExtendsBlock]) match {
              case _ childOf (_: ScNewTemplateDefinition) => None
              case extBl => Some(extBl)
            }
          case _ => None
        }
      }
    }

    def isOneLiner = {
      val lineText = ScalaRefactoringUtil.getLineText(editor)
      val model = editor.getSelectionModel
      val document = editor.getDocument
      val selectedText = model.getSelectedText
      val lineNumber = document.getLineNumber(model.getSelectionStart)

      val oneLineSelected = selectedText != null && lineText != null && selectedText.trim == lineText.trim

      val element = file.findElementAt(model.getSelectionStart)
      var parent = element
      def atSameLine(offsets: Int*) = offsets.forall(document.getLineNumber(_) == lineNumber)
      while (parent != null && atSameLine(parent.getTextRange.getStartOffset, parent.getTextRange.getEndOffset)) {
        parent = parent.getParent
      }
      val insideExpression = parent match {
        case null | _: ScBlock | _: ScTemplateBody | _: ScEarlyDefinitions | _: PsiFile => false
        case _ => true
      }
      oneLineSelected && !insideExpression
    }

    val revertInfo = ScalaRefactoringUtil.RevertInfo(file.getText, editor.getCaretModel.getOffset)
    editor.putUserData(ScalaIntroduceVariableHandler.REVERT_INFO, revertInfo)

    val typeName = if (varType != null) varType.canonicalText else ""
    val expression = ScalaRefactoringUtil.expressionToIntroduce(expression_)

    val isFunExpr = expression.isInstanceOf[ScFunctionExpr]
    val mainRange = new TextRange(startOffset, endOffset)
    val occurrences: Array[TextRange] = if (!replaceAllOccurrences) {
      Array[TextRange] (mainRange)
    } else occurrences_
    val occCount = occurrences.length

    val mainOcc = occurrences.indexWhere(range => range.contains(mainRange) || mainRange.contains(range))
    val fastDefinition = occCount == 1 && isOneLiner

    //changes document directly
    val replacedOccurences = ScalaRefactoringUtil.replaceOccurences(occurrences, varName, file)

    //only Psi-operations after this moment
    var firstRange = replacedOccurences(0)
    val parentExprs =
      if (occCount == 1)
        ScalaRefactoringUtil.findParentExpr(file, firstRange) match {
          case _ childOf ((block: ScBlock) childOf ((_) childOf (call: ScMethodCall)))
            if isFunExpr && block.statements.size == 1 => Seq(call)
          case _ childOf ((block: ScBlock) childOf (infix: ScInfixExpr))
            if isFunExpr && block.statements.size == 1 => Seq(infix)
          case expr => Seq(expr)
        }
      else replacedOccurences.toSeq.map(ScalaRefactoringUtil.findParentExpr(file, _))
    val commonParent: PsiElement = PsiTreeUtil.findCommonParent(parentExprs: _*)

    val nextParent: PsiElement = ScalaRefactoringUtil.nextParent(commonParent, file)

    editor.getCaretModel.moveToOffset(replacedOccurences(mainOcc).getEndOffset)

    def createEnumeratorIn(forStmt: ScForStatement): ScEnumerator = {
      val parent: ScEnumerators = forStmt.enumerators.orNull
      val inParentheses = parent.prevSiblings.toList.exists(_.getNode.getElementType == ScalaTokenTypes.tLPARENTHESIS)
      val created = ScalaPsiElementFactory.createEnumerator(varName, ScalaRefactoringUtil.unparExpr(expression), file.getManager, typeName)
      val elem = parent.getChildren.filter(_.getTextRange.contains(firstRange)).head
      var result: ScEnumerator = null
      if (elem != null) {
        var needSemicolon = true
        var sibling = elem.getPrevSibling
        if (inParentheses) {
          while (sibling != null && sibling.getText.trim == "") sibling = sibling.getPrevSibling
          if (sibling != null && sibling.getText.endsWith(";")) needSemicolon = false
          val semicolon = parent.addBefore(ScalaPsiElementFactory.createSemicolon(parent.getManager), elem)
          result = parent.addBefore(created, semicolon).asInstanceOf[ScEnumerator]
          if (needSemicolon) {
            parent.addBefore(ScalaPsiElementFactory.createSemicolon(parent.getManager), result)
          }
        } else {
          if (sibling.getText.indexOf('\n') != -1) needSemicolon = false
          result = parent.addBefore(created, elem).asInstanceOf[ScEnumerator]
          parent.addBefore(ScalaPsiElementFactory.createNewLineNode(elem.getManager).getPsi, elem)
          if (needSemicolon) {
            parent.addBefore(ScalaPsiElementFactory.createNewLineNode(parent.getManager).getPsi, result)
          }
        }
      }
      result
    }

    def createVariableDefinition(): PsiElement = {
      val created = ScalaPsiElementFactory.createDeclaration(varName, typeName, isVariable,
        ScalaRefactoringUtil.unparExpr(expression), file.getManager)
      var result: PsiElement = null
      if (fastDefinition) {
        result = replaceRangeByDeclaration(replacedOccurences(0), created)
      }
      else {
        var needFormatting = false
        val parent = commonParent match {
          case inExtendsBlock(extBl) =>
            needFormatting = true
            extBl.addEarlyDefinitions()
          case _ =>
            val container = ScalaRefactoringUtil.container(commonParent, file)
            val needBraces = !commonParent.isInstanceOf[ScBlock] && ScalaRefactoringUtil.needBraces(commonParent, nextParent)
            if (needBraces) {
              firstRange = firstRange.shiftRight(1)
              val replaced = commonParent.replace(ScalaPsiElementFactory.createExpressionFromText("{" + commonParent.getText + "}", file.getManager))
              replaced.getPrevSibling match {
                case ws: PsiWhiteSpace if ws.getText.contains("\n") => ws.delete()
                case _ =>
              }
              replaced
            } else container
        }
        val anchor = parent.getChildren.find(_.getTextRange.contains(firstRange)).getOrElse(parent.getLastChild)
        if (anchor != null) {
          result = ScalaPsiUtil.addStatementBefore(created.asInstanceOf[ScBlockStatement], parent, Some(anchor))
          CodeEditUtil.markToReformat(parent.getNode, needFormatting)
        } else throw new IntroduceException
      }
      result
    }

    val createdDeclaration: PsiElement = isIntroduceEnumerator(commonParent, nextParent, firstRange.getStartOffset) match {
      case Some(forStmt) => createEnumeratorIn(forStmt)
      case _ => createVariableDefinition()
    }

    addPrivateIfNotLocal(createdDeclaration)
    ScalaPsiUtil.adjustTypes(createdDeclaration)
    SmartPointerManager.getInstance(file.getProject).createSmartPsiElementPointer(createdDeclaration)
  }

  def runRefactoringForTypeInside(startOffset: Int, endOffset: Int, file: PsiFile, editor: Editor,
                                  typeElement: ScTypeElement, typeName: String,
                                  occurrences: OccurrenceHandler, replaceAllOccurrences: Boolean, suggestedParent: PsiElement, scopeItem: ScopeItem):
  (SmartPsiElementPointer[PsiElement], SmartPsiElementPointer[PsiElement])
  = {
    def addTypeAliasDefinition(typeName: String, typeElement: ScTypeElement, parent: PsiElement) = {
      def getAhchor(parent: PsiElement, firstOccurrence: PsiElement): Some[PsiElement] = {
        val children = parent.getChildren

        Some(parent.getChildren.find(_.getTextRange.contains(firstOccurrence.getTextRange))
          .getOrElse(if (parent.getFirstChild.getText != "{") parent.getFirstChild
          else if (children.isEmpty) parent.getLastChild
          else children.apply(0)))
      }

      val mtext = typeElement.getText
      val definition = ScalaPsiElementFactory
        .createTypeAliasDefinitionFromText(s"type $typeName = $mtext", typeElement.getContext, typeElement)
      ScalaPsiUtil.addTypeAliasBefore(definition, parent, getAhchor(parent, typeElement))
      //      definition
    }

    val typeAlias = addTypeAliasDefinition(typeName, occurrences.getAllOccurrences(0), suggestedParent)

    val replacedTypeElement = ScalaRefactoringUtil.replaceTypeElement(Array(typeElement), typeName).apply(0)
    if (scopeItem == null) {
      ScalaRefactoringUtil.replaceTypeElement(occurrences.getUsualOccurrences, typeName)
      ScalaRefactoringUtil.replaceTypeElement(occurrences.getExtendedOccurrences, typeName)
    } else {
      scopeItem.setTypeAlias(typeAlias)

      val ab = new ArrayBuffer[ScTypeElement]()
      for (el <- occurrences.getUsualOccurrences) {
        if (el != typeElement) {
          ab += el
        }
      }
      scopeItem.usualOccurrences = ScalaRefactoringUtil.replaceTypeElement(ab.toList.reverse.toArray, typeName)
      scopeItem.usualOccurrences = scopeItem.usualOccurrences :+ replacedTypeElement
      scopeItem.occurrencesFromInheretors = ScalaRefactoringUtil.replaceTypeElement(occurrences.getExtendedOccurrences, typeName)
    }

    val className = PsiTreeUtil.getParentOfType(suggestedParent, classOf[ScObject]) match {
      case objectType: ScObject =>
        objectType.name
      case _ => ""
    }
    if (scopeItem != null) {
      scopeItem.occurrencesInCompanion = ScalaRefactoringUtil.replaceTypeElement(occurrences.getCompanionObjOccurrences, className + "." + typeName)
    } else {
      ScalaRefactoringUtil.replaceTypeElement(occurrences.getCompanionObjOccurrences, className + "." + typeName)
    }


    (SmartPointerManager.getInstance(file.getProject).createSmartPsiElementPointer(typeAlias.asInstanceOf[PsiElement]),
      SmartPointerManager.getInstance(file.getProject).createSmartPsiElementPointer(replacedTypeElement))
  }

  def runRefactoringForTypes(startOffset: Int, endOffset: Int, file: PsiFile, editor: Editor,
                             typeElement: ScTypeElement, typeName: String,
                             occurrences_ : OccurrenceHandler, replaceAllOccurrences: Boolean, parent: PsiElement) = {
    val runnable = new Runnable() {
      def run() {
        runRefactoringForTypeInside(startOffset, endOffset, file, editor,
          typeElement, typeName, occurrences_, replaceAllOccurrences, parent, null)
      }
    }
    ScalaUtils.runWriteAction(runnable, editor.getProject, REFACTORING_NAME)
    editor.getSelectionModel.removeSelection()
  }

  def runRefactoring(startOffset: Int, endOffset: Int, file: PsiFile, editor: Editor, expression: ScExpression,
                     occurrences_ : Array[TextRange], varName: String, varType: ScType,
                     replaceAllOccurrences: Boolean, isVariable: Boolean) {
    val runnable = new Runnable() {
      def run() {
        runRefactoringInside(startOffset, endOffset, file, editor, expression, occurrences_, varName,
          varType, replaceAllOccurrences, isVariable) //this for better debug
      }
    }

    ScalaUtils.runWriteAction(runnable, editor.getProject, REFACTORING_NAME)
    editor.getSelectionModel.removeSelection()
  }

  def introduceVariable(startOffset: Int, endOffset: Int, file: PsiFile, editor: Editor, expression: ScExpression,
                        occurrences_ : Array[TextRange], varName: String, varType: ScType,
                        replaceAllOccurrences: Boolean, isVariable: Boolean): Computable[SmartPsiElementPointer[PsiElement]] = {

    new Computable[SmartPsiElementPointer[PsiElement]]() {
      def compute() = runRefactoringInside(startOffset, endOffset, file, editor, expression, occurrences_, varName,
        varType, replaceAllOccurrences, isVariable)
    }

  }

  def introduceTypeAlias(startOffset: Int, endOffset: Int, file: PsiFile, editor: Editor, typeElement: ScTypeElement,
                         occurrences_ : OccurrenceHandler, typeName: String,
                         replaceAllOccurrences: Boolean, parent: PsiElement, scopeItem: ScopeItem):
  Computable[(SmartPsiElementPointer[PsiElement], SmartPsiElementPointer[PsiElement])] = {

    new Computable[(SmartPsiElementPointer[PsiElement], SmartPsiElementPointer[PsiElement])]() {
      def compute() = runRefactoringForTypeInside(startOffset, endOffset, file, editor, typeElement,
        typeName, occurrences_, replaceAllOccurrences, parent, scopeItem)
    }
  }

  def invoke(project: Project, elements: Array[PsiElement], dataContext: DataContext) {
    //nothing to do
  }

  protected def getDialog(project: Project, editor: Editor, expr: ScExpression, typez: Array[ScType],
                          occurrences: Array[TextRange], declareVariable: Boolean,
                          validator: ScalaVariableValidator): ScalaIntroduceVariableDialog = {
    // Add occurrences highlighting
    if (occurrences.length > 1)
      occurrenceHighlighters = ScalaRefactoringUtil.highlightOccurrences(project, occurrences, editor)

    val possibleNames = NameSuggester.suggestNames(expr, validator)
    val dialog = new ScalaIntroduceVariableDialog(project, typez, occurrences.length, validator, possibleNames)
    dialog.show()
    if (!dialog.isOK) {
      if (occurrences.length > 1) {
        WindowManager.getInstance.getStatusBar(project).
          setInfo(ScalaBundle.message("press.escape.to.remove.the.highlighting"))
      }
    }

    dialog
  }

  protected def getDialogForTypes(project: Project, editor: Editor, typeElement: ScTypeElement,
                                  possibleScopes: util.ArrayList[ScopeItem]): ScalaIntroduceTypeAliasDialog = {

    // Add occurrences highlighting
    val occurrences = possibleScopes.get(0).usualOccurrences
    if (occurrences.length > 1)
      occurrenceHighlighters = ScalaRefactoringUtil.highlightOccurrences(project, occurrences.map(_.getTextRange), editor)

    val dialog = new ScalaIntroduceTypeAliasDialog(project, typeElement, possibleScopes, this, editor)
    dialog.show()
    if (!dialog.isOK) {
      if (occurrences.length > 1) {
        WindowManager.getInstance.getStatusBar(project).
          setInfo(ScalaBundle.message("press.escape.to.remove.the.highlighting"))
      }
    }

    dialog
  }

  def runTest(project: Project, editor: Editor, file: PsiFile, startOffset: Int, endOffset: Int, replaceAll: Boolean) {
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    ScalaRefactoringUtil.checkFile(file, project, editor, REFACTORING_NAME)

    val (expr: ScExpression, types: Array[ScType]) = ScalaRefactoringUtil.getExpression(project, editor, file, startOffset, endOffset).
      getOrElse(showErrorMessage(ScalaBundle.message("cannot.refactor.not.expression"), project, editor, REFACTORING_NAME))

    ScalaRefactoringUtil.checkCanBeIntroduced(expr, showErrorMessage(_, project, editor, REFACTORING_NAME))

    val fileEncloser = ScalaRefactoringUtil.fileEncloser(startOffset, file)
    val occurrences: Array[TextRange] = ScalaRefactoringUtil.getOccurrenceRanges(ScalaRefactoringUtil.unparExpr(expr), fileEncloser)
    runRefactoring(startOffset, endOffset, file, editor, expr, occurrences, "value", types(0), replaceAll, isVariable = false)
  }

}

object ScalaIntroduceVariableHandler {
  val REVERT_INFO: Key[ScalaRefactoringUtil.RevertInfo] = new Key("RevertInfo")
}