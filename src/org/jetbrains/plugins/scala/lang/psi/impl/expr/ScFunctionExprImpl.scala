package org.jetbrains.plugins.scala
package lang
package psi
package impl
package expr

import org.jetbrains.plugins.scala.lang.psi.ScalaPsiElementImpl
import com.intellij.lang.ASTNode
import api.statements.params.{ScParameter, ScParameters}
import types.result.TypingContext
import org.jetbrains.plugins.scala.lang.psi.controlFlow.impl.{AllVariablesControlFlowPolicy, ScalaControlFlowBuilder}
import org.jetbrains.plugins.scala.lang.psi.controlFlow.{ScControlFlowPolicy, Instruction}
import api.ScalaElementVisitor
import types._
import com.intellij.psi._
import com.intellij.psi.scope._
import org.jetbrains.plugins.scala.lang.psi.api.expr._

/**
 * @author Alexander Podkhalyuzin
 */

class ScFunctionExprImpl(node: ASTNode) extends ScalaPsiElementImpl(node) with ScFunctionExpr {
  override def accept(visitor: PsiElementVisitor): Unit = {
    visitor match {
      case visitor: ScalaElementVisitor => super.accept(visitor)
      case _ => super.accept(visitor)
    }
  }

  override def toString: String = "FunctionExpression"

  def parameters = params.params

  def params = findChildByClass(classOf[ScParameters])

  def result = findChild(classOf[ScExpression])

  override def processDeclarations(processor: PsiScopeProcessor,
                                   state: ResolveState,
                                   lastParent: PsiElement,
                                   place: PsiElement): Boolean = {
    result match {
      case Some(x) if x == lastParent || (lastParent.isInstanceOf[ScalaPsiElement] &&
        x == lastParent.asInstanceOf[ScalaPsiElement].getDeepSameElementInContext)=> {
        for (p <- parameters) {
          if (!processor.execute(p, state)) return false
        }
        true
      }
      case _ => true
    }
  }

  protected override def innerType(ctx: TypingContext) = {
    val paramTypes = (parameters: Seq[ScParameter]).map(_.getType(ctx))
    val resultType = result match {case Some(r) => r.getType(ctx).getOrAny case _ => Unit}
    collectFailures(paramTypes, Nothing)(ScFunctionType(resultType, _)(getProject, getResolveScope))
  }

  private var myControlFlow: Seq[Instruction] = null

  override def getControlFlow(cached: Boolean, policy: ScControlFlowPolicy = AllVariablesControlFlowPolicy) = {
    if (!cached || myControlFlow == null) result match {
      case Some(e) => {
        val builder = new ScalaControlFlowBuilder(null, null, policy)
        myControlFlow = builder.buildControlflow(e)
      }
      case None => myControlFlow = Seq.empty
    }
    myControlFlow
  }

}