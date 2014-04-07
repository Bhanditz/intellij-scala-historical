package org.jetbrains.plugins.scala
package lang
package psi
package api
package toplevel
package typedef

import statements._
import com.intellij.psi._
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.util.Iconable
import types.PhysicalSignature
import com.intellij.psi.impl.PsiClassImplUtil

/**
 * @author AlexanderPodkhalyuzin
 */

trait ScTypeDefinition extends ScTemplateDefinition with ScMember
    with NavigationItem with PsiClass with ScTypeParametersOwner with Iconable with ScDocCommentOwner with ScAnnotationsHolder {

  def isCase : Boolean = false

  def isObject : Boolean = false

  def isTopLevel = !parentsInFile.exists(_.isInstanceOf[ScTypeDefinition]) 
  
  def getPath: String = {
    val qualName = qualifiedName
    val index = qualName.lastIndexOf('.')
    if (index < 0) "" else qualName.substring(0, index)
  }

  def getQualifiedNameForDebugger: String

  /**
   * Qualified name stops on outer Class level.
   */
  def getTruncedQualifiedName: String
  
  def signaturesByName(name: String): Seq[PhysicalSignature]

  def isPackageObject = false

  override def accept(visitor: ScalaElementVisitor) {
    visitor.visitTypeDefintion(this)
  }

  def getObjectClassOrTraitToken: PsiElement

  def getSourceMirrorClass: PsiClass

  override def isEquivalentTo(another: PsiElement): Boolean = {
    PsiClassImplUtil.isClassEquivalentTo(this, another)
  }
}