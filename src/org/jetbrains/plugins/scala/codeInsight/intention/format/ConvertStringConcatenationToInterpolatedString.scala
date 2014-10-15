package org.jetbrains.plugins.scala
package codeInsight.intention.format

import org.jetbrains.plugins.scala.format.{InterpolatedStringFormatter, StringConcatenationParser}
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel.Scala_2_10
import project._

/**
 * Pavel Fatin
 */

class ConvertStringConcatenationToInterpolatedString extends AbstractFormatConversionIntention(
  "Convert to interpolated string", StringConcatenationParser, InterpolatedStringFormatter, eager = true) {

  override def isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean = {
    if (!super.isAvailable(project: Project, editor: Editor, element: PsiElement)) return false
    element.languageLevel >= Scala_2_10
  }
}
