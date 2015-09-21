package org.jetbrains.sbt
package annotator

import _root_.junit.framework.Assert._
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.plugins.scala.annotator.{AnnotatorHolderMock, Message, Error}
import org.jetbrains.plugins.scala.lang.psi.api.ScalaRecursiveElementVisitor
import org.jetbrains.plugins.scala.lang.psi.api.base.ScLiteral
import org.jetbrains.sbt.project.module.SbtModule
import org.jetbrains.sbt.resolvers.{SbtResolver, SbtResolverIndexesManager}


/**
 * @author Nikolay Obedin
 * @since 8/4/14.
 */
class DependencyAnnotatorTest extends AnnotatorTestBase {

  val testResolver = new SbtResolver(SbtResolver.Kind.Maven, "Test repo", "file:/%s/sbt/resolvers/testRepository" format baseRootPath)

  def testDoNotAnnotateIndexedDep =
    doTest(Seq.empty)

  def testAnnotateUnresolvedDep = {
    val msg = SbtBundle("sbt.annotation.unresolvedDependency")
    doTest(Seq(Error("\"org.jetbrains\"", msg),
      Error("\"unknown-lib\"", msg),
      Error("\"0.0.0\"", msg)))
  }

  override def setUp() = {
    super.setUp()
    FileUtil.delete(SbtResolverIndexesManager.DEFAULT_INDEXES_DIR)
    SbtResolverIndexesManager().update(Seq(testResolver))

    val moduleManager = Option(ModuleManager.getInstance(getProjectAdapter))
    moduleManager.foreach { manager =>
      manager.getModules.toSeq.foreach { module =>
        val resolvers = SbtModule.getResolversFrom(module)
        SbtModule.setResolversTo(module, resolvers + testResolver)
      }
    }
  }

  override def tearDown() = {
    super.tearDown()
    FileUtil.delete(SbtResolverIndexesManager.DEFAULT_INDEXES_DIR)
  }

  private def doTest(messages: Seq[Message]) {
    val element = loadTestFile()
    val mock = new AnnotatorHolderMock
    val annotator = new SbtDependencyAnnotator

    val visitor = new ScalaRecursiveElementVisitor {
      override def visitLiteral(lit: ScLiteral) {
        annotator.annotate(lit, mock)
        super.visitLiteral(lit)
      }
    }
    element.accept(visitor)
    assertEquals(messages, mock.annotations)
  }
}
