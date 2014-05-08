package org.jetbrains.plugins.scala
package codeInspection.collections

import org.jetbrains.plugins.scala.lang.psi.api.expr._
import Utils._
import com.intellij.openapi.util.TextRange
import org.jetbrains.plugins.scala.codeInspection.InspectionBundle
import org.jetbrains.plugins.scala.lang.psi.types.result.{Success, TypingContext}
import org.jetbrains.plugins.scala.lang.psi.types.{ScFunctionType, ScType, ScParameterizedType, ScDesignatorType}
import org.jetbrains.plugins.scala.lang.psi.impl.{ScalaPsiManager, ScalaPsiElementFactory}
import com.intellij.psi.search.GlobalSearchScope
import scala.Some

/**
 * Nikolay.Tropin
 * 5/21/13
 */
class Simplification(val replacementText: String, val hint: String, val rangeInParent: TextRange)

/*
 * After defining new simplification type one needs to add it to the
 * OperationOnCollectionInspection.possibleSimplificationTypes
 * */
abstract class SimplificationType(inspection: OperationOnCollectionInspection) {
  def hint: String
  def description: String = hint
  def getSimplification(last: MethodRepr, second: MethodRepr): List[Simplification]

  val likeOptionClasses = inspection.getLikeOptionClasses
  val likeCollectionClasses = inspection.getLikeCollectionClasses

  def createSimplification(methodToBuildFrom: MethodRepr,
                           parentExpr: ScExpression,
                           args: Seq[ScExpression],
                           newMethodName: String): List[Simplification] = {
    val rangeInParent = methodToBuildFrom.rightRangeInParent(parentExpr)
    methodToBuildFrom.itself match {
      case ScInfixExpr(left, _, right) if args.size == 1 =>
        List(new Simplification(s"${left.getText} $newMethodName ${args(0).getText}", hint, rangeInParent))
      case _: ScMethodCall | _: ScInfixExpr =>
        methodToBuildFrom.optionalBase match {
          case Some(baseExpr) =>
            val baseText = baseExpr.getText
            val argsText = bracedArgs(args)
            List(new Simplification(s"$baseText.$newMethodName$argsText", hint, rangeInParent))
          case _ => Nil
        }
      case _ => Nil
    }
  }

  def swapMethodsSimplification(last: MethodRepr, second: MethodRepr): List[Simplification] = {
    val range = second.rightRangeInParent(last.itself)
    def refWithArgumentsText(method: MethodRepr): Option[String] = (method.itself, method.optionalBase) match {
      case (_: ScMethodCall | _: ScReferenceExpression, Some(baseExpr)) =>
        val startIndex = baseExpr.getTextRange.getEndOffset - method.itself.getTextRange.getStartOffset
        val text = method.itself.getText
        if (startIndex > 0 && startIndex < text.length) Option(text.substring(startIndex))
        else None
      case (ScInfixExpr(left, op, right), _) => Some(s".${op.refName}${bracedArg(method, right)}")
      case _ => None
    }
    for {
      lastText <- refWithArgumentsText(last)
      secondText <- refWithArgumentsText(second)
      baseExpr <- second.optionalBase
    } {
      return List(new Simplification(s"${baseExpr.getText}$lastText$secondText", hint, range))
    }
    Nil
  }

  private def bracedArg(method: MethodRepr, argText: String) = method.args match {
    case Seq(_: ScBlock) => argText
    case Seq(_: ScParenthesisedExpr) => argText
    case _ if argText == "" => ""
    case _ => s"($argText)"
  }

  private def bracedArgs(args: Seq[ScExpression]) = {
    args.map {
      case p: ScParenthesisedExpr if p.expr.isDefined => p.getText
      case ScBlock(stmt: ScBlockStatement) => s"(${stmt.getText})"
      case b: ScBlock => b.getText
      case other => s"(${other.getText})"
    }.mkString
  }
}

class MapGetOrElseFalse(inspection: OperationOnCollectionInspection) extends SimplificationType(inspection) {
  def hint = InspectionBundle.message("map.getOrElse.false.hint")
  def getSimplification(last: MethodRepr, second: MethodRepr): List[Simplification] = {
    val (lastArgs, secondArgs) = (last.args, second.args)
    (last.optionalMethodRef, second.optionalMethodRef) match {
      case (Some(lastRef), Some(secondRef))
        if lastRef.refName == "getOrElse" &&
              secondRef.refName == "map" &&
              isLiteral(lastArgs, text = "false") &&
              secondArgs.size == 1 &&
              isFunctionWithBooleanReturn(secondArgs(0)) &&
              checkResolve(lastRef, likeOptionClasses) &&
              checkResolve(secondRef, likeOptionClasses) =>

          createSimplification(second, last.itself, second.args, "exists")
      case _ => Nil
    }
  }
}

class FindIsDefined(inspection: OperationOnCollectionInspection) extends SimplificationType(inspection) {
  def hint = InspectionBundle.message("find.isDefined.hint")

  def getSimplification(last: MethodRepr, second: MethodRepr): List[Simplification] = {
    (last.optionalMethodRef, second.optionalMethodRef) match {
      case (Some(lastRef), Some(secondRef))
        if lastRef.refName == "isDefined" &&
              secondRef.refName == "find" &&
              checkResolve(lastRef, likeOptionClasses) &&
              checkResolve(secondRef, likeCollectionClasses) =>

          createSimplification(second, last.itself, second.args, "exists")
      case _ => Nil
    }
  }
}

class FindNotEqualsNone(inspection: OperationOnCollectionInspection) extends SimplificationType(inspection){

  def hint = InspectionBundle.message("find.notEquals.none.hint")

  def getSimplification(last: MethodRepr, second: MethodRepr): List[Simplification] = {
    val lastArgs = last.args
    (last.optionalMethodRef, second.optionalMethodRef) match {
      case (Some(lastRef), Some(secondRef))
        if lastRef.refName == "!=" &&
                secondRef.refName == "find" &&
                lastArgs.size == 1 &&
                lastArgs(0).getText == "None" &&
                checkResolve(lastArgs(0), Array("scala.None")) &&
                checkResolve(secondRef, likeCollectionClasses) =>

          createSimplification(second, last.itself, second.args, "exists")
      case _ => Nil
    }
  }
}

class FilterHeadOption(inspection: OperationOnCollectionInspection) extends SimplificationType(inspection) {

  def hint = InspectionBundle.message("filter.headOption.hint")

  def getSimplification(last: MethodRepr, second: MethodRepr): List[Simplification] = {
    (last.optionalMethodRef, second.optionalMethodRef) match {
      case (Some(lastRef), Some(secondRef))
        if lastRef.refName == "headOption" &&
              secondRef.refName == "filter" &&
              checkResolve(lastRef, likeCollectionClasses) &&
              checkResolve(secondRef, likeCollectionClasses) =>

          createSimplification(second, last.itself, second.args, "find")
      case _ => Nil
    }
  }
}

class FoldLeftSum(inspection: OperationOnCollectionInspection) extends SimplificationType(inspection){

  def hint = InspectionBundle.message("foldLeft.sum.hint")

  private def checkNotString(optionalBase: Option[ScExpression]): Boolean = {
    optionalBase match {
      case Some(expr) =>
        expr.getType(TypingContext.empty).getOrAny match {
          case ScParameterizedType(_, Seq(scType)) =>
            val project = expr.getProject
            val manager = ScalaPsiManager.instance(project)
            val stringClass = manager.getCachedClass(GlobalSearchScope.allScope(project), "java.lang.String")
            if (stringClass == null) return false
            val stringType = new ScDesignatorType(stringClass)
            if (scType.conforms(stringType)) false
            else {
              val exprWithSum = ScalaPsiElementFactory.createExpressionFromText(expr.getText + ".sum", expr.getContext).asInstanceOf[ScExpression]
              exprWithSum.findImplicitParameters match {
                case Some(implPar) => true
                case _ => false
              }
            }
          case _ => false
        }
      case _ => false
    }
  }
  def getSimplification(last: MethodRepr, second: MethodRepr): List[Simplification] = {
    (last.optionalMethodRef, second.optionalMethodRef) match {
      case (None, Some(secondRef))
        if List("foldLeft", "/:").contains(secondRef.refName) &&
              isLiteral(second.args, "0") &&
              last.args.size == 1 &&
              isSum(last.args(0)) &&
              checkResolve(secondRef, likeCollectionClasses) &&
              checkNotString(second.optionalBase) =>

        createSimplification(second, last.itself, Nil, "sum")
      case _ => Nil
    }
  }
}

class FoldLeftTrueAnd(inspection: OperationOnCollectionInspection) extends SimplificationType(inspection){

  def hint = InspectionBundle.message("foldLeft.true.and.hint")

  def getSimplification(last: MethodRepr, second: MethodRepr): List[Simplification] = {
    (last.optionalMethodRef, second.optionalMethodRef) match {
      case (None, Some(secondRef))
        if List("foldLeft", "/:").contains(secondRef.refName) &&
              isLiteral(second.args, "true") &&
              last.args.size == 1 &&
              checkResolve(secondRef, likeCollectionClasses) =>

        andWithSomeFunction(last.args(0)).toList.flatMap { fun =>
          createSimplification(second, last.itself, Seq(fun), "forall")
        }
      case _ => Nil
    }
  }
}

class FilterSize(inspection: OperationOnCollectionInspection) extends SimplificationType(inspection) {

  def hint = InspectionBundle.message("filter.size.hint")

  def getSimplification(last: MethodRepr, second: MethodRepr): List[Simplification] = {
    (last.optionalMethodRef, second.optionalMethodRef) match {
      case (Some(lastRef), Some(secondRef)) if List("size", "length").contains(lastRef.refName) &&
              secondRef.refName == "filter" &&
              checkResolve(lastRef, likeCollectionClasses) &&
              checkResolve(secondRef, likeCollectionClasses) =>
        createSimplification(second, last.itself, second.args, "count")
      case _ => Nil
    }
  }
}

class SortFilter(inspection: OperationOnCollectionInspection) extends SimplificationType(inspection) {

  def hint = InspectionBundle.message("sort.filter.hint")

  override def getSimplification(last: MethodRepr, second: MethodRepr): List[Simplification] = {
    (last.optionalMethodRef, second.optionalMethodRef) match {
      case (Some(lastRef), Some(secondRef))
        if lastRef.refName == "filter" &&
                List("sortWith", "sortBy", "sorted").contains(secondRef.refName) &&
                checkResolve(lastRef, likeCollectionClasses) &&
                checkResolve(secondRef, likeCollectionClasses) =>
        swapMethodsSimplification(last, second)
      case _ => Nil
    }
  }
}

class MapGetOrElse(inspection: OperationOnCollectionInspection) extends SimplificationType(inspection) {
  def hint = InspectionBundle.message("map.getOrElse.hint")

  override def getSimplification(last: MethodRepr, second: MethodRepr): List[Simplification] = {
    (last.optionalMethodRef, second.optionalMethodRef) match {
      case (Some(lastRef), Some(secondRef)) if lastRef.refName == "getOrElse" &&
              secondRef.refName == "map" &&
              checkResolve(lastRef, likeOptionClasses) &&
              checkResolve(secondRef, likeOptionClasses) &&
              suitableTypes(second.args(0), last.args(0))=>
        createSimplification(second, last.itself, last.args ++ second.args, "fold")
      case _ => Nil
    }
  }

    def suitableTypes(mapArg: ScExpression, goeArg: ScExpression): Boolean = {
      mapArg.getType() match {
        case Success(ScFunctionType(retType, _), _) => retType.conforms(goeArg.getType().getOrNothing)
        case _ => false
      }
    }
}
