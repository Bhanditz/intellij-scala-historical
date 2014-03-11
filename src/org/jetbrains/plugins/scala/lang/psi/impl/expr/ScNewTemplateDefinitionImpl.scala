package org.jetbrains.plugins.scala
package lang
package psi
package impl
package expr

import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import toplevel.PsiClassFake
import com.intellij.lang.ASTNode
import com.intellij.psi._
import api.expr._
import api.toplevel.templates.ScTemplateBody
import api.statements.{ScTypeAlias, ScDeclaredElementsHolder}
import collection.mutable.ArrayBuffer
import types.result.{Success, TypingContext}
import api.toplevel.typedef.ScTemplateDefinition
import psi.stubs.ScTemplateDefinitionStub
import icons.Icons
import types._
import api.ScalaElementVisitor
import extensions.toPsiClassExt

/**
* @author Alexander Podkhalyuzin
* Date: 06.03.2008
*/

class ScNewTemplateDefinitionImpl private () extends ScalaStubBasedElementImpl[ScTemplateDefinition] with ScNewTemplateDefinition with PsiClassFake {
  def this(node: ASTNode) = {this(); setNode(node)}
  def this(stub: ScTemplateDefinitionStub) = {this(); setStub(stub); setNode(null)}

  override def toString: String = "NewTemplateDefinition"

  override def getIcon(flags: Int) = Icons.CLASS

  protected override def innerType(ctx: TypingContext) = {
    val (holders, aliases) : (Seq[ScDeclaredElementsHolder], Seq[ScTypeAlias]) = extendsBlock.templateBody match {
      case Some(b: ScTemplateBody) => (b.holders.toSeq, b.aliases.toSeq)
      case None => (Seq.empty, Seq.empty)
    }

    val superTypes = extendsBlock.superTypes.filter {
      case ScDesignatorType(clazz: PsiClass) => clazz.qualifiedName != "scala.ScalaObject"
      case _                                 => true
    }
    if (superTypes.length > 1 || !holders.isEmpty || !aliases.isEmpty) {
      new Success(ScCompoundType(superTypes, holders.toList, aliases.toList, ScSubstitutor.empty), Some(this))
    } else {
      extendsBlock.templateParents match {
        case Some(tp) if tp.typeElements.length == 1 =>
          tp.typeElements(0).getNonValueType(ctx)
        case _ =>
          superTypes.headOption match {
            case s@Some(t) => Success(t, Some(this))
            case None => Success(AnyRef, Some(this)) //this is new {} case
          }
      }
    }
  }

 override def processDeclarationsForTemplateBody(processor: PsiScopeProcessor, state: ResolveState,
                                          lastParent: PsiElement, place: PsiElement): Boolean =
  extendsBlock.templateBody match {
    case Some(body) if PsiTreeUtil.isContextAncestor(body, place, false) =>
      super[ScNewTemplateDefinition].processDeclarationsForTemplateBody(processor, state, lastParent, place)
    case _ => true
  }
  def nameId: PsiElement = null
  override def setName(name: String): PsiElement = throw new IncorrectOperationException("cannot set name")
  override def name: String = "<anonymous>"

  override def getName: String = name

  override def getSupers: Array[PsiClass] = {
    val direct = extendsBlock.supers.filter {
      case clazz: PsiClass => clazz.qualifiedName != "scala.ScalaObject"
      case _               => true
    }.toArray
    val res = new ArrayBuffer[PsiClass]
    res ++= direct
    for (sup <- direct if !res.contains(sup)) res ++= sup.getSupers
    // return strict superclasses
    res.filter(_ != this).toArray
  }

  override def processDeclarations(processor: PsiScopeProcessor, state: ResolveState, lastParent: PsiElement,
                                   place: PsiElement): Boolean = {
    super[ScNewTemplateDefinition].processDeclarations(processor, state, lastParent, place)
  }

  override def getExtendsListTypes: Array[PsiClassType] = innerExtendsListTypes

  override def getImplementsListTypes: Array[PsiClassType] = innerExtendsListTypes

  def getTypeWithProjections(ctx: TypingContext, thisProjections: Boolean = false) = getType(ctx) //no projections for new template definition

  override def isInheritor(baseClass: PsiClass, deep: Boolean): Boolean =
    super[ScNewTemplateDefinition].isInheritor(baseClass, deep)

  override def accept(visitor: ScalaElementVisitor) {
    visitor.visitNewTemplateDefinition(this)
  }

  override def accept(visitor: PsiElementVisitor) {
    visitor match {
      case visitor: ScalaElementVisitor => visitor.visitNewTemplateDefinition(this)
      case _ => super.accept(visitor)
    }
  }


  override def findMethodBySignature(patternMethod: PsiMethod, checkBases: Boolean): PsiMethod = {
    super[ScNewTemplateDefinition].findMethodBySignature(patternMethod, checkBases)
  }

  override def findMethodsBySignature(patternMethod: PsiMethod, checkBases: Boolean): Array[PsiMethod] = {
    super[ScNewTemplateDefinition].findMethodsBySignature(patternMethod, checkBases)
  }

  import com.intellij.openapi.util.{Pair => IPair}
  import java.util.{List => JList}
  import java.util.{Collection => JCollection}

  override def findMethodsByName(name: String, checkBases: Boolean): Array[PsiMethod] = {
    super[ScNewTemplateDefinition].findMethodsByName(name, checkBases)
  }

  override def findFieldByName(name: String, checkBases: Boolean): PsiField = {
    super[ScNewTemplateDefinition].findFieldByName(name, checkBases)
  }

  override def findInnerClassByName(name: String, checkBases: Boolean): PsiClass = {
    super[ScNewTemplateDefinition].findInnerClassByName(name, checkBases)
  }

  override def getAllFields: Array[PsiField] = {
    super[ScNewTemplateDefinition].getAllFields
  }

  override def findMethodsAndTheirSubstitutorsByName(name: String,
                                                     checkBases: Boolean): JList[IPair[PsiMethod, PsiSubstitutor]] = {
    super[ScNewTemplateDefinition].findMethodsAndTheirSubstitutorsByName(name, checkBases)
  }

  override def getAllMethodsAndTheirSubstitutors: JList[IPair[PsiMethod, PsiSubstitutor]] = {
    super[ScNewTemplateDefinition].getAllMethodsAndTheirSubstitutors
  }

  override def getVisibleSignatures: JCollection[HierarchicalMethodSignature] = {
    super[ScNewTemplateDefinition].getVisibleSignatures
  }
}