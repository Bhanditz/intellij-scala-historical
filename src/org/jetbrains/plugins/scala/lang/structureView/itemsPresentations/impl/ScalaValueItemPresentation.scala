package org.jetbrains.plugins.scala
package lang
package structureView
package itemsPresentations
package impl

import com.intellij.openapi.editor.colors.{TextAttributesKey, CodeInsightColors}
import com.intellij.psi._
import org.jetbrains.plugins.scala.icons.Icons

import javax.swing._;

/**
* @author Alexander Podkhalyuzin
* Date: 08.05.2008
*/

class ScalaValueItemPresentation(private val element: PsiElement, isInherited: Boolean) extends ScalaItemPresentation(element) {
  def getPresentableText: String = {
    ScalaElementPresentation.getPresentableText(myElement)
  }

  override def getIcon(open: Boolean): Icon = {
    Icons.VAL
  }

  override def getTextAttributesKey: TextAttributesKey = {
    if(isInherited) CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES else null
  }
}
