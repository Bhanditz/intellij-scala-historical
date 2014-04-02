package org.jetbrains.plugins.scala
package lang
package psi
package types

import api.statements._
import result.TypingContext
import com.intellij.psi.PsiClass
import extensions.toPsiClassExt
import lang.psi
import collection.mutable

/**
 * Substitutor should be meaningful only for decls and typeDecls. Components shouldn't be applied by substitutor.
 */
case class ScCompoundType(components: Seq[ScType], signatureMap: Map[Signature, ScType],
                          typesMap: Map[String, (ScType, ScType, ScTypeAlias)]) extends ValueType {
  def visitType(visitor: ScalaTypeVisitor) {
    visitor.visitCompoundType(this)
  }

  override def removeAbstracts = ScCompoundType(components.map(_.removeAbstracts),
    signatureMap.map {
      case (s: Signature, tp: ScType) =>
        (new Signature(s.name, s.typesEval.map(_.map(_.removeAbstracts)), s.paramLength, s.typeParams,
          s.substitutor, s.namedElement, s.hasRepeatedParam), tp.removeAbstracts)
    }, typesMap.map {
      case (s: String, (lower, upper, ta)) => (s, (lower.removeAbstracts, upper.removeAbstracts, ta))
    })

  import collection.immutable.{HashSet => IHashSet}

  override def recursiveUpdate(update: ScType => (Boolean, ScType), visited: IHashSet[ScType]): ScType = {
    if (visited.contains(this)) {
      return update(this) match {
        case (true, res) => res
        case _ => this
      }
    }
    update(this) match {
      case (true, res) => res
      case _ =>
        new ScCompoundType(components.map(_.recursiveUpdate(update, visited + this)), signatureMap.map {
          case (signature: Signature, tp) => (signature, tp.recursiveUpdate(update, visited + this))
        }, typesMap.map {
          case (s, (tp1, tp2, ta)) => (s, (tp1.recursiveUpdate(update, visited + this), tp2.recursiveUpdate(update, visited + this), ta))
        })
    }
  }

  override def recursiveVarianceUpdateModifiable[T](data: T, update: (ScType, Int, T) => (Boolean, ScType, T),
                                                    variance: Int = 1): ScType = {
    update(this, variance, data) match {
      case (true, res, _) => res
      case (_, _, newData) =>
        new ScCompoundType(components.map(_.recursiveVarianceUpdateModifiable(newData, update, variance)), signatureMap.map {
          case (signature: Signature, tp) => (signature, tp.recursiveVarianceUpdateModifiable(newData, update, 1))
        }, typesMap.map {
          case (s, (tp1, tp2, ta)) => (s, (tp1.recursiveVarianceUpdateModifiable(newData, update, 1), tp2.recursiveVarianceUpdateModifiable(newData, update, 1), ta))
        })
    }
  }

  override def equivInner(r: ScType, uSubst: ScUndefinedSubstitutor, falseUndef: Boolean): (Boolean, ScUndefinedSubstitutor) = {
    var undefinedSubst = uSubst
    r match {
      case r: ScCompoundType =>
        if (r == this) return (true, undefinedSubst)
        if (components.length != r.components.length) return (false, undefinedSubst)
        val list = components.zip(r.components)
        val iterator = list.iterator
        while (iterator.hasNext) {
          val (w1, w2) = iterator.next()
          val t = Equivalence.equivInner(w1, w2, undefinedSubst, falseUndef)
          if (!t._1) return (false, undefinedSubst)
          undefinedSubst = t._2
        }

        if (signatureMap.size != r.signatureMap.size) return (false, undefinedSubst)

        val iterator2 = signatureMap.iterator
        while (iterator2.hasNext) {
          val (sig, t) = iterator2.next()
          r.signatureMap.get(sig) match {
            case None => false
            case Some(t1) =>
              val f = Equivalence.equivInner(t, t1, undefinedSubst, falseUndef)
              if (!f._1) return (false, undefinedSubst)
              undefinedSubst = f._2
          }
        }

        val types1 = typesMap
        val types2 = r.typesMap
        if (types1.size != types2.size) (false, undefinedSubst)
        else {
          val types1iterator = types1.iterator
          while (types1iterator.hasNext) {
            val (name, bounds1) = types1iterator.next()
            types2.get(name) match {
              case None => return (false, undefinedSubst)
              case Some (bounds2) =>
                var t = Equivalence.equivInner(bounds1._1, bounds2._1, undefinedSubst, falseUndef)
                if (!t._1) return (false, undefinedSubst)
                undefinedSubst = t._2
                t = Equivalence.equivInner(bounds1._2, bounds2._2, undefinedSubst, falseUndef)
                if (!t._1) return (false, undefinedSubst)
                undefinedSubst = t._2
            }
          }
          (true, undefinedSubst)
        }
      case _ =>
        if (signatureMap.size == 0 && typesMap.size == 0) {
          val filtered = components.filter {
            case psi.types.Any => false
            case psi.types.AnyRef =>
              if (!r.conforms(psi.types.AnyRef)) return (false, undefinedSubst)
              false
            case ScDesignatorType(obj: PsiClass) if obj.qualifiedName == "java.lang.Object" =>
              if (!r.conforms(psi.types.AnyRef)) return (false, undefinedSubst)
              false
            case _ => true
          }
          if (filtered.length == 1) Equivalence.equivInner(filtered(0), r, undefinedSubst, falseUndef)
          else (false, undefinedSubst)
        } else (false, undefinedSubst)

    }
  }
}

object ScCompoundType {
  def fromPsi(components: Seq[ScType], decls: Seq[ScDeclaredElementsHolder],
              typeDecls: Seq[ScTypeAlias], subst: ScSubstitutor): ScCompoundType = {
    val signatureMapVal: mutable.HashMap[Signature, ScType] = new mutable.HashMap[Signature, ScType] {
      override def elemHashCode(s : Signature) = s.name.hashCode * 31 + {
        val length = s.paramLength
        if (length.sum == 0) List(0).hashCode()
        else length.hashCode()
      }
    }
    val typesVal = new mutable.HashMap[String, (ScType, ScType, ScTypeAlias)]

    for (typeDecl <- typeDecls) {
      typesVal += ((typeDecl.name, (typeDecl.lowerBound.getOrNothing, typeDecl.upperBound.getOrAny, typeDecl)))
    }


    for (decl <- decls) {
      decl match {
        case fun: ScFunction =>
          signatureMapVal += ((new Signature(fun.name, PhysicalSignature.typesEval(fun), PhysicalSignature.paramLength(fun),
            fun.getTypeParameters, subst, Some(fun), PhysicalSignature.hasRepeatedParam(fun)), fun.returnType.getOrAny))
        case varDecl: ScVariable =>
          varDecl.typeElement match {
            case Some(te) => for (e <- varDecl.declaredElements) {
              val varType = te.getType(TypingContext.empty(varDecl.declaredElements))
              signatureMapVal += ((new Signature(e.name, Stream.empty, 0, subst, Some(e)), varType.getOrAny))
              signatureMapVal += ((new Signature(e.name + "_=", Stream(varType.getOrAny), 1, subst, Some(e)), psi.types.Unit)) //setter
            }
            case None =>
          }
        case valDecl: ScValue => valDecl.typeElement match {
          case Some(te) => for (e <- valDecl.declaredElements) {
            val valType = te.getType(TypingContext.empty(valDecl.declaredElements))
            signatureMapVal += ((new Signature(e.name, Stream.empty, 0, subst, Some(e)), valType.getOrAny))
          }
          case None =>
        }
      }
    }

    ScCompoundType(components, signatureMapVal.toMap, typesVal.toMap)
  }
}