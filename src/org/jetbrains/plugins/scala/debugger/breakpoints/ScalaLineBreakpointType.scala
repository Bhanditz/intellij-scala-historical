package org.jetbrains.plugins.scala.debugger.breakpoints

import java.util.{Collections, List => JList}
import javax.swing.Icon

import com.intellij.debugger.SourcePosition
import com.intellij.debugger.ui.breakpoints._
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.{DumbService, Project}
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi._
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.impl.XSourcePositionImpl
import com.intellij.xdebugger.impl.breakpoints.XLineBreakpointImpl
import com.intellij.xdebugger.{XDebuggerUtil, XSourcePosition}
import org.jetbrains.annotations.{NotNull, Nullable}
import org.jetbrains.java.debugger.breakpoints.properties.JavaLineBreakpointProperties
import org.jetbrains.plugins.scala.debugger.ScalaPositionManager
import org.jetbrains.plugins.scala.debugger.evaluation.util.DebuggerUtil
import org.jetbrains.plugins.scala.extensions.{Both, ElementType}
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScNamedElement
import org.jetbrains.plugins.scala.{ScalaBundle, ScalaLanguage}

import scala.collection.JavaConverters._

/**
 * @author Nikolay.Tropin
 */
class ScalaLineBreakpointType extends JavaLineBreakpointType("scala-line", ScalaBundle.message("line.breakpoints.tab.title")) {

  override def getDisplayName: String = ScalaBundle.message("line.breakpoints.tab.title")

  override def canPutAt(@NotNull file: VirtualFile, line: Int, @NotNull project: Project): Boolean = {
    val psiFile: PsiFile = PsiManager.getInstance(project).findFile(file)
    if (psiFile == null || !psiFile.getLanguage.is(ScalaLanguage.Instance)) {
      return false
    }
    val document: Document = FileDocumentManager.getInstance.getDocument(file)
    if (document == null) return false

    var result: Boolean = false
    val processor: Processor[PsiElement] = new Processor[PsiElement] {
      override def process(e: PsiElement): Boolean = e match {
        case ElementType(ScalaTokenTypes.kPACKAGE | ScalaTokenTypes.kIMPORT) => false
        case ws: PsiWhiteSpace => true
        case _ if PsiTreeUtil.getParentOfType(e, classOf[PsiComment]) != null => true
        case _ if PsiTreeUtil.getParentOfType(e, classOf[ScExpression]) != null =>
          result = true
          false
        case _ => true
      }
    }
    XDebuggerUtil.getInstance.iterateLine(project, document, line, processor)
    result
  }

  @NotNull
  override def computeVariants(@NotNull project: Project, @NotNull position: XSourcePosition): JList[JavaLineBreakpointType#JavaBreakpointVariant] = {
    val emptyList = Collections.emptyList[JavaLineBreakpointType#JavaBreakpointVariant]
    val file = PsiManager.getInstance(project).findFile(position.getFile) match {
      case null => return emptyList
      case sf: ScalaFile => sf
      case _ => return emptyList
    }
    val line = position.getLine

    ScalaPositionManager.positionsOnLine(file, line) match {
      case Seq() => return emptyList
      case Seq(elem) if !ScalaPositionManager.isLambda(elem) =>
        return Collections.singletonList(new JavaBreakpointVariant(position))
      case _ =>
    }

    var res: List[JavaLineBreakpointType#JavaBreakpointVariant] = List(new JavaBreakpointVariant(position)) //all variants

    val elementAtLine = SourcePosition.createFromLine(file, line).getElementAt
    val startMethod = DebuggerUtil.getContainingMethod(elementAtLine) match {
      case Some(e) => e
      case None => return emptyList
    }
    val lambdas = ScalaPositionManager.lambdasOnLine(file, line)

    if (!lambdas.contains(startMethod))
      res = res :+ new ExactScalaBreakpointVariant(position, startMethod, -1)

    var ordinal: Int = 0
    for ((lambda, ord) <- lambdas.zipWithIndex) {
      res = res :+ new ExactScalaBreakpointVariant(XSourcePositionImpl.createByElement(lambda), lambda, ordinal)
      ordinal += 1
    }
    res.asJava
  }

  override def matchesPosition(@NotNull breakpoint: LineBreakpoint[_], @NotNull position: SourcePosition): Boolean = {
    val method = getContainingMethod(breakpoint)
    if (method == null) return false

    if (!breakpoint.isInstanceOf[RunToCursorBreakpoint[_]] && lambdaOrdinal(breakpoint) == null) return true

    DebuggerUtil.inTheMethod(position, method)
  }

  @Nullable
  override def getContainingMethod(@NotNull breakpoint: LineBreakpoint[_]): PsiElement = {
    val position: SourcePosition = breakpoint.getSourcePosition
    if (position == null || position.getElementAt == null) return null

    val ordinal = lambdaOrdinal(breakpoint)
    val lambdas = ScalaPositionManager.lambdasOnLine(position.getFile, position.getLine)
    if (ordinal == null || ordinal == -1 || ordinal > lambdas.size - 1)
      DebuggerUtil.getContainingMethod(position.getElementAt).orNull
    else lambdas(ordinal)
  }

  override def getHighlightRange(breakpoint: XLineBreakpoint[JavaLineBreakpointProperties]): TextRange = {
    BreakpointManager.getJavaBreakpoint(breakpoint) match {
      case lineBp: LineBreakpoint[_] if lambdaOrdinal(lineBp) != null =>
        val dumbService = DumbService.getInstance(lineBp.getProject)
        if (dumbService.isDumb) {
          breakpoint match {
            case breakpointImpl: XLineBreakpointImpl[_] =>
              dumbService.smartInvokeLater(new Runnable {
                override def run(): Unit = {
                  breakpointImpl.getHighlighter.dispose()
                  breakpointImpl.updateUI()
                }
              })
            case _ =>
          }
          null
        }
        else Option(getContainingMethod(lineBp)).map(_.getTextRange).orNull
      case _ => null
    }

  }

  private def lambdaOrdinal(breakpoint: LineBreakpoint[_]): Integer = {
    val xBreakpoint = breakpoint.getXBreakpoint
    if (xBreakpoint != null) {
      xBreakpoint.getProperties match {
        case jp: JavaLineBreakpointProperties => jp.getLambdaOrdinal
        case _ => null
      }
    }
    else null
  }

  override def getPriority: Int = super.getPriority + 1

  private class ExactScalaBreakpointVariant(position: XSourcePosition, element: PsiElement, lambdaOrdinal: Integer)
    extends ExactJavaBreakpointVariant(position, element, lambdaOrdinal) {

    private val isLambda = lambdaOrdinal != null && lambdaOrdinal >= 0

    override def getIcon: Icon = {
      if (isLambda) AllIcons.Nodes.Function
      else element match {
        case e @ (_: PsiMethod | _: PsiClass | _: PsiFile) => e.getIcon(0)
        case _ => AllIcons.Debugger.Db_set_breakpoint
      }

    }

    override def getText: String = {
      if (isLambda) super.getText
      else {
        val ending = element match {
          case Both(f: ScFunction, named: ScNamedElement) => s"function ${named.name}"
          case f: ScalaFile => "containing file"
          case _ => "containing block"
        }
        s"line in $ending"
      }
    }

  }
}
