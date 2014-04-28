package org.jetbrains.jps.incremental.scala;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.cmdline.ProjectDescriptor;
import org.jetbrains.jps.incremental.scala.model.*;
import org.jetbrains.jps.model.JpsGlobal;
import org.jetbrains.jps.model.ex.JpsElementChildRoleBase;
import org.jetbrains.jps.model.library.JpsLibrary;
import org.jetbrains.jps.model.module.JpsDependencyElement;
import org.jetbrains.jps.model.module.JpsLibraryDependency;
import org.jetbrains.jps.model.module.JpsModule;

import java.util.ArrayList;
import java.util.Collection;

/**
 * @author Pavel Fatin
 */
public class SettingsManager {
  public static final JpsElementChildRoleBase<GlobalSettings> GLOBAL_SETTINGS_ROLE = JpsElementChildRoleBase.create("scala global settings");
  public static final JpsElementChildRoleBase<ProjectSettings> PROJECT_SETTINGS_ROLE = JpsElementChildRoleBase.create("scala project settings");
  public static final JpsElementChildRoleBase<LibrarySettings> LIBRARY_SETTINGS_ROLE = JpsElementChildRoleBase.create("scala library settings");

  public static GlobalSettings getGlobalSettings(JpsGlobal global) {
    GlobalSettings settings = global.getContainer().getChild(GLOBAL_SETTINGS_ROLE);
    return settings == null ? GlobalSettingsImpl.DEFAULT : settings;
  }

  public static void setGlobalSettings(JpsGlobal global, GlobalSettings settings) {
    global.getContainer().setChild(GLOBAL_SETTINGS_ROLE, settings);
  }

  public static ProjectSettings getProjectSettings(JpsProject project) {
    return project.getContainer().getChild(PROJECT_SETTINGS_ROLE);
  }

  public static void setProjectSettings(JpsProject project, ProjectSettings settings) {
    project.getContainer().setChild(PROJECT_SETTINGS_ROLE, settings);
  }

  public static boolean hasScalaSdk(JpsModule module) {
    return getScalaSdk(module) != null;
  }

  @Nullable
  public static JpsLibrary getScalaSdk(JpsModule module) {
    for (JpsLibrary library : libraryDependenciesIn(module)) {
      if (library.getType() == ScalaLibraryType.getInstance()) {
        return library;
      }
    }
    return null;
  }

  public static Collection<JpsLibrary> libraryDependenciesIn(JpsModule module) {
    Collection<JpsLibrary> libraries = new ArrayList<JpsLibrary>();
    for (JpsDependencyElement element : module.getDependenciesList().getDependencies()) {
      if (element instanceof JpsLibraryDependency) {
        JpsLibrary library = ((JpsLibraryDependency) element).getLibrary();
        libraries.add(library);
      }
    }
    return libraries;
  }

  public static ProjectSettings getProjectSettings(ProjectDescriptor projectDescriptor) {
    return new ProjectSetingsImpl(projectDescriptor); //todo use real settings
  }
}
