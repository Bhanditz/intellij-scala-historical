package org.jetbrains.plugins.scala
package configuration.template

import javax.swing.JComponent

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.vfs.VfsUtilCore

/**
 * @author Pavel Fatin
 */
object SdkSelection {
  def browse(parent: JComponent): Option[Either[String, ScalaSdkDescriptor]] = {
    val virtualFiles = FileChooser.chooseFiles(new ScalaFilesChooserDescriptor(), parent, null, null).toSeq

    val files = virtualFiles.map(VfsUtilCore.virtualToIoFile)

    val allFiles = files.filter(_.isFile) ++ files.flatMap(_.allFiles)

    val components = Component.discoverIn(allFiles)

    if (files.length > 0) Some(ScalaSdkDescriptor.from(components)) else None
  }
}
