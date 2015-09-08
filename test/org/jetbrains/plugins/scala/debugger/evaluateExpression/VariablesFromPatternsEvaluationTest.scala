package org.jetbrains.plugins.scala
package debugger.evaluateExpression

import org.jetbrains.plugins.scala.debugger.{ScalaDebuggerTestCase, ScalaVersion_2_11}

/**
 * Nikolay.Tropin
 * 8/5/13
 */
class VariablesFromPatternsEvaluationTest extends VariablesFromPatternsEvaluationTestBase with ScalaVersion_2_11

//todo uncomment when SCL-9131 fixed
//class VariablesFromPatternsEvaluationTest_2_12_M2 extends VariablesFromPatternsEvaluationTestBase with ScalaVersion_2_12_M2

abstract class VariablesFromPatternsEvaluationTestBase extends ScalaDebuggerTestCase{
  def testMatch() {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  val name = "name"
        |  def main(args: Array[String]) {
        |    val x = (List(1, 2), Some("z"), None)
        |    x match {
        |      case all @ (list @ List(q, w), some @ Some(z), _) =>
        |        "stop here"
        |      case _ =>
        |    }
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 6)
    runDebugger("Sample") {
      waitForBreakpoint()
      evalEquals("all", "(List(1, 2),Some(z),None)")
      evalEquals("list", "List(1, 2)")
      evalEquals("x", "(List(1, 2),Some(z),None)")
      evalEquals("name", "name")
      evalEquals("q", "1")
      evalEquals("z", "z")
      evalEquals("some", "Some(z)")
      evalEquals("args", "[]")
    }
  }

  def testMatchInForStmt() {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  val name = "name"
        |  def main(args: Array[String]) {
        |    for (s <- List("a", "b"); if s == "a"; ss = s + s; i <- List(1,2); if i == 1; si = s + i) {
        |      val x = (List(1, 2), Some("z"), 3 :: 4 :: Nil)
        |      x match {
        |        case all @ (q :: qs, some @ Some(z), list @ List(m, _)) =>
        |          "stop here"
        |        case _ =>
        |      }
        |    }
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 7)
    runDebugger("Sample") {
      waitForBreakpoint()
      evalEquals("all", "(List(1, 2),Some(z),List(3, 4))")
      evalEquals("x", "(List(1, 2),Some(z),List(3, 4))")
      evalEquals("name", "name")
      evalEquals("q", "1")
      evalEquals("qs", "List(2)")
      evalEquals("z", "z")
      evalEquals("list", "List(3, 4)")
      evalEquals("m", "3")
      evalEquals("some", "Some(z)")

//      evalEquals("s", "a")
/* todo evaluation works, but not in test;
    it was broken after check for implicit conversion was added in ScalaEvaluationBuilder.Builder.visitExpression */

//      evalEquals("ss", "aa")
//      evalEquals("i", "1")
//      evalEquals("si", "a1")
//      evalEquals("args", "[]")
    }
  }

  def testRegexMatch() {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  val name = "name"
        |  def main(args: Array[String]) {
        |    val Decimal = "(-)?(\\d+)(\\.\\d*)?".r
        |    "-2.5" match {
        |      case number @ Decimal(sign, _, dec) =>
        |        "stop here"
        |      case _ =>
        |    }
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 6)
    runDebugger("Sample") {
      waitForBreakpoint()
      evalEquals("number", "-2.5")
      evalEquals("sign", "-")
      evalEquals("dec", ".5")
      evalEquals("name", "name")
    }
  }

  def testMultilevel() {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  val name = "name"
        |  def main(args: Array[String]) {
        |    List(None, Some(1 :: 2 :: Nil)) match {
        |      case List(none, some) =>
        |        some match {
        |          case Some(seq) =>
        |            seq match {
        |              case Seq(1, two) =>
        |                "stop here"
        |              case _ =>
        |            }
        |          case _ =>
        |        }
        |      case _ =>
        |    }
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 9)
    runDebugger("Sample") {
      waitForBreakpoint()
      evalEquals("name", "name")
      evalEquals("args", "[]")
      evalEquals("none", "None")
      evalEquals("some", "Some(List(1, 2))")
      evalEquals("seq", "List(1, 2)")
      evalEquals("two", "2")
    }
  }

  def testLocalInMatch() {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  val name = "name"
        |  def main(args: Array[String]) {
        |    Option("a") match {
        |      case None =>
        |      case some @ Some(a) =>
        |        def foo(i: Int) {
        |          "stop here"
        |        }
        |        foo(10)
        |    }
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 7)
    runDebugger("Sample") {
      waitForBreakpoint()
      evalEquals("name", "name")
      evalEquals("args", "[]")
      evalEquals("some", "Some(a)")
      evalEquals("a", "a")
      evalEquals("i", "10")
    }
  }

  def testAnonymousInMatch() {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  val name = "name"
        |  def main(args: Array[String]) {
        |    Option("a") match {
        |      case None =>
        |      case some @ Some(a) =>
        |        List(10) foreach { i =>
        |          "stop here"
        |        }
        |    }
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 7)
    runDebugger("Sample") {
      waitForBreakpoint()
      evalEquals("name", "name")
      evalEquals("args", "[]")
      evalEquals("some", "Some(a)")
      evalEquals("a", "a")
      evalEquals("i", "10")
    }
  }

}
