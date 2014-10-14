package org.jetbrains.plugins.scala.project;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.ui.IdeBorderFactory;

import javax.swing.*;
import java.awt.*;

/**
 * @author Pavel Fatin
 */
public class ScalaLibraryEditorForm {
  private JPanel myContentPanel;
  private JComboBox<ScalaLanguageLevel> myLanguageLevel;
  private JPanel myPluginsPanel;

  private MyPathEditor myClasspathEditor = new MyPathEditor(new FileChooserDescriptor(true, false, true, true, false, true));

  public ScalaLibraryEditorForm() {
    myLanguageLevel.setRenderer(new NamedValueRenderer());
    myLanguageLevel.setModel(new DefaultComboBoxModel<ScalaLanguageLevel>(ScalaLanguageLevel.values()));

    myPluginsPanel.setBorder(IdeBorderFactory.createBorder());
    myPluginsPanel.add(myClasspathEditor.createComponent(), BorderLayout.CENTER);
  }

  public ScalaLibraryPropertiesState getState() {
    ScalaLibraryPropertiesState state = new ScalaLibraryPropertiesState();
    state.languageLevel = (ScalaLanguageLevel) myLanguageLevel.getSelectedItem();
    state.compilerClasspath = myClasspathEditor.getPaths();
    return state;
  }

  public void setState(ScalaLibraryPropertiesState state) {
    myLanguageLevel.setSelectedItem(state.languageLevel);
    myClasspathEditor.setPaths(state.compilerClasspath);
  }

  public JComponent getComponent() {
    return myContentPanel;
  }
}
