package org.jetbrains.plugins.scala.debugger.stepInto

/**
 * @author Nikolay.Tropin
 */
class StepIntoTest extends StepIntoTestBase{
  def testSimple() {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  def main(args: Array[String]) {
        |    val x = AAA.foo("123") //stop here
        |  }
        |}
        |
        |object AAA {
        |  def foo(s: String) = {
        |    s.substring(1) //should step here
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 2)
    runDebugger("Sample") {
      waitForBreakpoint()
      doStepInto()
      checkLocation("Sample.scala", "foo", 9)
    }
  }

  def testConstructor() {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  def main(args: Array[String]) {
        |    val x = new ZZZ(1).foo() //stop here
        |  }
        |}
      """.stripMargin.trim()
    )
    addFileToProject("ZZZ.scala",
    """
      |class ZZZ(z: Int) { //should step here
      |  val x = z
      |
      |  def foo(): Int = z
      |}""".stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 2)
    runDebugger("Sample") {
      waitForBreakpoint()
      doStepInto()
      checkLocation("ZZZ.scala", "<init>", 1)
    }
  }

  def testApplyMethod(): Unit = {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  def main(args: Array[String]) {
        |    val x = ZZZ(1).foo() //stop here
        |  }
        |}
      """.stripMargin.trim()
    )
    addFileToProject("ZZZ.scala",
      """
        |class ZZZ(z: Int) {
        |  val x = z
        |
        |  def foo(): Int = z
        |}
        |
        |object ZZZ {
        |  def apply(z: Int) = {
        |    new ZZZ(z)  //should step here
        |  }
        |}
        |""".stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 2)
    runDebugger("Sample") {
      waitForBreakpoint()
      doStepInto()
      checkLocation("ZZZ.scala", "apply", 9)
    }
  }

  def testIntoPackageObject(): Unit = {
    addFileToProject("Sample.scala",
      """
        |package test
        |
        |object Sample {
        |  def main(args: Array[String]) {
        |    foo(1)
        |  }
        |}
        |
      """.stripMargin.trim()
    )
    addFileToProject("package.scala",
      """
        |package object test {
        |  def foo(i: Int): Unit = {
        |    println("foo!") //should step here
        |  }
        |}
        |""".stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 4)
    runDebugger("test.Sample") {
      waitForBreakpoint()
      doStepInto()
      checkLocation("package.scala", "foo", 3)
    }
  }

  def testFromPackageObject(): Unit = {
    addFileToProject("Sample.scala",
      """
        |package test
        |
        |object Sample {
        |  def main(args: Array[String]) {
        |    foo(1)
        |  }
        |
        |  def bar() {
        |    println("bar") //should step here
        |  }
        |}
        |
      """.stripMargin.trim()
    )
    addFileToProject("package.scala",
      """
        |package object test {
        |  def foo(i: Int): Unit = {
        |    Sample.bar() //stop here
        |  }
        |}
        |""".stripMargin.trim()
    )
    addBreakpoint("package.scala", 2)
    runDebugger("test.Sample") {
      waitForBreakpoint()
      doStepInto()
      checkLocation("Sample.scala", "bar", 9)
    }
  }

  def testWithDefaultParam() {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  def main(args: Array[String]) {
        |    val x = ZZZ.withDefault(1)  //stop here
        |  }
        |}
      """.stripMargin.trim()
    )
    addFileToProject("ZZZ.scala",
      """
        |object ZZZ {
        |  def withDefault(z: Int, s: String = "default") = {
        |    println("hello")  //should step here
        |  }
        |}""".stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 2)
    runDebugger("Sample") {
      waitForBreakpoint()
      doStepInto()
      checkLocation("ZZZ.scala", "withDefault", 3)
    }
  }

  def testTraitMethod() {
    addFileToProject("Sample.scala",
      """
        |object Sample extends ZZZ{
        |  def main(args: Array[String]) {
        |    val x = foo(1)  //stop here
        |  }
        |}
      """.stripMargin.trim()
    )
    addFileToProject("ZZZ.scala",
      """
        |trait ZZZ {
        |  def foo(z: Int) = {
        |    println("hello")  //should step here
        |  }
        |}""".stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 2)
    runDebugger("Sample") {
      waitForBreakpoint()
      doStepInto()
      checkLocation("ZZZ.scala", "foo", 3)
    }
  }

  def testUnapplyMethod() {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  def main(args: Array[String]) {
        |    val z = Some(1)
        |    z match {
        |      case ZZZ(a) => ZZZ(a)  //stop here
        |      case _ =>
        |    }
        |  }
        |}
      """.stripMargin.trim()
    )
    addFileToProject("ZZZ.scala",
      """
        |object ZZZ {
        |  def unapply(z: Option[Int]) = z  //should step here
        |
        |  def apply(i: Int) = Some(i)
        |}""".stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 4)
    runDebugger("Sample") {
      waitForBreakpoint()
      doStepInto()
      checkLocation("ZZZ.scala", "unapply", 2)
    }
  }

  def testImplicitConversion() {
    addFileToProject("Sample.scala",
      """
        |import scala.language.implicitConversions
        |
        |object Sample {
        |
        |  class A; class B
        |
        |  implicit def a2B(a: A): B = new B  //should step here
        |
        |  def foo(b: B): Unit = {}
        |
        |  def main(args: Array[String]) {
        |    val a = new A
        |    foo(a) //stop here
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 12)
    runDebugger("Sample") {
      waitForBreakpoint()
      doStepInto()
      checkLocation("Sample.scala", "a2B", 7)
    }
  }

  def testLazyVal(): Unit = {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  lazy val lzy = Some(1)  //should step here
        |
        |  def main(args: Array[String]) {
        |    val x = lzy // stop here
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 4)
    runDebugger("Sample") {
      waitForBreakpoint()
      doStepInto()
      checkLocation("Sample.scala", "lzy$lzycompute", 2)
    }
  }

  def testLazyVal2(): Unit = {
    addFileToProject("Sample.scala",
      """
        |object Sample {
        |  lazy val lzy = new AAA
        |
        |  def main(args: Array[String]) {
        |    val x = lzy
        |    val y = lzy.foo() //stop here
        |  }
        |
        |  class AAA {
        |    def foo() {} //should step here
        |  }
        |}
      """.stripMargin.trim()
    )
    addBreakpoint("Sample.scala", 5)
    runDebugger("Sample") {
      waitForBreakpoint()
      doStepInto()
      checkLocation("Sample.scala", "foo", 10)
    }
  }


}
