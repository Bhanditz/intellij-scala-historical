package org.jetbrains.plugins.scala
package debugger.evaluateExpression

import org.jetbrains.plugins.scala.debugger.{ScalaDebuggerTestCase, ScalaVersion_2_11, ScalaVersion_2_12_M2}

/**
 * Nikolay.Tropin
 * 8/2/13
 */

class InAnonFunEvaluationTest extends InAnonFunEvaluationTestBase with ScalaVersion_2_11

class InAnonFunEvaluationTest_2_12_M2 extends InAnonFunEvaluationTestBase with ScalaVersion_2_12_M2 {
  //todo SCL-9139
  override def testPartialFunction(): Unit = {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  val name = "name"
        |  def main(args: Array[String]) {
        |    def printName(param: String, notUsed: String) {
        |      List(("a", 10)).foreach {
        |        case (a, i: Int) =>
        |            val x = "x"
        |            println(a + param)
        |            "stop here"
        |      }
        |    }
        |    printName("param", "notUsed")
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 7)
    runDebugger("Sample") {
      waitForBreakpoint()
      evalEquals("a", "a")
      evalEquals("x", "x")
      evalEquals("param", "param")
      evalEquals("name", "name")
      evalEquals("notUsed", "notUsed")
      evalEquals("args", "[]")
    }
  }



}

abstract class InAnonFunEvaluationTestBase extends ScalaDebuggerTestCase{

  def testFunctionValue() {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  def main(args: Array[String]) {
        |    val a = "a"
        |    var b = "b"
        |    val f: (Int) => Unit = n => {
        |      val x = "x"
        |      "stop here"
        |    }
        |    f(10)
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 6)
    runDebugger("Sample") {
      waitForBreakpoint()
      evalEquals("a", "a")
      evalEquals("b", "b")
      evalEquals("x", "x")
      evalEquals("n", "10")
      evalEquals("args", "[]")
    }
  }

  def testPartialFunction() {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  val name = "name"
        |  def main(args: Array[String]) {
        |    def printName(param: String, notUsed: String) {
        |      List(("a", 10)).foreach {
        |        case (a, i: Int) =>
        |            val x = "x"
        |            println(a + param)
        |            "stop here"
        |      }
        |    }
        |    printName("param", "notUsed")
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 7)
    runDebugger("Sample") {
      waitForBreakpoint()
      evalEquals("a", "a")
      evalEquals("i", "10")
      evalEquals("x", "x")
      evalEquals("param", "param")
      evalEquals("name", "name")
      evalEquals("notUsed", "notUsed")
      evalEquals("args", "[]")
    }
  }

  def testFunctionExpr() {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  val name = "name"
        |  def main(args: Array[String]) {
        |    def printName(param: String, notUsed: String) {
        |      List("a").foreach {
        |        a =>
        |            val x = "x"
        |            println(a + param)
        |            "stop here"
        |      }
        |    }
        |    printName("param", "notUsed")
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 7)
    runDebugger("Sample") {
      waitForBreakpoint()
      evalEquals("a", "a")
      evalEquals("x", "x")
      evalEquals("param", "param")
      evalEquals("name", "name")
      evalEquals("notUsed", "notUsed")
      evalEquals("args", "[]")
    }
  }

  def testForStmt() {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  val name = "name"
        |  def main(args: Array[String]) {
        |    def printName(param: String, notUsed: String) {
        |      for (s <- List("a", "b"); if s == "a"; ss = s + s; i <- List(1,2); if i == 1; si = s + i) {
        |        val in = "in"
        |        println(s + param + ss)
        |        "stop here"
        |      }
        |    }
        |    printName("param", "notUsed")
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 6)
    runDebugger("Sample") {
      waitForBreakpoint()
      evalEquals("s", "a")
      evalEquals("in", "in")
      evalEquals("param", "param")
      evalEquals("name", "name")
      evalEquals("notUsed", "notUsed")
      evalEquals("args", "[]")
      evalEquals("ss", "aa")
      evalEquals("i", ScalaBundle.message("not.used.from.for.statement", "i"))
      evalEquals("si", ScalaBundle.message("not.used.from.for.statement", "si"))
    }
  }


}
