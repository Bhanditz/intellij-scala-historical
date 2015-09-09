package org.jetbrains.plugins.scala.debugger.exactBreakpoints

import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.SourcePositionHighlighter
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.DocumentUtil
import com.intellij.xdebugger.XDebuggerUtil
import org.jetbrains.plugins.scala.debugger.{ScalaDebuggerTestCase, ScalaPositionManager, ScalaVersion_2_11, ScalaVersion_2_12_M2}
import org.jetbrains.plugins.scala.extensions.inReadAction
import org.junit.Assert

import scala.collection.JavaConverters._

/**
 * @author Nikolay.Tropin
 */

class ExactBreakpointTest extends ExactBreakpointTestBase with ScalaVersion_2_11
class ExactBreakpointTest_2_12_M2 extends ExactBreakpointTestBase with ScalaVersion_2_12_M2 {
  //todo SCL-9129 should be fixed
  override def testEarlyDefAndTemplateBody(): Unit = {}
  override def testSeveralLines(): Unit = {}
}

abstract class ExactBreakpointTestBase extends ScalaDebuggerTestCase {
  protected val mainClass = "Sample"
  protected val fileName = s"$mainClass.scala"

  case class Breakpoint(line: Int, ordinal: Integer) {
    override def toString: String = s"line = $line, ordinal=$ordinal"
  }

  private def addBreakpoint(b: Breakpoint): Unit = addBreakpoint(fileName, b.line, b.ordinal)

  protected def checkVariants(lineNumber: Int, variants: String*) = {
    val xSourcePosition = XDebuggerUtil.getInstance().createPosition(getVirtualFile(getFileInSrc(fileName)), lineNumber)
    val foundVariants = scalaLineBreakpointType.computeVariants(getProject, xSourcePosition).asScala.map(_.getText)
    Assert.assertEquals("Wrong set of variants found: ", variants, foundVariants)
  }

  protected def checkStoppedAtBreakpointAt(breakpoints: Breakpoint*)(sourcePositionText: String) = {
    checkStopResumeSeveralTimes(breakpoints: _*)(sourcePositionText)
  }

  protected def checkStopResumeSeveralTimes(breakpoints: Breakpoint*)(sourcePositions: String*) = {
    def message(expected: String, actual: String) = {
      s"Wrong source position. Expected: $expected, actual: $actual"
    }
    breakpoints.foreach(addBreakpoint)
    runDebugger(mainClass) {
      for (expected <- sourcePositions) {
        waitForBreakpoint()
        managed {
          val location = suspendContext.getFrameProxy.getStackFrame.location
          inReadAction {
            val sourcePosition = new ScalaPositionManager(getDebugProcess).getSourcePosition(location)
            val text: String = highlightedText(sourcePosition)
            Assert.assertTrue(message(expected, text), text.startsWith(expected.stripSuffix("...")))
          }
        }
        resume()
      }
    }
  }

  private def highlightedText(sourcePosition: SourcePosition): String = {
    val elemRange = SourcePositionHighlighter.getHighlightRangeFor(sourcePosition)
    val document = PsiDocumentManager.getInstance(getProject).getDocument(sourcePosition.getFile)
    val lineRange = DocumentUtil.getLineTextRange(document, sourcePosition.getLine)
    val textRange = if (elemRange != null) elemRange.intersection(lineRange) else lineRange
    document.getText(textRange).trim
  }

  protected def checkNotStoppedAtBreakpointAt(breakpoint: Breakpoint) = {
    addBreakpoint(breakpoint)
    runDebugger(mainClass) {
      Assert.assertTrue(s"Stopped at breakpoint: $breakpoint", processTerminatedNoBreakpoints())
    }
  }

  protected def addFileToProject(fileText: String): Unit = {
    Assert.assertTrue(s"File should start with `object $mainClass`", fileText.startsWith(s"object $mainClass"))
    addFileToProject(fileName, fileText.stripMargin.trim)
  }


  def testOneLine(): Unit = {
    addFileToProject(
      """object Sample {
        |  def main(args: Array[String]) {
        |    Seq(1).map(x => x + 1).filter(_ > 10).foreach(println)
        |  }
        |}"""
    )

    checkVariants(lineNumber = 2, "All", "line in function main", "x => x + 1", "_ > 10", "println")

    checkStopResumeSeveralTimes(Breakpoint(2, null))("Seq(1).map(...", "x => x + 1", "_ > 10")
    checkStoppedAtBreakpointAt(Breakpoint(2, -1))("Seq(1).map(...")
    checkStoppedAtBreakpointAt(Breakpoint(2, 0))("x => x + 1")
    checkStoppedAtBreakpointAt(Breakpoint(2, 1))("_ > 10")
    checkNotStoppedAtBreakpointAt(Breakpoint(2, 2))
  }

  def testEither(): Unit = {
    addFileToProject(
      """object Sample {
        |  def main(args: Array[String]) {
        |    val x: Either[String, Int] = Right(1)
        |    val y: Either[String, Int] = Left("aaa")
        |
        |    x.fold(_.substring(1), _ + 1)
        |    y.fold(_.substring(2), _ + 2)
        |  }
        |}
        |
      """
    )
    checkVariants(lineNumber = 5, "All", "line in function main", "_.substring(1)", "_ + 1")
    checkStopResumeSeveralTimes(Breakpoint(5, null), Breakpoint(6, null))("x.fold(...", "_ + 1", "y.fold(...", "_.substring(2)")
    checkStoppedAtBreakpointAt(Breakpoint(5, 1))("_ + 1")
    checkStoppedAtBreakpointAt(Breakpoint(6, 0))("_.substring(2)")
    checkNotStoppedAtBreakpointAt(Breakpoint(5, 0))
    checkNotStoppedAtBreakpointAt(Breakpoint(6, 1))
  }

  def testSeveralLines(): Unit = {
    addFileToProject(
      """object Sample {
        |  def main(args: Array[String]) {
        |    Option("aaa").flatMap(_.headOption)
        |      .find(c => c.isDigit).getOrElse('0')
        |  }
        |}"""
    )
    checkVariants(2, "All", "line in function main", "_.headOption")
    checkVariants(3, "All", "line in function main", "c => c.isDigit", "'0'")

    checkStopResumeSeveralTimes(Breakpoint(2, null), Breakpoint(3, null))("Option(\"aaa\")...", "_.headOption", ".find(...", "c => c.isDigit", "'0'")
    checkStopResumeSeveralTimes(Breakpoint(2, -1), Breakpoint(3, -1))("Option(...", ".find(...")
    checkStopResumeSeveralTimes(Breakpoint(2, 0), Breakpoint(3, 0))("_.headOption", "c => c.isDigit")
  }

  def testNestedLambdas(): Unit = {
    addFileToProject(
      """object Sample {
        |  def main(args: Array[String]) {
        |    Seq("a").flatMap(x => x.find(_ == 'a').getOrElse('a').toString).foreach(c => println(Some(c).filter(_ == 'a').getOrElse('b')))
        |  }
        |}
      """
    )
    checkVariants(2,
      "All",
      "line in function main",
      "x => x.find(_ == 'a').getOrElse('a').toString",
      "_ == 'a'",
      "'a'",
      "c => println(Some(c).filter(_ == 'a').getOrElse('b'))",
      "_ == 'a'",
      "'b'")

    checkStopResumeSeveralTimes(Breakpoint(2, null))("Seq(\"a\").flatMap(...", "x => x.find(...", "_ == 'a'", "c => println...", "_ == 'a'")
    checkNotStoppedAtBreakpointAt(Breakpoint(2, 2))
    checkNotStoppedAtBreakpointAt(Breakpoint(2, 5))
  }

  def testNestedLambdas2(): Unit = {
    addFileToProject(
      """object Sample {
        |  def main(args: Array[String]) {
        |    Seq("b").flatMap(x => x.find(_ == 'a').getOrElse('a').toString).foreach(c => println(Some(c).filter(_ == 'b').getOrElse('a')))
        |  }
        |}
      """
    )
    checkVariants(2,
      "All",
      "line in function main",
      "x => x.find(_ == 'a').getOrElse('a').toString",
      "_ == 'a'",
      "'a'",
      "c => println(Some(c).filter(_ == 'b').getOrElse('a'))",
      "_ == 'b'",
      "'a'")

    checkStopResumeSeveralTimes(Breakpoint(2, null))("Seq(\"b\").flatMap(...", "x => x.find(...", "_ == 'a'", "'a'", "c => println...", "_ == 'b'", "'a'")
    checkStoppedAtBreakpointAt(Breakpoint(2, 1))("_ == 'a'")
    checkStoppedAtBreakpointAt(Breakpoint(2, 2))("'a'")
    checkStoppedAtBreakpointAt(Breakpoint(2, 4))("_ == 'b'")
    checkStoppedAtBreakpointAt(Breakpoint(2, 5))("'a'")
  }

  def testConstructorAndClassParam(): Unit = {
    addFileToProject(
      """object Sample {
        |  def main(args: Array[String]) {
        |    new BBB()
        |  }
        |}
        |
        |class BBB extends AAA("a3".filter(_.isDigit)) {
        |  Seq(1).map(x => x + 1).filter(_ > 10)
        |}
        |
        |class AAA(s: String)
      """
    )

    checkVariants(6, "All", "constructor of BBB", "_.isDigit")
    checkStopResumeSeveralTimes(Breakpoint(6, null), Breakpoint(10, null))("class BBB ...", "_.isDigit", "_.isDigit", "class AAA(...")
  }

  def testEarlyDefAndTemplateBody(): Unit = {
    addFileToProject(
      """object Sample {
        |  def main(args: Array[String]) {
        |    new BBB()
        |  }
        |}
        |
        |class BBB extends {
        |  val x = None.getOrElse(Seq(1)).filter(_ > 0)
        |} with AAA("") {
        |  Seq(1).map(x => x + 1).filter(_ > 10)
        |}
        |
        |class AAA(s: String)
      """
    )
    checkVariants(7, "All", "early definitions of BBB", "Seq(1)", "_ > 0")
    checkVariants(9, "All", "line in containing block", "x => x + 1", "_ > 10")

    checkStopResumeSeveralTimes(Breakpoint(7, null), Breakpoint(9, null))("val x = ...", "Seq(1)", "_ > 0", "Seq(1).map...", "x => x + 1", "_ > 10")
  }

  def testNewTemplateDefinitionAsLambda(): Unit = {
    addFileToProject(
      """object Sample {
        |  def main(args: Array[String]) {
        |    Seq("a").map(new AAA(_)).filter(_ => false).headOption.getOrElse(new AAA("1"))
        |  }
        |}
        |
        |class AAA(s: String)
      """
    )
    checkVariants(2, "All", "line in function main", "new AAA(_)", "_ => false", "new AAA(\"1\")")
    checkStopResumeSeveralTimes(Breakpoint(2, null))("Seq(\"a\")...", "new AAA(_)", "_ => false", "new AAA(\"1\")")
  }
}
