package org.jetbrains.plugins.scala.macroAnnotations

import scala.annotation.StaticAnnotation
import scala.language.experimental.macros
import scala.reflect.macros.whitebox

/**
  * This annotation makes the compiler generate code that calls CachesUtil.get(..,)
  *
  * NOTE: Annotated function should preferably be top-level or in a static object for better performance.
  * The field for Key is generated and it is more efficient to just keep it stored in this field, rather than get
  * it from CachesUtil every time
  *
  * Author: Svyatoslav Ilinskiy
  * Date: 9/25/15.
  */
class CachedInsidePsiElement(psiElement: Any, dependencyItem: Object, useOptionalProvider: Boolean = false) extends StaticAnnotation {
  def macroTransform(annottees: Any*) = macro CachedInsidePsiElement.cachedInsidePsiElementImpl
}

object CachedInsidePsiElement {
  def cachedInsidePsiElementImpl(c: whitebox.Context)(annottees: c.Tree*): c.Expr[Any] = {
    import CachedMacroUtil._
    import c.universe._
    implicit val x: c.type = c
    def parameters: (Tree, Tree, Boolean) = {
      c.prefix.tree match {
        case q"new CachedInsidePsiElement(..$params)" if params.length == 2 =>
          (params.head, modCountParamToModTracker(c)(params(1), params.head), false)
        case q"new CachedInsidePsiElement(..$params)" if params.length == 3 =>
          val optional = params.last match {
            case q"useOptionalProvider = $v" => c.eval[Boolean](c.Expr(v))
            case q"$v" => c.eval[Boolean](c.Expr(v))
          }
          (params.head, modCountParamToModTracker(c)(params(1), params.head), optional)
        case _ => abort("Wrong annotation parameters!")
      }
    }

    //annotation parameters
    val (elem, dependencyItem, useOptionalProvider) = parameters

    annottees.toList match {
      case DefDef(mods, name, tpParams, paramss, retTp, rhs) :: Nil =>
        if (retTp.isEmpty) {
          abort("You must specify return type")
        }

        //generated names
        val cachedFunName = generateTermName("cachedFun")
        val keyId = c.freshName(name.toString + "cacheKey")
        val key = generateTermName(name + "Key")
        val cacheStatsName = generateTermName("cacheStats")
        val dependencyItemName = generateTermName("dependencyItem")
        val defdefFQN = thisFunctionFQN(name.toString)

        val analyzeCaches = analyzeCachesEnabled(c)
        val provider =
          if (useOptionalProvider) TypeName("MyOptionalProvider")
          else TypeName("MyProvider")

        val actualCalculation = transformRhsToAnalyzeCaches(c)(cacheStatsName, retTp, rhs)

        val analyzeCachesEnterCacheArea =
          if (analyzeCaches) q"$cacheStatsName.aboutToEnterCachedArea()"
          else EmptyTree
        val updatedRhs = q"""
          def $cachedFunName(): $retTp = $actualCalculation
          ..$analyzeCachesEnterCacheArea
          $cachesUtilFQN.get($elem, $key, new $cachesUtilFQN.$provider[Any, $retTp]($elem, _ => $cachedFunName())($dependencyItemName))
          """
        val updatedDef = DefDef(mods, name, tpParams, paramss, retTp, updatedRhs)
        val res = q"""
          private val $key = $cachesUtilFQN.getOrCreateKey[$keyTypeFQN[$cachedValueTypeFQN[$retTp]]]($keyId)
          ${if (analyzeCaches) q"private val $cacheStatsName = $cacheStatisticsFQN($keyId, $defdefFQN)" else EmptyTree}
          private val $dependencyItemName = $dependencyItem

          ..$updatedDef
          """
        println(res)
        c.Expr(res)
      case _ => abort("You can only annotate one function!")
    }
  }

}
