package org.jetbrains.plugins.scala.debugger.smartStepInto

import com.intellij.debugger.engine._
import com.intellij.debugger.impl.DebuggerUtilsEx
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Range
import com.sun.jdi.Location
import org.jetbrains.plugins.scala.debugger.evaluation.util.DebuggerUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.ScMethodLike
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScObject, ScTemplateDefinition}
import org.jetbrains.plugins.scala.lang.psi.types.ValueClassType

/**
 * Nikolay.Tropin
 * 2014-06-10
 */

class ScalaMethodFilter(function: ScMethodLike, callingExpressionLines: Range[Integer]) extends MethodFilter {

  val myTargetMethodSignature = DebuggerUtil.getFunctionJVMSignature(function)
  val myDeclaringClassName = {
    val clazz = PsiTreeUtil.getParentOfType(function, classOf[ScTemplateDefinition])
    DebuggerUtil.getClassJVMName(clazz, clazz.isInstanceOf[ScObject] || ValueClassType.isValueClass(clazz))
  }
  val funName = function match {
    case c: ScMethodLike if c.isConstructor => "<init>"
    case fun: ScFunction => fun.name
    case _ => "!unknownName!"
  }

  override def locationMatches(process: DebugProcessImpl, location: Location): Boolean = {
    val method = location.method()
    if (!method.name.startsWith(funName)) return false
    if (myTargetMethodSignature != null && method.signature() != myTargetMethodSignature.getName(process)) false
    else DebuggerUtilsEx.isAssignableFrom(myDeclaringClassName.getName(process), location.declaringType)
  }

  override def getCallingExpressionLines = callingExpressionLines
}
