package org.jetbrains.plugins.scala
package codeInspection.collections

import org.jetbrains.plugins.scala.codeInspection.InspectionBundle

/**
 * Nikolay.Tropin
 * 5/30/13
 */
class FoldLeftTrueAndTest extends OperationsOnCollectionInspectionTest {
  val hint = InspectionBundle.message("foldLeft.true.and.hint")
  def test_1() {
    val selected = s"List(false).${START}foldLeft(true){_ && _}$END"
    check(selected)
    val text = "List(false).foldLeft(true){_ && _}"
    val result = "List(false).forall(_)"
    testFix(text, result, hint)
  }

  def test_2() {
    val selected = s"""def a(x: String) = false
                     |List("a").${START}foldLeft(true) (_ && a(_))$END""".stripMargin
    check(selected)
    val text = """def a(x: String) = false
                 |List("a").foldLeft(true) (_ && a(_))""".stripMargin
    val result = """def a(x: String) = false
                   |List("a").forall(a(_))""".stripMargin
    testFix(text, result, hint)
  }

  def test_3() {
    val selected = s"""def a(x: String) = false
                     |List("a").${START}foldLeft(true) ((x,y) => x && a(y))$END""".stripMargin
    check(selected)
    val text = """def a(x: String) = false
                 |List("a").foldLeft(true) ((x,y) => x && a(y))""".stripMargin
    val result = """def a(x: String) = false
                   |List("a").forall(y => a(y))""".stripMargin
    testFix(text, result, hint)
  }

  def test_4() {

    val text = """def a(x: String) = false
                 |List("a").foldLeft(true) ((x,y) => x && a(x))""".stripMargin
    checkTextHasNoErrors(text, annotation, inspectionClass)
  }

  override val inspectionClass = classOf[FoldLeftTrueAndInspection]
}
