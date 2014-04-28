package org.jetbrains.jps.incremental.scala.model;

import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.ex.JpsElementBase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author Pavel Fatin
 */
public class ProjectSettingsImpl extends JpsElementBase<ProjectSettingsImpl> implements ProjectSettings {
  private State myState;

  public ProjectSettingsImpl(State state) {
    myState = state;
  }

  public IncrementalityType getIncrementalityType() {
    return myState.incrementalityType;
  }

  public CompileOrder getCompileOrder() {
    return myState.compileOrder;
  }

  public String[] getCompilerOptions() {
    List<String> list = new ArrayList<String>();

    if (!myState.warnings) {
      list.add("-nowarn");
    }

    if (myState.deprecationWarnings) {
      list.add("-deprecation");
    }

    if (myState.uncheckedWarnings) {
      list.add("-unchecked");
    }

    if (myState.optimiseBytecode) {
      list.add("-optimise");
    }

    if (myState.explainTypeErrors) {
      list.add("-explaintypes");
    }

    if (myState.continuations) {
      list.add("-P:continuations:enable");
    }

    switch (myState.debuggingInfoLevel) {
      case None:
        list.add("-g:none");
        break;
      case Source:
        list.add("-g:source");
        break;
      case Line:
        list.add("-g:line");
        break;
      case Vars:
        list.add("-g:vars");
        break;
      case Notc:
        list.add("-g:notc");
    }

    for (String pluginPath : myState.plugins) {
      list.add("-Xplugin:" + FileUtil.toCanonicalPath(pluginPath));
    }

    String optionsString = myState.additionalCompilerOptions.trim();
    if (!optionsString.isEmpty()) {
      String[] options = optionsString.split("\\s+");
      list.addAll(Arrays.asList(options));
    }

    return list.toArray(new String[list.size()]);
  }

  @NotNull
  @Override
  public ProjectSettingsImpl createCopy() {
    return new ProjectSettingsImpl(XmlSerializerUtil.createCopy(myState));
  }

  @Override
  public void applyChanges(@NotNull ProjectSettingsImpl facetSettings) {
    // do nothing
  }

  public static class State {
    public IncrementalityType incrementalityType = IncrementalityType.IDEA;

    public CompileOrder compileOrder = CompileOrder.Mixed;

    public boolean warnings = true;

    public boolean deprecationWarnings;

    public boolean uncheckedWarnings;

    public boolean optimiseBytecode;

    public boolean explainTypeErrors;

    public boolean continuations;

    public DebuggingInfoLevel debuggingInfoLevel = DebuggingInfoLevel.Vars;

    public String additionalCompilerOptions = "";

    public String[] plugins = new String[] {};
  }
}
