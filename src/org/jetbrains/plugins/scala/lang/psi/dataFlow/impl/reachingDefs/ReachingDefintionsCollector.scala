package org.jetbrains.plugins.scala.lang.psi.dataFlow.impl.reachingDefs

import _root_.org.jetbrains.plugins.scala.lang.psi.api.base.ScReferenceElement
import _root_.org.jetbrains.plugins.scala.lang.psi.api.{ScalaRecursiveElementVisitor, ScControlFlowOwner}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.dataFlow.DfaEngine
import org.jetbrains.plugins.scala.lang.psi.controlFlow.Instruction
import collection.mutable.ArrayBuffer
import com.intellij.psi.{PsiNamedElement, PsiElement}
import org.jetbrains.plugins.scala.lang.psi.controlFlow.impl.{ReadWriteVariableInstruction, DefineValueInstruction}
import org.jetbrains.plugins.scala.lang.psi.{ScalaPsiUtil, ScalaPsiElement}
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScFunctionExpr
import scala.collection.mutable

/**
 * @author ilyas
 */

object ReachingDefintionsCollector {
  import ReachingDefinitions.{A => RDSet, _}
  def collectVariableInfo(elements: Seq[PsiElement], scope: Seq[PsiElement]): FragmentVariableInfos = {
    val elementsForScope: ArrayBuffer[PsiElement] = new ArrayBuffer
    var element = scope(0)
    while (element != null) {
      elementsForScope += element
      element = element.getNextSibling
    }
    collectVariableInfo(elements, elementToFragmentMapper(elementsForScope.toSeq))
  }
  def collectVariableInfo(elements: Seq[PsiElement], scope: ScalaPsiElement): FragmentVariableInfos =
    collectVariableInfo(elements, elementToScopeMapper(scope))
  /**
   * @param elements a fragment to analyze
   * @param isInScope since Extract Method refactoring is in fact RDC's main client, it should define a scope
   *                  where to look for captured variables
   */
  def collectVariableInfo(elements: Seq[PsiElement], isInScope: (PsiElement) => Boolean): FragmentVariableInfos = {
    import PsiTreeUtil._
    val isInFragment = elementToFragmentMapper(elements)
    // for every reference element, define is it's definition in scope
    val inputInfos = getInputInfo(elements, isInFragment, isInScope)

    // CFG -> DFA
    val commonParent = findCommonParent(elements: _*)
    val cfowner = getParentOfType(commonParent, classOf[ScControlFlowOwner])
    if (cfowner == null) {
      val message = "cfowner == null: " + elements.map(_.getText).mkString("(", ", ", ")") + "\n" + "files: " +
        elements.map(_.getContainingFile.getName).mkString("(", ", ", ")")
      throw new RuntimeException(message)
    }
    val cfg = cfowner.getControlFlow(cached = false) //todo: make cache more right to not get PsiInvalidAccess
    val engine = new DfaEngine(cfg, ReachingDefinitionsInstance, ReachingDefinitionsLattice)
    val dfaResult = engine.performDFA

    // instructions in given fragment
    val fragmentInstructions = filterByFragment(cfg, isInFragment)

    // for every WRITE or VAL define, if it escapes `elements' or not
    // i.e. if there instructions with greater numbers, which are "reached" by WRITE
    // instructions from the fragment

    val outputInfos = computeOutputVariables(fragmentInstructions, dfaResult)

    // take into account scope
    // todo implement

    FragmentVariableInfos(inputInfos, outputInfos)
  }

  private def elementToFragmentMapper(elements: Seq[PsiElement]) = new ((PsiElement) => Boolean) {
    val elem2Outer: mutable.Map[PsiElement, Boolean] = new mutable.HashMap[PsiElement, Boolean]

    def apply(elem: PsiElement): Boolean = elem != null && (elem2Outer.get(elem) match {
      case Some(b) => b
      case None =>
        for (e <- elements) {
          if (PsiTreeUtil.findCommonParent(e, elem) eq e) {
            elem2Outer + (elem -> true)
            return true
          }
        }
        elem2Outer + (elem -> false)
        false
    })
  }

  private def elementToScopeMapper(scope: ScalaPsiElement) = new ((PsiElement) => Boolean) {
    val elem2Outer: mutable.Map[PsiElement, Boolean] = new mutable.HashMap[PsiElement, Boolean]

    def apply(elem: PsiElement): Boolean = elem != null && (elem2Outer.get(elem) match {
      case Some(b) => b
      case None =>
        val b = PsiTreeUtil.findCommonParent(elem, scope) eq scope
        elem2Outer + (elem -> b)
        b
    })
  }

  private def filterByFragment(cfg: Seq[Instruction], checker: (PsiElement) => Boolean) = cfg.filter(i =>
    i.element match {
      case None => false
      case Some(e) => checker(e)
    })

  private def getInputInfo(elements: Seq[PsiElement],
                           isInFragment: (PsiElement) => Boolean,
                           isInScope: (PsiElement) => Boolean): Iterable[VariableInfo] = {
    val inputDefs = new ArrayBuffer[VariableInfo]

    def isInClosure(elem: PsiElement) = {
      val parent = PsiTreeUtil.getParentOfType(elem, classOf[ScFunctionExpr], classOf[ScFunction])
      parent != null && isInFragment(parent)
    }

    val visitor = new ScalaRecursiveElementVisitor {
      override def visitReference(ref: ScReferenceElement) {
        val element = ref.resolve()
        element match {
          case named: PsiNamedElement
            if !isInFragment(named) && isInScope(named) &&
                    !inputDefs.map(_.element).contains(named) =>
            val isReferenceParameter = isInClosure(ref) && ScalaPsiUtil.isLValue(ref)
            inputDefs += VariableInfo(named, isReferenceParameter)
          case _ =>
            super.visitReference(ref)
        }
      }
    }

    for (e <- elements if e.isInstanceOf[ScalaPsiElement]) e.asInstanceOf[ScalaPsiElement].accept(visitor)
    inputDefs
  }

  def computeOutputVariables(innerInstructions: Seq[Instruction],
                             dfaResult: scala.collection.mutable.Map[Instruction, RDSet]): Iterable[VariableInfo] = {
    val buffer = new ArrayBuffer[PsiNamedElement]
    for ((i@ReadWriteVariableInstruction(_, readRef, false), rdset) <- dfaResult if !innerInstructions.contains(i);
         reaching <- rdset if innerInstructions.contains(reaching)) {
      val definitionToRead = readRef.resolve()
      reaching match {
        case DefineValueInstruction(_, named, _)
          if !buffer.contains(named) && (named eq definitionToRead) => buffer += named
        case ReadWriteVariableInstruction(_, ref, true) => ref.resolve() match {
          case named: PsiNamedElement
            if !buffer.contains(named) && (named eq definitionToRead) => buffer += named
          case _ =>
        }
        case _ =>
      }
    }
    buffer.map(VariableInfo(_, isRef = false))
  }


}

case class FragmentVariableInfos(inputVariables: Iterable[VariableInfo],
                                 outputVariables: Iterable[VariableInfo])

object FragmentVariableInfos {
  def empty = FragmentVariableInfos(Nil, Nil)
}

/**
 * @param isRef local variable must be treated as reference parameter
 */
case class VariableInfo(element: PsiNamedElement, isRef: Boolean)