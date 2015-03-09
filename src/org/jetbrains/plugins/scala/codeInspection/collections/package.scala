package org.jetbrains.plugins.scala.codeInspection

import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.CachedValueProvider.Result
import com.intellij.psi.util.{CachedValueProvider, CachedValuesManager, PsiTreeUtil}
import com.intellij.psi.{PsiElement, PsiMethod, PsiType}
import org.jetbrains.plugins.scala.debugger.evaluation.ScalaEvaluatorBuilderUtil
import org.jetbrains.plugins.scala.extensions.{ExpressionType, PsiNamedElementExt, ResolvesTo, childOf}
import org.jetbrains.plugins.scala.lang.psi.api.base.{ScLiteral, ScReferenceElement}
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScFunctionDefinition, ScValue, ScVariable}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScMember, ScObject}
import org.jetbrains.plugins.scala.lang.psi.api.{InferUtil, ScalaRecursiveElementVisitor}
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.lang.psi.types.ScFunctionType
import org.jetbrains.plugins.scala.lang.psi.types.result.{Success, TypingContext}
import org.jetbrains.plugins.scala.lang.psi.{ScalaPsiUtil, types}
import org.jetbrains.plugins.scala.lang.resolve.ScalaResolveResult
import org.jetbrains.plugins.scala.project.ProjectPsiElementExt
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel.Scala_2_9
import org.jetbrains.plugins.scala.settings.ScalaApplicationSettings

import scala.annotation.tailrec

/**
 * @author Nikolay.Tropin
 */
package object collections {
  def likeCollectionClasses = ScalaApplicationSettings.getInstance().getLikeCollectionClasses
  def likeOptionClasses = ScalaApplicationSettings.getInstance().getLikeOptionClasses

  val monadicMethods = Set("map", "flatMap", "filter", "withFilter")
  val foldMethodNames = Set("foldLeft", "/:", "foldRight", ":\\", "fold")
  val reduceMethodNames = Set("reduce", "reduceLeft", "reduceRight")

  def invocation(methodName: String) = new InvocationTemplate(methodName == _)
  def invocation(methodNames: Set[String]) = new InvocationTemplate(methodNames.contains)

  private[collections] val `.exists` = invocation("exists").from(likeCollectionClasses)
  private[collections] val `.filter` = invocation("filter").from(likeCollectionClasses)
  private[collections] val `.map` = invocation("map").from(likeCollectionClasses)
  private[collections] val `.headOption` = invocation("headOption").from(likeCollectionClasses)
  private[collections] val `.sizeOrLength` = invocation(Set("size", "length")).from(likeCollectionClasses)
  private[collections] val `.find` = invocation("find").from(likeCollectionClasses)

  private[collections] val `.isDefined` = invocation(Set("isDefined", "nonEmpty")).from(likeOptionClasses)
  private[collections] val `.isEmptyOnOption` = invocation("isEmpty").from(likeOptionClasses)
  private[collections] val `.isEmpty` = invocation("isEmpty").from(likeCollectionClasses)
  private[collections] val `.nonEmpty` = invocation("nonEmpty").from(likeCollectionClasses)

  private[collections] val `.fold` = invocation(foldMethodNames).from(likeCollectionClasses)
  private[collections] val `.foldLeft` = invocation(Set("foldLeft", "/:")).from(likeCollectionClasses)
  private[collections] val `.reduce` = invocation(reduceMethodNames).from(likeCollectionClasses)
  private[collections] val `.getOrElse` = invocation("getOrElse").from(likeOptionClasses)
  private[collections] val `.getOnMap` = invocation("get").from(likeCollectionClasses).ref(checkResolveToMap)
  private[collections] val `.mapOnOption` = invocation("map").from(likeOptionClasses).ref(checkScalaVersion)
  private[collections] val `.sort` = invocation(Set("sortWith", "sortBy", "sorted")).from(likeCollectionClasses)
  private[collections] val `.reverse` = invocation("reverse").from(likeCollectionClasses)
  private[collections] val `.iterator` = invocation("iterator").from(likeCollectionClasses)
  private[collections] val `.apply` = invocation("apply").from(likeCollectionClasses)
  private[collections] val `.zip` = invocation("zip").from(likeCollectionClasses)
  private[collections] val `.indices` = invocation("indices").from(likeCollectionClasses)
  private[collections] val `!=` = invocation("!=")
  private[collections] val `==` = invocation(Set("==", "equals"))
  private[collections] val `>` = invocation(">")
  private[collections] val `>=` = invocation(">=")
  private[collections] val `!` = invocation(Set("!", "unary_!"))

  private[collections] val `.monadicMethod` = invocation(monadicMethods).from(likeCollectionClasses)

  object scalaNone {
    def unapply(expr: ScExpression): Boolean = {
      expr match {
        case ResolvesTo(obj: ScObject) if obj.qualifiedName == "scala.None" => true
        case _ => false
      }
    }
  }

  object literal {
    def unapply(expr: ScExpression): Option[String] = {
      expr match {
        case lit: ScLiteral => Some(lit.getText)
        case _ => None
      }
    }
  }

  object returnsBoolean {
    def unapply(expr: ScExpression): Boolean = {
      expr.getType(TypingContext.empty) match {
        case Success(result, _) =>
          result match {
            case ScFunctionType(returnType, _) => returnType.conforms(types.Boolean)
            case _ => false
          }
        case _ => false
      }
    }
  }

  object binaryOperation {
    def unapply(expr: ScExpression): Option[String] = {
      val operRef = stripped(expr) match {
        case ScFunctionExpr(Seq(x, y), Some(result)) =>
          def checkResolve(left: ScExpression, right: ScExpression) = (stripped(left), stripped(right)) match {
            case (leftRef: ScReferenceExpression, rightRef: ScReferenceExpression) =>
              Set(leftRef.resolve(), rightRef.resolve()) equals Set(x, y)
            case _ => false
          }
          stripped(result) match {
            case ScInfixExpr(left, oper, right) if checkResolve(left, right) => Some(oper)
            case ScMethodCall(refExpr: ScReferenceExpression, Seq(left, right)) if checkResolve(left, right) => Some(refExpr)
            case _ => None
          }
        case ScInfixExpr(underscore(), oper, underscore()) => Some(oper)
        case ScMethodCall(refExpr: ScReferenceExpression, Seq(underscore(), underscore()))  => Some(refExpr)
        case _ => None
      }
      operRef.map(_.refName)
    }
  }

  object andCondition {
    def unapply(expr: ScExpression): Option[ScExpression] = {
      stripped(expr) match {
        case ScFunctionExpr(Seq(x, y), Some(result)) =>
          stripped(result) match {
            case ScInfixExpr(left, oper, right) if oper.refName == "&&" =>
              (stripped(left), stripped(right)) match {
                case (leftRef: ScReferenceExpression, right: ScExpression)
                  if leftRef.resolve() == x && isIndependentOf(right, x) =>
                  val secondArgName = y.getName
                  val funExprText = secondArgName + " => " + right.getText
                  Some(ScalaPsiElementFactory.createExpressionWithContextFromText(funExprText, expr.getContext, expr))
                case _ => None
              }
            case _ => None
          }
        case ScInfixExpr(underscore(), oper, right) if oper.refName == "&&" => Some(right)
        case _ => None
      }
    }
  }

  object equalsWith {
    def unapply(expr: ScExpression): Option[ScExpression] = {
      stripped(expr) match {
        case ScFunctionExpr(Seq(x), Some(result)) =>
          stripped(result) match {
            case ScInfixExpr(left, oper, right) if oper.refName == "==" =>
              (stripped(left), stripped(right)) match {
                case (leftRef: ScReferenceExpression, rightExpr)
                  if leftRef.resolve() == x && isIndependentOf(rightExpr, x) =>
                  Some(rightExpr)
                case (leftExpr: ScExpression, rightRef: ScReferenceExpression)
                  if rightRef.resolve() == x && isIndependentOf(leftExpr, x) =>
                  Some(leftExpr)
                case _ => None
              }
            case _ => None
          }
        case ScInfixExpr(underscore(), oper, right) if oper.refName == "==" => Some(right)
        case ScInfixExpr(left, oper, underscore()) if oper.refName == "==" => Some(left)
        case _ => None
      }
    }
  }

  object underscore {
    def unapply(expr: ScExpression): Boolean = {
      stripped(expr) match {
        case ScParenthesisedExpr(underscore()) => true
        case typed: ScTypedStmt if typed.expr.isInstanceOf[ScUnderscoreSection] => true
        case und: ScUnderscoreSection => true
        case _ => false
      }
    }
  }

  def invocationText(qual: ScExpression, methName: String, args: Seq[ScExpression]) = {
    val qualText = qual.getText
    val argsText = argListText(args)
    qual match {
      case _ childOf ScInfixExpr(`qual`, _, _) if args.size == 1 =>
        s"${qual.getText} $methName ${args.head.getText}"
      case infix: ScInfixExpr => s"($qualText).$methName$argsText"
      case _ => s"$qualText.$methName$argsText"
    }
  }

  def argListText(args: Seq[ScExpression]) = {
    args match {
      case Seq(p: ScParenthesisedExpr) => p.getText
      case Seq(b @ ScBlock(fe: ScFunctionExpr)) => b.getText
      case Seq(ScBlock(stmt: ScBlockStatement)) => s"(${stmt.getText})"
      case Seq(b: ScBlock) => b.getText
      case Seq((fe: ScFunctionExpr) childOf (b: ScBlockExpr)) => b.getText
      case Seq(other) => s"(${other.getText})"
      case seq if seq.size > 1 => seq.map(_.getText).mkString("(", ", ", ")")
      case _ => ""
    }
  }


  private def checkResolveToMap(memberRef: ScReferenceElement) = memberRef.resolve() match {
    case m: ScMember => Option(m.containingClass).exists(_.name.toLowerCase.contains("map"))
    case _ => false
  }

  private def checkScalaVersion(elem: PsiElement) = { //there is no Option.fold in Scala 2.9
    elem.scalaLanguageLevel.map(_ > Scala_2_9).getOrElse(true)
  }

  def implicitParameterExistsFor(methodName: String, baseExpr: ScExpression): Boolean = {
    val sumExpr = ScalaPsiElementFactory.createExpressionWithContextFromText(s"${baseExpr.getText}.$methodName", baseExpr.getContext, baseExpr)
    sumExpr.findImplicitParameters match {
      case Some(Seq(srr: ScalaResolveResult)) if srr.element.name == InferUtil.notFoundParameterName => false
      case Some(Seq(srr: ScalaResolveResult, _*)) => true
      case _ => false
    }
  }

  @tailrec
  def stripped(expr: ScExpression): ScExpression = {
    expr match {
      case ScParenthesisedExpr(inner) => stripped(inner)
      case ScBlock(inner: ScExpression) => stripped(inner)
      case _ => expr
    }
  }

  def isIndependentOf(expr: ScExpression, parameter: ScParameter): Boolean = {
    var result = true
    val name = parameter.getName
    val visitor = new ScalaRecursiveElementVisitor() {
      override def visitReferenceExpression(ref: ScReferenceExpression) {
        if (ref.refName == name && ref.resolve() == parameter) result = false
        super.visitReferenceExpression(ref)
      }
    }
    expr.accept(visitor)
    result
  }

  def checkResolve(expr: ScExpression, patterns: Array[String]): Boolean = {
    expr match {
      case ref: ScReferenceExpression =>
        import org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings.nameFitToPatterns
        ref.resolve() match {
          case obj: ScObject =>
            nameFitToPatterns(obj.qualifiedName, patterns)
          case member: ScMember =>
            val clazz = member.containingClass
            if (clazz == null) false
            else nameFitToPatterns(clazz.qualifiedName, patterns)
          case _ => false
        }
      case _ => false
    }
  }

  private val sideEffectsCollectionMethods = Set("append", "appendAll", "clear", "insert", "insertAll",
    "prepend", "prependAll", "reduceToSize", "remove", "retain",
    "transform", "trimEnd", "trimStart", "update",
    "push", "pushAll", "pop", "dequeue", "dequeueAll", "dequeueFirst", "enqueue",
    "next")

  private class SideEffectsProvider(expr: ScExpression) extends CachedValueProvider[Seq[ScExpression]] {
    override def compute(): Result[Seq[ScExpression]] = Result.create(computeExprsWithSideEffects(expr), expr)

    private def computeExprsWithSideEffects(expr: ScExpression): Seq[ScExpression] = {

      def isSideEffectCollectionMethod(ref: ScReferenceExpression): Boolean = {
        val refName = ref.refName
        (refName.endsWith("=") || refName.endsWith("=:") || sideEffectsCollectionMethods.contains(refName)) &&
                checkResolve(ref, Array("scala.collection.mutable._", "scala.collection.Iterator"))
      }

      def isSetter(ref: ScReferenceExpression): Boolean = {
        ref.refName.startsWith("set") || ref.refName.endsWith("_=")
      }

      def hasUnitReturnType(ref: ScReferenceExpression): Boolean = {
        ref match {
          case MethodRepr(ExpressionType(ScFunctionType(_, _)), _, _, _) => false
          case ResolvesTo(fun: ScFunction) => fun.hasUnitResultType
          case ResolvesTo(m: PsiMethod) => m.getReturnType == PsiType.VOID
          case _ => false
        }
      }

      object definedOutside {
        def unapply(ref: ScReferenceElement): Option[PsiElement] = ref match {
          case ResolvesTo(elem: PsiElement) if !PsiTreeUtil.isAncestor(expr, elem, false) => Some(elem)
          case _ => None
        }
      }

      val predicate: (PsiElement) => Boolean = {
        case `expr` => true
        case ScFunctionExpr(_, _) childOf `expr` => true
        case (e: ScExpression) childOf `expr` if ScUnderScoreSectionUtil.underscores(e).nonEmpty => true
        case fun: ScFunctionDefinition => false
        case elem: PsiElement => !ScalaEvaluatorBuilderUtil.isGenerateClass(elem)
      }

      val sameLevelIterator = expr.depthFirst(predicate).filter(predicate)

      sameLevelIterator.collect {
        case assign @ ScAssignStmt(definedOutside(ScalaPsiUtil.inNameContext(_: ScVariable)), _) =>
          assign
        case assign @ ScAssignStmt(mc @ ScMethodCall(definedOutside(_), _), _) if mc.isUpdateCall =>
          assign
        case infix @ ScInfixExpr(definedOutside(ScalaPsiUtil.inNameContext(v: ScVariable)), _, _) if infix.isAssignmentOperator =>
          infix
        case MethodRepr(itself, Some(definedOutside(ScalaPsiUtil.inNameContext(v @ (_ : ScVariable | _: ScValue)))), Some(ref), _)
          if isSideEffectCollectionMethod(ref) || isSetter(ref) || hasUnitReturnType(ref) => itself
        case MethodRepr(itself, None, Some(ref @ definedOutside(_)), _) if hasUnitReturnType(ref) => itself
      }.toSeq
    }
  }

  def exprsWithSideEffects(expr: ScExpression) = CachedValuesManager.getCachedValue(expr, new SideEffectsProvider(expr))

  def hasSideEffects(expr: ScExpression) = exprsWithSideEffects(expr).nonEmpty

  def rightRangeInParent(expr: ScExpression, parent: ScExpression): TextRange = {
    val endOffset = parent.getTextRange.getEndOffset

    val startOffset = expr match {
      case _ childOf ScInfixExpr(`expr`, op, _) => op.nameId.getTextOffset
      case _ childOf (ref @ ScReferenceExpression.withQualifier(`expr`)) => ref.nameId.getTextOffset
      case _ => expr.getTextRange.getEndOffset
    }
    TextRange.create(startOffset, endOffset).shiftRight( - parent.getTextOffset)
  }

}

