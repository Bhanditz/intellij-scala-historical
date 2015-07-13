package org.jetbrains.sbt.project

import java.io.File

import com.intellij.facet.FacetManager
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.sbt.UsefulTestCaseHelper
import org.jetbrains.sbt.project.data.AndroidFacetNode

/**
 * @author Nikolay Obedin
 * @since 6/15/15.
 */
class AndroidFacetDataServiceTest extends ProjectDataServiceTestCase {

  import ExternalSystemDsl._

  private def generateProject(proguardConfig: Seq[String]): DataNode[ProjectData] =
    new project {
      name := getProject.getName
      ideDirectoryPath := getProject.getBasePath
      linkedProjectPath := getProject.getBasePath

      modules += new javaModule {
        name := "Module 1"
        moduleFileDirectoryPath := getProject.getBasePath + "/module1"
        externalConfigPath := getProject.getBasePath + "/module1"
        arbitraryNodes += new AndroidFacetNode(
          version = "21",
          manifest = getProject.getBasePath + "/manifest.xml",
          apk = getProject.getBasePath + "/test.apk",
          res = getProject.getBasePath + "/res",
          assets = getProject.getBasePath + "/assets",
          gen = getProject.getBasePath + "/gen",
          libs = getProject.getBasePath + "/libs",
          isLibrary = true,
          proguardConfig = proguardConfig
        )
      }
    }.build.toDataNode

  private def doTestFacetSetup(proguardConfig: Seq[String]): Unit = {
    importProjectData(generateProject(proguardConfig))

    val module = ModuleManager.getInstance(getProject).findModuleByName("Module 1")
    assert(module != null)

    val facet = FacetManager.getInstance(module).getFacetByType(AndroidFacet.ID)
    assert(facet != null)

    val properties = facet.getConfiguration.getState
    assert(properties.GEN_FOLDER_RELATIVE_PATH_APT == "/../gen")
    assert(properties.GEN_FOLDER_RELATIVE_PATH_AIDL == "/../gen")
    assert(properties.MANIFEST_FILE_RELATIVE_PATH == "/../manifest.xml")
    assert(properties.RES_FOLDER_RELATIVE_PATH == "/../res")
    assert(properties.ASSETS_FOLDER_RELATIVE_PATH == "/../assets")
    assert(properties.LIBS_FOLDER_RELATIVE_PATH == "/../libs")
    assert(properties.APK_PATH == "/../test.apk")
    assert(properties.LIBRARY_PROJECT)
    assert(properties.myProGuardCfgFiles.isEmpty == proguardConfig.isEmpty)
    assert(properties.RUN_PROGUARD == proguardConfig.nonEmpty)

    if (proguardConfig.nonEmpty) {
      import scala.collection.JavaConverters._
      val proguardConfigPath = FileUtil.toSystemDependentName(getProject.getBasePath + "/proguard-sbt.txt")
      assert(properties.myProGuardCfgFiles.asScala.toSeq == Seq(proguardConfigPath))
      val actualProguardConfig = scala.io.Source.fromFile(proguardConfigPath).getLines().toSeq
      assert(actualProguardConfig == proguardConfig)
    }
  }

  def testWithoutProguard(): Unit =
    doTestFacetSetup(Seq.empty)

  def testWithProguard(): Unit =
    doTestFacetSetup(Seq("-some-option", "-another-option"))

  def testModuleIsNull(): Unit = {
    val testProject = new project {
      name := getProject.getName
      ideDirectoryPath := getProject.getBasePath
      linkedProjectPath := getProject.getBasePath
      arbitraryNodes += new AndroidFacetNode("", "", "", "", "", "", "", false, Seq.empty)
    }.build.toDataNode
    importProjectData(testProject)
  }
}
