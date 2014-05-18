package org.jetbrains.plugins.scala
package editor.importOptimizer


import com.intellij.lang.ImportOptimizer
import com.intellij.openapi.util.{TextRange, EmptyRunnable}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi._
import lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.api.base.{ScStableCodeReferenceElement, ScReferenceElement}
import lang.psi.api.toplevel.imports.usages.ImportUsed
import lang.resolve.ScalaResolveResult
import scala.collection.{mutable, Set}
import lang.psi.api.{ScalaRecursiveElementVisitor, ScalaFile}
import lang.psi.api.toplevel.imports.{ScImportExpr, ScImportStmt}
import lang.psi.impl.ScalaPsiElementFactory
import lang.psi.api.expr.{ScMethodCall, ScForStatement, ScExpression}
import lang.psi.{ScImportsHolder, ScalaPsiUtil, ScalaPsiElement}
import scala.collection.JavaConversions._
import settings.ScalaProjectSettings
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScMember, ScTypeDefinition, ScObject}
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScSimpleTypeElement
import java.util
import com.intellij.concurrency.JobLauncher
import com.intellij.util.Processor
import com.intellij.util.containers.{ConcurrentHashMap, ConcurrentHashSet}
import com.intellij.openapi.progress.{ProgressIndicator, ProgressManager}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.usages.ImportSelectorUsed
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.usages.ImportExprUsed
import scala.Some
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.usages.ImportWildcardSelectorUsed
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScTypedDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.ScTemplateBody
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.packaging.ScPackaging
import scala.annotation.tailrec
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaNamesUtil
import com.intellij.openapi.editor.Document

/**
 * User: Alexander Podkhalyuzin
 * Date: 16.06.2009
 */

class ScalaImportOptimizer extends ImportOptimizer {
  import ScalaImportOptimizer._

  def processFile(file: PsiFile): Runnable = processFile(file, deleteOnlyWrongImorts = false)

  def processFile(file: PsiFile, deleteOnlyWrongImorts: Boolean): Runnable = {
    val scalaFile = file match {
      case scFile: ScalaFile => scFile
      case multiRootFile: PsiFile if multiRootFile.getViewProvider.getLanguages contains ScalaFileType.SCALA_LANGUAGE =>
        multiRootFile.getViewProvider.getPsi(ScalaFileType.SCALA_LANGUAGE).asInstanceOf[ScalaFile]
      case _ => return EmptyRunnable.getInstance() 
    }

    val usedImports = new ConcurrentHashSet[ImportUsed]
    val list: util.ArrayList[PsiElement] =  new util.ArrayList[PsiElement]()
    scalaFile.accept(new ScalaRecursiveElementVisitor {
      override def visitElement(element: ScalaPsiElement): Unit = {
        list.add(element)
        super.visitElement(element)
      }
    })
    val size = list.size
    val progressManager: ProgressManager = ProgressManager.getInstance()
    val indicator: ProgressIndicator = if (progressManager.hasProgressIndicator) progressManager.getProgressIndicator else null
    if (indicator != null) indicator.setText2("Analyzing " + file.getName)
    val i = new AtomicInteger(0)
    JobLauncher.getInstance().invokeConcurrentlyUnderProgress(list, indicator, true, true, new Processor[PsiElement] {
      override def process(element: PsiElement): Boolean = {
        val count: Int = i.getAndIncrement
        if (count <= size && indicator != null) indicator.setFraction(count.toDouble / size)
        element match {
          case ref: ScReferenceElement =>
            if (PsiTreeUtil.getParentOfType(ref, classOf[ScImportStmt]) == null) {
              ref.multiResolve(false) foreach {
                case scalaResult: ScalaResolveResult => scalaResult.importsUsed.foreach(usedImports.add)
                case _ =>
              }
            }
          case simple: ScSimpleTypeElement =>
            simple.findImplicitParameters match {
              case Some(parameters) =>
                parameters.foreach {
                  case r: ScalaResolveResult => r.importsUsed.foreach(usedImports.add)
                  case _ =>
                }
              case _ =>
            }
          case _ =>
        }
        val imports = element match {
          case expression: ScExpression => checkTypeForExpression(expression)
          case _ => ScalaImportOptimizer.NO_IMPORT_USED
        }
        imports.foreach(usedImports.add)
        true
      }
    })

    i.set(0)

    if (indicator != null) indicator.setText("Collecting imports information in file: " + file.getName)

    val importsInfo = new ConcurrentHashMap[TextRange, (Set[String], Seq[ImportInfo])]

    def isImportUsed(importUsed: ImportUsed): Boolean = {
      //todo: collect proper information about language features
      usedImports.contains(importUsed) || isLanguageFeatureImport(importUsed)
    }

    JobLauncher.getInstance().invokeConcurrentlyUnderProgress(list, indicator, true, true, new Processor[PsiElement] {
      override def process(element: PsiElement): Boolean = {
        val count: Int = i.getAndIncrement
        if (count <= size && indicator != null) indicator.setFraction(count.toDouble / size)
        element match {
          case imp: ScImportsHolder =>
            var rangeStart = -1
            var rangeEnd = -1
            var rangeNames = Set.empty[String]
            val infos = new ArrayBuffer[ImportInfo]
            for (child <- imp.getChildren) {
              child match {
                case imp: ScImportStmt =>
                  if (rangeStart == -1) {
                    rangeStart = imp.getTextRange.getStartOffset
                    rangeEnd = imp.getTextRange.getEndOffset
                    val refText = "someIdentifier"
                    val reference = ScalaPsiElementFactory.createReferenceFromText(refText, imp.getContext, imp)
                    val rangeNamesSet = new mutable.HashSet[String]()
                    reference.getResolveResultVariants.foreach {
                      case ScalaResolveResult(p: PsiPackage, _) =>
                        if (p.getParentPackage != null && p.getParentPackage.getName != null) {
                          rangeNamesSet += name(p.getName)
                        }
                      case ScalaResolveResult(td: ScTypedDefinition, _) if td.isStable => rangeNamesSet += td.name
                      case ScalaResolveResult(_: ScTypeDefinition, _) =>
                      case ScalaResolveResult(c: PsiClass, _) => rangeNamesSet += name(c.getName)
                      case ScalaResolveResult(f: PsiField, _) if f.hasModifierProperty("final") =>
                        rangeNamesSet += name(f.getName)
                      case _ =>
                    }
                    rangeNames = rangeNamesSet.toSet
                  } else {
                    rangeEnd = imp.getTextRange.getEndOffset
                  }
                  imp.importExprs.foreach(expr =>
                    getImportInfo(expr, isImportUsed) match {
                      case Some(importInfo) => infos += importInfo
                      case _ =>
                    }
                  )
                case _ =>
                  if (rangeStart != -1) {
                    importsInfo.put(new TextRange(rangeStart, rangeEnd), (rangeNames, Seq(infos: _*)))
                    rangeStart = -1
                    rangeEnd = -1
                    rangeNames = Set.empty
                    infos.clear()
                  }
              }
            }
            if (rangeStart != -1) {
              importsInfo.put(new TextRange(rangeStart, rangeEnd), (rangeNames, Seq(infos: _*)))
              rangeStart = -1
              rangeEnd = -1
              rangeNames = Set.empty
              infos.clear()
            }
          case _ =>
        }
        true
      }
    })
    importsInfo

    new Runnable {
      def run() {
        val documentManager = PsiDocumentManager.getInstance(scalaFile.getProject)
        val document: Document = documentManager.getDocument(scalaFile)
        documentManager.commitDocument(document)
        for ((range, (names, importInfos)) <- importsInfo.toSeq.sortBy(_._1.getStartOffset).reverseIterator) {

          val text = importInfos.map(_.getImportText(isRelative = false)).mkString("\n")
          document.replaceString(range.getStartOffset, range.getEndOffset, text)
        }
      }
    }
  }

  private def checkTypeForExpression(expr: ScExpression): Set[ImportUsed] = {
    var res: collection.mutable.HashSet[ImportUsed] =
      collection.mutable.HashSet(expr.getTypeAfterImplicitConversion(expectedOption = expr.smartExpectedType()).
        importsUsed.toSeq : _*)
    expr match {
      case call: ScMethodCall =>
        res ++= call.getImportsUsed
      case _ =>
    }
    expr.findImplicitParameters match {
      case Some(seq) =>
        for (rr <- seq if rr != null) {
          res ++= rr.importsUsed
        }
      case _ =>
    }
    expr match {
      case f: ScForStatement => res ++= ScalaPsiUtil.getExprImports(f)
      case _ =>
    }
    res
  }


  def supports(file: PsiFile): Boolean = {
    true
  }
}

object ScalaImportOptimizer {
  val NO_IMPORT_USED: Set[ImportUsed] = Set.empty

  def isLanguageFeatureImport(used: ImportUsed): Boolean = {
    val expr = used match {
      case ImportExprUsed(e) => e
      case ImportSelectorUsed(selector) => PsiTreeUtil.getParentOfType(selector, classOf[ScImportExpr])
      case ImportWildcardSelectorUsed(e) => e
    }
    if (expr == null) return false
    if (expr.qualifier == null) return false
    expr.qualifier.resolve() match {
      case o: ScObject =>
        o.qualifiedName.startsWith("scala.language") || o.qualifiedName.startsWith("scala.languageFeature")
      case _ => false
    }
  }

  class ImportInfo(val importUsed: Set[ImportUsed], val prefixQualifier: String,
                   val relative: Option[String], val allNames: Set[String],
                   val singleNames: Set[String], renames: Map[String, String],
                   val hidedNames: Set[String], val hasWildcard: Boolean, val rootUsed: Boolean) {
    def getImportText(isRelative: Boolean): String = {
      val groupStrings = new ArrayBuffer[String]
      if (!hasWildcard) groupStrings ++= singleNames
      groupStrings ++= renames.map(pair => pair._1 + " => " + pair._2)
      groupStrings ++= hidedNames.map(_ + " => _")
      if (hasWildcard) groupStrings += "_"
      val postfix =
        if (groupStrings.length > 1 || !renames.isEmpty || !hidedNames.isEmpty) groupStrings.mkString("{", ", ", "}")
        else groupStrings(0)
      "import " + (if (rootUsed) "_root_." else "") + relative.getOrElse(prefixQualifier) + "." + postfix

    }
  }

  def name(s: String): String = {
    if (ScalaNamesUtil.isKeyword(s)) s"`$s`"
    else s
  }

  def getImportInfo(imp: ScImportExpr, isImportUsed: ImportUsed => Boolean): Option[ImportInfo] = {
    val res = new ArrayBuffer[ImportUsed]
    val allNames = new mutable.HashSet[String]()
    val singleNames = new mutable.HashSet[String]()
    val renames = new mutable.HashMap[String, String]()
    val hidedNames = new mutable.HashSet[String]()
    var hasWildcard = false

    def addAllNames(ref: ScStableCodeReferenceElement, nameToAdd: String): Unit = {
      if (ref.multiResolve(false).exists {
        case ScalaResolveResult(p: PsiPackage, _) => true
        case ScalaResolveResult(td: ScTypedDefinition, _) if td.isStable => true
        case ScalaResolveResult(_: ScTypeDefinition, _) => false
        case ScalaResolveResult(_: PsiClass, _) => true
        case ScalaResolveResult(f: PsiField, _) => f.hasModifierProperty("final")
        case _ => false
      }) allNames += nameToAdd
    }

    if (!imp.singleWildcard && imp.selectorSet == None) {
      val importUsed: ImportExprUsed = ImportExprUsed(imp)
      if (isImportUsed(importUsed)) {
        res += importUsed
        imp.reference match {
          case Some(ref) =>
            singleNames += ref.refName
            addAllNames(ref, ref.refName)
          case None => //something is not valid
        }
      }
    } else if (imp.singleWildcard) {
      val importUsed =
        if (imp.selectorSet == None) ImportExprUsed(imp)
        else ImportWildcardSelectorUsed(imp)
      if (isImportUsed(importUsed)) {
        res += importUsed
        hasWildcard = true
        val refText = imp.qualifier.getText + ".someIdentifier"
        val reference = ScalaPsiElementFactory.createReferenceFromText(refText, imp.qualifier.getContext, imp.qualifier)
        reference.getResolveResultVariants.foreach {
          case ScalaResolveResult(p: PsiPackage, _) => allNames += name(p.getName)
          case ScalaResolveResult(td: ScTypedDefinition, _) if td.isStable => allNames += td.name
          case ScalaResolveResult(_: ScTypeDefinition, _) =>
          case ScalaResolveResult(c: PsiClass, _) => allNames += name(c.getName)
          case ScalaResolveResult(f: PsiField, _) if f.hasModifierProperty("final") => allNames += name(f.getName)
          case _ =>
        }
      }
    }
    for (selector <- imp.selectors) {
      val importUsed: ImportSelectorUsed = ImportSelectorUsed(selector)
      if (isImportUsed(importUsed)) {
        res += importUsed
        val refName: String = selector.reference.refName
        if (selector.isAliasedImport) {
          val importedName: String = selector.importedName
          if (importedName == "_") {
            hidedNames += refName
          } else if (importedName == refName) {
            singleNames += refName
            addAllNames(selector.reference, refName)
          } else {
            renames += ((refName, importedName))
            addAllNames(selector.reference, importedName)
          }
        } else singleNames += refName
      }
    }
    allNames --= hidedNames

    if (res.isEmpty) return None //all imports are empty

    val qualifier = imp.qualifier
    if (qualifier == null) return None //ignore invalid imports

    @tailrec
    def deepestQualifier(ref: ScStableCodeReferenceElement): ScStableCodeReferenceElement = {
      ref.qualifier match {
        case Some(q) => deepestQualifier(q)
        case None => ref
      }
    }
    val deepRef = deepestQualifier(qualifier)

    def packageQualifier(p: PsiPackage): String = {
      p.getParentPackage match {
        case null => name(p.getName)
        case parent if parent.getName == null => name(p.getName)
        case parent => packageQualifier(parent) + "." + name(p.getName)
      }
    }

    @tailrec
    def collectQualifierString(ref: ScStableCodeReferenceElement, withDeepest: Boolean,
                               res: String = ""): String = {
      ref.qualifier match {
        case Some(q) => collectQualifierString(q, withDeepest, ref.refName + withDot(res))
        case None if withDeepest && ref.refName != "_root_" => ref.refName + withDot(res)
        case None => res
      }
    }

    def withDot(s: String): String = {
      if (s.isEmpty) "" else "." + s
    }

    var isRelative = false
    var qualifierString = collectQualifierString(qualifier, withDeepest = false)

    def updateQualifierString(prefix: String): Unit = {
      qualifierString = prefix + withDot(qualifierString)
    }

    @tailrec
    def isRelativeObject(o: ScObject, res: Boolean = false): Boolean = {
      o.getContext match {
        case _: ScTemplateBody =>
          o.containingClass match {
            case containingObject: ScObject => isRelativeObject(o, res = true)
            case _ => false //inner of some class/trait
          }
        case _: ScPackaging => true
        case _ => res //something in default package or in local object
      }
    }

    var rootUsed = false

    if (deepRef.getText != "_root_") {
      deepRef.bind() match {
        case Some(ScalaResolveResult(p: PsiPackage, _)) =>
          if (p.getParentPackage != null && p.getParentPackage.getName != null) {
            isRelative = true
            updateQualifierString(packageQualifier(p))
          } else updateQualifierString(deepRef.refName)
        case Some(ScalaResolveResult(o: ScObject, _)) =>
          if (isRelativeObject(o)) {
            isRelative = true
            updateQualifierString(o.qualifiedName)
          } else updateQualifierString(deepRef.refName)
        case Some(ScalaResolveResult(p: PsiClass, _)) =>
          val parts = p.getQualifiedName.split('.')
          if (parts.length > 1) {
            isRelative = true
            updateQualifierString(parts.map(name).mkString("."))
          } else updateQualifierString(deepRef.refName)
        case Some(ScalaResolveResult(td: ScTypedDefinition, _)) =>
          ScalaPsiUtil.nameContext(td) match {
            case m: ScMember =>
              m.containingClass match {
                case o: ScObject if isRelativeObject(o, res = true) =>
                  isRelative = true
                  updateQualifierString(deepRef.refName)
                  updateQualifierString(o.qualifiedName)
                case _ => updateQualifierString(deepRef.refName)
              }
            case _ => updateQualifierString(deepRef.refName)
          }
        case Some(ScalaResolveResult(f: PsiField, _)) =>
          isRelative = true
          updateQualifierString(deepRef.refName)
          f.getContainingClass match {
            case null => return None //somehting is wrong
            case clazz => updateQualifierString(clazz.getQualifiedName.split('.').map(name).mkString("."))
          }
        case _ => return None //do not process invalid import
      }
    } else rootUsed = true

    val relativeQualifier =
      if (isRelative) Some(collectQualifierString(qualifier, withDeepest = true))
      else None

    Some(new ImportInfo(res.toSet, qualifierString, relativeQualifier, allNames.toSet,
      singleNames.toSet, renames.toMap, hidedNames.toSet, hasWildcard, rootUsed))
  }
}