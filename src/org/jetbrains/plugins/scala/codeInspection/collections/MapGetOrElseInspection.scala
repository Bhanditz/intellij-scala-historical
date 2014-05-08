package org.jetbrains.plugins.scala
package codeInspection.collections

import org.jetbrains.plugins.scala.codeInspection.InspectionBundle
import org.jetbrains.plugins.scala.codeInspection.collections.OperationOnCollectionsUtil._
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.types.result.Success
import org.jetbrains.plugins.scala.lang.psi.types.ScFunctionType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.config.ScalaVersionUtil

/**
 * Nikolay.Tropin
 * 2014-05-05
 */
class MapGetOrElseInspection extends OperationOnCollectionInspection {
  override def possibleSimplificationTypes: Array[SimplificationType] =
    Array(new MapGetOrElse(this))
}

class MapGetOrElse(inspection: OperationOnCollectionInspection) extends SimplificationType(inspection) {
  def hint = InspectionBundle.message("map.getOrElse.hint")

  override def getSimplification(last: MethodRepr, second: MethodRepr): List[Simplification] = {
    (last.optionalMethodRef, second.optionalMethodRef) match {
      case (Some(lastRef), Some(secondRef)) if lastRef.refName == "getOrElse" &&
              secondRef.refName == "map" &&
              checkScalaVersion(lastRef) &&
              checkResolve(lastRef, likeOptionClasses) &&
              checkResolve(secondRef, likeOptionClasses) &&
              suitableTypes(second.args(0), last.args(0))=>
        createSimplification(second, last.itself, last.args ++ second.args, "fold")
      case _ => Nil
    }
  }

  def checkScalaVersion(elem: PsiElement) = { //there is no Option.fold in Scala 2.9
    val isScala2_9 = ScalaVersionUtil.isGeneric(elem, false, ScalaVersionUtil.SCALA_2_9)
    !isScala2_9
  }

  def suitableTypes(mapArg: ScExpression, goeArg: ScExpression): Boolean = {
    mapArg.getType() match {
      case Success(ScFunctionType(retType, _), _) => retType.conforms(goeArg.getType().getOrNothing)
      case _ => false
    }
  }
}