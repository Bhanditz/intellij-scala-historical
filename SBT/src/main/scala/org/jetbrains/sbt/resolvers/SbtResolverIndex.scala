package org.jetbrains.sbt
package resolvers

import java.io._
import java.util.Properties

import com.intellij.util.io.{DataExternalizer, EnumeratorStringDescriptor, PersistentHashMap}

/**
 * @author Nikolay Obedin
 * @since 7/25/14.
 */
class SbtResolverIndex private (val root: String, val timestamp: Long, val indexDir: File) {

  import org.jetbrains.sbt.resolvers.SbtResolverIndex._

  private val groupToArtifactMap = createPersistentMap(indexDir / Paths.GROUP_TO_ARTIFACT_FILE)
  private val groupArtifactToVersionMap = createPersistentMap(indexDir / Paths.GROUP_ARTIFACT_TO_VERSION_FILE)

  def update() {
    def getOrCreate(map: PersistentHashMap[String, Set[String]], key: String) = map.get(key) match {
      case null  => Set.empty[String]
      case value => value
    }
    using(SbtResolverIndexer.getInstance(root, indexDir)) (indexer => {
      indexer.update()
      indexer.foreach (artifact => {
        val newGroupSet = getOrCreate(groupToArtifactMap, artifact.groupId) + artifact.artifactId
        groupToArtifactMap.put(artifact.groupId, newGroupSet)
        val newGroupArtifactSet = getOrCreate(groupArtifactToVersionMap, artifact.groupArtifact) + artifact.version
        groupArtifactToVersionMap.put(artifact.groupArtifact, newGroupArtifactSet)
      })
    })
    store()
  }

  def store() {
    indexDir.mkdirs()
    if (!indexDir.exists || !indexDir.isDirectory)
      throw new RuntimeException("Resolver's index dir can not be created: %s" format indexDir.absolutePath)

    val props = new Properties()
    props.setProperty(Keys.VERSION, CURRENT_INDEX_VERSION)
    props.setProperty(Keys.ROOT, root)
    props.setProperty(Keys.UPDATE_TIMESTAMP, timestamp.toString)

    val propFile = indexDir / Paths.PROPERTIES_FILE
    using(new FileOutputStream(propFile)) (outputStream => {
      props.store(outputStream, null)
    })

    groupToArtifactMap.force()
    groupArtifactToVersionMap.force()
  }

  def close() {
    groupToArtifactMap.close()
    groupArtifactToVersionMap.close()
  }

  private def createPersistentMap(file: File) =
    new PersistentHashMap[String, Set[String]](file, new EnumeratorStringDescriptor, new SetDescriptor)
}

object SbtResolverIndex {
  val NO_TIMESTAMP = -1

  val CURRENT_INDEX_VERSION = "1"

  object Paths {
    val PROPERTIES_FILE = "index.properties"
    val GROUP_TO_ARTIFACT_FILE = "group-to-artifact.map"
    val GROUP_ARTIFACT_TO_VERSION_FILE = "group-artifact-to-version.map"
  }

  object Keys {
    val VERSION = "version"
    val ROOT = "root"
    val UPDATE_TIMESTAMP = "update-timestamp"
  }

  def create(root: String, indexDir: File) = {
    val index = new SbtResolverIndex(root, NO_TIMESTAMP, indexDir)
    index.store()
    index
  }

  def load(indexDir: File) = {
    val propFile = indexDir / Paths.PROPERTIES_FILE
    val props = new Properties()

    using(new FileInputStream(propFile)) (inputStream => {
      props.load(inputStream)
    })

    val indexVersion = props.getProperty(Keys.VERSION)
    if (indexVersion != CURRENT_INDEX_VERSION)
      throw new RuntimeException("Index version differs from expected one: %s" format propFile.absolutePath)

    val root = props.getProperty(Keys.ROOT)
    val timestamp = props.getProperty(Keys.UPDATE_TIMESTAMP).toLong
    new SbtResolverIndex(root, timestamp, indexDir)
  }
}

private class SetDescriptor extends DataExternalizer[Set[String]] {
  def save(s: DataOutput, set: Set[String]) {
    s.writeInt(set.size)
    set foreach s.writeUTF
  }

  def read(s: DataInput): Set[String] = {
    val count = s.readInt()
    1.to(count).map(_ => s.readUTF).toSet
  }
}
