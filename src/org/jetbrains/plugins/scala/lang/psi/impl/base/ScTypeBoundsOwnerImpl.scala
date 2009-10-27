package org.jetbrains.plugins.scala
package lang
package psi
package impl
package base

import api.toplevel.ScTypeBoundsOwner
import lexer.ScalaTokenTypes
import com.intellij.psi.util.PsiTreeUtil
import api.base.types.ScTypeElement
import psi.types.{ScType, Nothing, Any}
import psi.types.result.{TypingContext, Success, TypeResult}

trait ScTypeBoundsOwnerImpl extends ScTypeBoundsOwner {
  //todo[CYCLIC]
  def lowerBound: TypeResult[ScType] = wrapWith(lowerTypeElement, Nothing) flatMap ( _.getType(TypingContext.empty) )

  def upperBound: TypeResult[ScType] = wrapWith(upperTypeElement, Any) flatMap ( _.getType(TypingContext.empty) )

  override def viewBound: Option[ScType] = viewTypeElement flatMap (_.getType(TypingContext.empty) match {
    case Success(t, _) => Some(t)
    case _ => None
  })

  override def upperTypeElement: Option[ScTypeElement] = {
    val tUpper = findLastChildByType(ScalaTokenTypes.tUPPER_BOUND)
    if (tUpper != null) {
      PsiTreeUtil.getNextSiblingOfType(tUpper, classOf[ScTypeElement]) match {
        case null => None
        case te => Some(te)
      }
    } else None
  }

  override def lowerTypeElement: Option[ScTypeElement] = {
    val tLower = findLastChildByType(ScalaTokenTypes.tLOWER_BOUND)
    if (tLower != null) {
      PsiTreeUtil.getNextSiblingOfType(tLower, classOf[ScTypeElement]) match {
        case null => None
        case te => Some(te)
      }
    } else None
  }


  override def viewTypeElement: Option[ScTypeElement] = {
    val tView = findLastChildByType(ScalaTokenTypes.tVIEW)
    if (tView != null) {
      PsiTreeUtil.getNextSiblingOfType(tView, classOf[ScTypeElement]) match {
        case null => None
        case te => Some(te)
      }
    } else None
  }
}