package org.jetbrains.plugins.scala.codeInspection.collections

import org.jetbrains.plugins.scala.codeInspection.InspectionBundle

/**
 * @author Nikolay.Tropin
 */
class EmptyCheckTest extends OperationsOnCollectionInspectionTest {
  override val inspectionClass: Class[_ <: OperationOnCollectionInspection] = classOf[EmptyCheckInspection]

  override def hint: String = isEmptyHint

  val isEmptyHint = InspectionBundle.message("replace.with.isEmpty")
  val isDefinedHint = InspectionBundle.message("replace.with.isDefined")
  val nonEmptyHint = InspectionBundle.message("replace.with.nonEmpty")

  def testNotIsEmpty() {
    val selected = s"!Seq().${START}isEmpty$END"
    checkTextHasError(selected, nonEmptyHint, inspectionClass)
    val text = "!Seq().isEmpty"
    val result = "Seq().nonEmpty"
    testFix(text, result, nonEmptyHint)
  }

  def testNotNonEmpty() {
    val selected = s"!Seq().${START}nonEmpty$END"
    checkTextHasError(selected, isEmptyHint, inspectionClass)
    val text = "!Seq().nonEmpty"
    val result = "Seq().isEmpty"
    testFix(text, result, isEmptyHint)
  }

  def testNotIsDefined() {
    val selected = s"!Option(1).${START}isDefined$END"
    checkTextHasError(selected, isEmptyHint, inspectionClass)
    val text = "!Option(1).isDefined"
    val result = "Option(1).isEmpty"
    testFix(text, result, isEmptyHint)
  }

  def testSizeEqualsZero(): Unit = {
    val selected = s"Seq().${START}size == 0$END"
    checkTextHasError(selected, isEmptyHint, inspectionClass)
    val text = "Seq().size == 0"
    val result = "Seq().isEmpty"
    testFix(text, result, isEmptyHint)
  }

  def testSizeGreaterZero(): Unit = {
    val selected = s"Seq().${START}size > 0$END"
    checkTextHasError(selected, nonEmptyHint, inspectionClass)
    val text = "Seq().size > 0"
    val result = "Seq().nonEmpty"
    testFix(text, result, nonEmptyHint)
  }

  def testLengthGrEqOne(): Unit = {
    val selected = s"Seq().${START}length >= 1$END"
    checkTextHasError(selected, nonEmptyHint, inspectionClass)
    val text = "Seq().size >= 1"
    val result = "Seq().nonEmpty"
    testFix(text, result, nonEmptyHint)
  }

  def testEqualsNone(): Unit = {
    val selected = s"Option(1) $START== None$END"
    checkTextHasError(selected, isEmptyHint, inspectionClass)
    val text = "Option(1) == None"
    val result = "Option(1).isEmpty"
    testFix(text, result, isEmptyHint)
  }

  def testNotEqualsNone(): Unit = {
    val selected = s"Option(1) $START!= None$END"
    checkTextHasError(selected, isDefinedHint, inspectionClass)
    val text = "Option(1) != None"
    val result = "Option(1).isDefined"
    testFix(text, result, isDefinedHint)
  }

  def testSizeNotEqualsZero(): Unit = {
    val selected = s"Seq().${START}size != 0$END"
    checkTextHasError(selected, nonEmptyHint, inspectionClass)
    val text = "Seq().size != 0"
    val result = "Seq().nonEmpty"
    testFix(text, result, nonEmptyHint)
  }

  def testNotSizeNotEqualsZero(): Unit = {
    val selected = s"!(Seq().${START}size != 0)$END"
    checkTextHasError(selected, isEmptyHint, inspectionClass)
    val text = "!(Seq().size != 0)"
    val result = "Seq().isEmpty"
    testFix(text, result, isEmptyHint)
  }
}
