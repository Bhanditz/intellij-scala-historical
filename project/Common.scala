import sbt._
import Keys._
import scala.language.implicitConversions
import scala.language.postfixOps

object Common {
  def newProject(projectName: String, base: File): Project =
    Project(projectName, base).settings(
      name := projectName,
      organization := "JetBrains",
      scalaVersion := Versions.scalaVersion,
      unmanagedSourceDirectories in Compile += baseDirectory.value / "src",
      unmanagedSourceDirectories in Test += baseDirectory.value / "test",
      unmanagedResourceDirectories in Compile += baseDirectory.value / "resources",
      libraryDependencies += Dependencies.junitInterface
    )

  def newProject(projectName: String): Project =
    newProject(projectName, file(projectName))

  def unmanagedJarsFrom(sdkDirectory: File, subdirectories: String*): Classpath = {
    val sdkPathFinder = subdirectories.foldLeft(PathFinder.empty) { (finder, dir) =>
      finder +++ (sdkDirectory / dir)
    }
    (sdkPathFinder * globFilter("*.jar")).classpath
  }

  def filterTestClasspath(classpath: Def.Classpath): Def.Classpath =
    classpath.filterNot(_.data.getName.endsWith("lucene-core-2.4.1.jar"))

  val slowTestsCategory: String =
    "org.jetbrains.plugins.scala.SlowTests"

  val testConfigDir: File =
    Path.userHome / ".IdeaData" / "IDEA-15" / "scala" / "test-config"

  val testSystemDir: File =
    Path.userHome / ".IdeaData" / "IDEA-15" / "scala" / "test-system"

  def ivyCacheDir: File =
    Option(System.getProperty("sbt.ivy.home")) match {
      case Some(path) => file(path) / "cache"
      case None       => Path.userHome / ".ivy2" / "cache"
    }

  def commonTestSettings(packagedPluginDir: SettingKey[File]): Seq[Setting[_]] = Seq(
    fork in Test := true,
    parallelExecution := false,
    javaOptions in Test := Seq(
      "-Xms128m",
      "-Xmx4096m",
      "-XX:MaxPermSize=350m",
      "-ea",
      s"-Didea.system.path=$testSystemDir",
      s"-Didea.config.path=$testConfigDir",
      s"-Dsbt.ivy.home=$ivyCacheDir",
      s"-Dplugin.path=${packagedPluginDir.value}"
    ),
    envVars in Test += "NO_FS_ROOTS_ACCESS_CHECK" -> "yes",
    fullClasspath in Test <<= fullClasspath.in(Test).map(filterTestClasspath)
  )
}
