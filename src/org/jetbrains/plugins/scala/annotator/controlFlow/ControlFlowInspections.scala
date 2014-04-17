package org.jetbrains.plugins.scala.annotator
package controlFlow

import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import com.intellij.lang.annotation.AnnotationHolder
import org.jetbrains.plugins.scala.lang.psi.controlFlow.ControlFlowUtil
import org.jetbrains.plugins.scala.ScalaBundle
import com.intellij.psi.PsiElement

/**
 * @author ilyas
 */

trait ControlFlowInspections {
  self: ScalaAnnotator =>

  def annotate(element: PsiElement, holder: AnnotationHolder) {
    element match {
      case f: ScFunctionDefinition => //todo: checkBodyForUnreachableStatements(f, holder)
      case _ =>
    }
  }

  protected def checkBodyForUnreachableStatements(method: ScFunctionDefinition, holder: AnnotationHolder) {
    val cfg = method.getControlFlow(cached = false)
    val components = ControlFlowUtil.detectConnectedComponents(cfg)
    if (components.length > 1) {
      for (comp <- components.tail.headOption) {
        comp.toSeq.sortBy(_.num).headOption.flatMap(_.element) match {
          case Some(elem) =>
            holder.createErrorAnnotation(elem, ScalaBundle.message("unreachable.expression"))
          case None =>
        }
      }
    }
  }
}