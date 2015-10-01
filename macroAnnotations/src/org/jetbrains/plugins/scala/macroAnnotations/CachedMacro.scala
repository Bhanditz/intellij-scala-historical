package org.jetbrains.plugins.scala.macroAnnotations

import scala.annotation.tailrec
import scala.language.experimental.macros
import scala.reflect.macros.whitebox

/**
 * Author: Svyatoslav Ilinskiy
 * Date: 9/22/15.
 */
object CachedMacro {
  val debug: Boolean = false

  def cachedImpl(c: whitebox.Context)(annottees: c.Tree*): c.Expr[Any] = {
    import c.universe._

    def abort(message: String) = c.abort(c.enclosingPosition, message)

    def parameters: (Boolean, ModCount.Value, Tree) = {
      @tailrec
      def modCountParam(modCount: c.universe.Tree): ModCount.Value = modCount match {
        case q"modificationCount = $v" => modCountParam(v)
        case q"ModCount.$v" => ModCount.withName(v.toString)
        case q"$v" => ModCount.withName(v.toString)
      }

      c.prefix.tree match {
        case q"new Cached(..$params)" if params.length == 3 =>
          val synch: Boolean = params.head match {
            case q"synchronized = $v" => c.eval[Boolean](c.Expr(v))
            case q"$v" => c.eval[Boolean](c.Expr(v))
          }
          val modCount: ModCount.Value = modCountParam(params(1))
          val manager = params(2)
          (synch, modCount, manager)
        case _ => abort("Wrong parameters")
      }
    }


    //todo: caching two overloaded functions
    annottees.toList match {
      case d@DefDef(mods, name, tpParams, params, retTp, rhs) :: Nil =>
        if (retTp.toString() == "<type ?>") {
          abort("You must specify return type")
        }

        val (synchronized, modCount, manager) = parameters
        val flattennedParams = params.flatten
        val paramNames = flattennedParams.map(_.name)
        val cacheVarName = TermName(name.toString + "Cache_")
        val modCountVarName = TermName(name.toString + "ModCount_")
        val mapName = TermName(name.toString + "CacheMap")
        val hasParameters: Boolean = flattennedParams.nonEmpty

        val fields = if (hasParameters) {
          q"""
            private val $mapName = com.intellij.util.containers.ContainerUtil.
                newConcurrentMap[(..${flattennedParams.map(_.tpt)}), ($retTp, Long)]()
          """
        } else {
          q"""
            @volatile
            private var $cacheVarName: Option[$retTp] = None
            @volatile
            private var $modCountVarName: Long = 0L
          """
        }

        def getValuesFromMap: c.universe.Tree = q"""
            var ($cacheVarName, $modCountVarName) = Option($mapName.get(..$paramNames)) match {
              case Some((res, count)) => (Some(res), count)
              case _ => (None, 0L)
            }
          """
        def putValuesIntoMap: c.universe.Tree = q"$mapName.put((..$paramNames), ($cacheVarName.get, $modCountVarName))"

        val functionContents = q"""
            val modCount = $manager.getModificationTracker.${TermName(modCount.toString)}
            ..${if (hasParameters) getValuesFromMap else EmptyTree}
            if ($cacheVarName.isDefined && $modCountVarName == modCount) $cacheVarName.get
            else {
              $cacheVarName = Some(calc())
              $modCountVarName = modCount
              ..${if (hasParameters) putValuesIntoMap else EmptyTree}
              $cacheVarName.get
            }
          """
        val res = q"""
          ..$fields
          $mods def $name[..$tpParams](...$params): $retTp = {
            def calc(): $retTp = $rhs

            ..${ if (synchronized) q"synchronized { $functionContents }" else functionContents }
          }
          """
        println(res)
        c.Expr(res)
      case _ => abort("You can only annotate one function!")
    }
  }

  def cachedMappedWithRecursionGuardImpl(c: whitebox.Context)(annottees: c.Tree*): c.Expr[Any] = {
    import c.universe._
    def abort(s: String) = c.abort(c.enclosingPosition, s)

    annottees.toList match {
      case d@DefDef(mods, name, tpParams, params, retTp, rhs) :: Nil =>
        if (retTp.toString() == "<type ?>") {
          abort("You must specify return type")
        }

        val (annotationParameters: List[Tree], dom) = c.prefix.tree match {
          //case q"new CachedMappedWithRecursionGuard[$t](..$params)" if params.length == 4 => (params, t)
          case q"new CachedMappedWithRecursionGuard(..$params)" if params.length == 4 => (params, tq"com.intellij.psi.PsiElement")
          case _ => abort("Wrong annotation parameters!")
        }
        val flatParams = params.flatten
        val parameterTypes = flatParams.map(_.tpt)
        val parameterNames: List[c.universe.TermName] = flatParams.map(_.name)
        val cachesUtilFQN = q"org.jetbrains.plugins.scala.caches.CachesUtil"
        val key = annotationParameters(1)
        val parameterDefinitions: List[c.universe.Tree] = flatParams match {
          case List(param) => List(ValDef(NoMods, param.name, param.tpt, q"data"))
          case _ => flatParams.zipWithIndex.map {
            case (param, i) =>
              ValDef(NoMods, param.name, param.tpt, q"data.${TermName("_" + (i + 1))}")
          }
        }

        val builder = q"""
          def inner_(a: Any, data: Data): $retTp = {
            ..$parameterDefinitions
            $rhs
          }
          """
        val element = annotationParameters.head
        val defaultValue = annotationParameters(2)
        val dependencyItem = annotationParameters(3)

        val updatedRhs = q"""
          type Data = (..$parameterTypes)
          val data = (..$parameterNames)

          $builder

          $cachesUtilFQN.getMappedWithRecursionPreventingWithRollback[$dom, Data, $retTp]($element, data, $key, inner_, $defaultValue, $dependencyItem)
          """
        val res = c.Expr(DefDef(mods, name, tpParams, params, retTp, updatedRhs))
        println(res)
        res
      case _ => abort("You can only annotate one function!")
    }
  }

  def cachedWithRecursionGuardImpl(c: whitebox.Context)(annottees: c.Tree*): c.Expr[Any] = {
    import c.universe._
    def abort(s: String) = c.abort(c.enclosingPosition, s)

    annottees.toList match {
      case d@DefDef(mods, name, tpParams, params, retTp, rhs) :: Nil =>
        if (retTp.toString() == "<type ?>") {
          abort("You must specify return type")
        }

        val cachesUtilFQN = q"org.jetbrains.plugins.scala.caches.CachesUtil"

        val (annotationParameters: List[Tree], providerType, useOptionalProvider: Boolean) = c.prefix.tree match {
          case q"new CachedWithRecursionGuard[$t](..$params)" if params.length == 4 => (params, t, false) //default parameter
          case q"new CachedWithRecursionGuard[$t](..$params)" if params.length == 5 =>
            val optional = params.last match {
              case q"useOptionalProvider = $v" => c.eval[Boolean](c.Expr(v))
              case q"$v" => c.eval[Boolean](c.Expr(v))
            }
            (params, t, optional)
          case _ => abort("Wrong annotation parameters!")
        }
        val element = annotationParameters.head
        val key = annotationParameters(1)
        val defaultValue = annotationParameters(2)
        val dependencyItem = annotationParameters(3)
        val provider =
          if (useOptionalProvider) TypeName("MyOptionalProvider")
          else TypeName("MyProvider")
        val fun = q"def calc_(): $retTp = $rhs"
        val builder = q"new $cachesUtilFQN.$provider[$providerType, $retTp]($element, _ => calc_())($dependencyItem)"
        val updatedRhs = q"""
          $fun
          $cachesUtilFQN.getWithRecursionPreventingWithRollback($element, $key, $builder, $defaultValue)
          """
        val res = DefDef(mods, name, tpParams, params, retTp, updatedRhs)
        println(res)
        c.Expr(res)
      case _ => abort("You can only annotate one function!")
    }
  }

  def cachedInsidePsiElementImpl(c: whitebox.Context)(annottees: c.Tree*): c.Expr[Any] = {
    import c.universe._
    def abort(s: String) = c.abort(c.enclosingPosition, s)

    annottees.toList match {
      case d@DefDef(mods, name, tpParams, params, retTp, rhs) :: Nil =>
        if (retTp.toString() == "<type ?>") {
          abort("You must specify return type")
        }
        val (annotationParameters: List[Tree], useOptionalProvider: Boolean) = c.prefix.tree match {
          case q"new CachedInsidePsiElement(..$params)" if params.length == 3 => (params, false)
          case q"new CachedInsidePsiElement(..$params)" if params.length == 4 =>
            val optional = params.last match {
              case q"useOptionalProvider = $v" => c.eval[Boolean](c.Expr(v))
              case q"$v" => c.eval[Boolean](c.Expr(v))
            }
            (params, optional)
          case _ => abort("Wrong annotation parameters!")
        }
        val cachesUtilFQN = q"org.jetbrains.plugins.scala.caches.CachesUtil"
        val elem = annotationParameters.head
        val key = annotationParameters(1)
        val dependencyItem = annotationParameters(2)
        val provider =
          if (useOptionalProvider) TypeName("MyOptionalProvider")
          else TypeName("MyProvider")
        val updatedRhs = q"""
          def calc_(): $retTp = $rhs

          $cachesUtilFQN.get($elem, $key, new $cachesUtilFQN.$provider[Any, $retTp]($elem, _ => calc_())($dependencyItem))
          """
        val res = DefDef(mods, name, tpParams, params, retTp, updatedRhs)
        println(res)
        c.Expr(res)
      case _ => abort("You can only annotate one function!")
    }
  }

  def println(a: Any): Unit = {
    if (debug) {
      Console.println(a)
    }
  }
}

object ModCount extends Enumeration {
  type ModCount = Value
  val getModificationCount = Value("getModificationCount")
  val getOutOfCodeBlockModificationCount = Value("getOutOfCodeBlockModificationCount")
  val getJavaStructureModificationCount = Value("getJavaStructureModificationCount")
}
