package org.jetbrains.sbt
package project

import com.intellij.pom.java.LanguageLevel
import org.jetbrains.sbt.project.data.Sdk

import scala.language.implicitConversions

/**
 * @author Nikolay Obedin
 * @since 8/4/15.
 */
object ProjectStructureDsl {

  import DslUtils._

  trait ProjectAttribute
  trait ModuleAttribute
  trait LibraryAttribute

  val name =
    new Attribute[String]("name") with ProjectAttribute with ModuleAttribute with LibraryAttribute
  val libraries =
    new Attribute[Seq[library]]("libraries") with ProjectAttribute with ModuleAttribute
  val modules =
    new Attribute[Seq[module]]("modules") with ProjectAttribute
  val sdk =
    new Attribute[Sdk]("sdk") with ProjectAttribute with ModuleAttribute
  val languageLevel =
    new Attribute[LanguageLevel]("languageLevel") with ProjectAttribute with ModuleAttribute

  val contentRoots =
    new Attribute[Seq[String]]("contentRoots") with ModuleAttribute
  val sources =
    new Attribute[Seq[String]]("sources") with ModuleAttribute with LibraryAttribute
  val testSources =
    new Attribute[Seq[String]]("testSources") with ModuleAttribute
  val resources =
    new Attribute[Seq[String]]("resources") with ModuleAttribute
  val testResources =
    new Attribute[Seq[String]]("testResources") with ModuleAttribute
  val excluded =
    new Attribute[Seq[String]]("excluded") with ModuleAttribute
  val moduleDependencies =
    new Attribute[Seq[module]]("moduleDependencies") with ModuleAttribute
  val libraryDependencies =
    new Attribute[Seq[library]]("libraryDependencies") with ModuleAttribute

  val classes =
    new Attribute[Seq[String]]("classes") with LibraryAttribute
  val javadocs =
    new Attribute[Seq[String]]("javadocs") with LibraryAttribute

  class project {
    val attributes = new AttributeMap

    protected implicit def defineAttribute[T : Manifest](attribute: Attribute[T] with ProjectAttribute): AttributeDef[T] =
      new AttributeDef(attribute, attributes)
    protected implicit def defineAttributeSeq[T](attribute: Attribute[Seq[T]] with ProjectAttribute)(implicit m: Manifest[Seq[T]]): AttributeSeqDef[T] =
      new AttributeSeqDef(attribute, attributes)
  }

  class module {
    val attributes = new AttributeMap

    protected implicit def defineAttribute[T : Manifest](attribute: Attribute[T] with ModuleAttribute): AttributeDef[T] =
      new AttributeDef(attribute, attributes)
    protected implicit def defineAttributeSeq[T](attribute: Attribute[Seq[T]] with ModuleAttribute)(implicit m: Manifest[Seq[T]]): AttributeSeqDef[T] =
      new AttributeSeqDef(attribute, attributes)
  }

  class library {
    val attributes = new AttributeMap

    protected implicit def defineAttribute[T : Manifest](attribute: Attribute[T] with LibraryAttribute): AttributeDef[T] =
      new AttributeDef(attribute, attributes)
    protected implicit def defineAttributeSeq[T](attribute: Attribute[Seq[T]] with LibraryAttribute)(implicit m: Manifest[Seq[T]]): AttributeSeqDef[T] =
      new AttributeSeqDef(attribute, attributes)
  }
}

