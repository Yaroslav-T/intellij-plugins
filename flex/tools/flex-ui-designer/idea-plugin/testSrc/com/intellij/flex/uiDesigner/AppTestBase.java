package com.intellij.flex.uiDesigner;

import com.intellij.flex.uiDesigner.libraries.LibraryManager;
import com.intellij.flex.uiDesigner.mxml.ProjectComponentReferenceCounter;
import com.intellij.lang.javascript.JSTestUtils;
import com.intellij.lang.javascript.flex.projectStructure.model.ModifiableFlexIdeBuildConfiguration;
import com.intellij.lang.javascript.flex.projectStructure.model.OutputType;
import com.intellij.lang.javascript.flex.projectStructure.model.TargetPlatform;
import com.intellij.lang.javascript.flex.projectStructure.model.impl.Factory;
import com.intellij.lang.javascript.flex.projectStructure.options.BuildConfigurationNature;
import com.intellij.lang.javascript.flex.sdk.FlexSdkType2;
import com.intellij.lang.javascript.flex.sdk.FlexSdkUtils;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkModificator;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.peer.PeerFactory;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.Consumer;
import junit.framework.AssertionFailedError;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

abstract class AppTestBase extends FlashUIDesignerBaseTestCase {
  protected TestClient client;
  protected TestSocketInputHandler socketInputHandler;

  protected String flexSdkRootPath;
  protected final List<Pair<VirtualFile, VirtualFile>> libs = new ArrayList<Pair<VirtualFile, VirtualFile>>();

  protected String getSourceBasePath() {
    return "common";
  }

  private VirtualFile testDir;
  protected final VirtualFile getTestDir() {
    if (testDir == null) {
      testDir = LocalFileSystem.getInstance().findFileByPath(DesignerTests.getTestDataPath() + "/src/" + getSourceBasePath());
    }

    return testDir;
  }

  protected final VirtualFile getSource(String relativePath) {
    VirtualFile file = getTestDir().findFileByRelativePath(relativePath);
    assertNotNull(file);
    return file;
  }

  @Override
  protected void setUpJdk() {
    final String flexVersion = getFlexVersion();
    flexSdkRootPath = DesignerTests.getTestDataPath() + "/lib/flex-sdk/" + flexVersion;

    Flex annotation = getFlexAnnotation();
    doSetupFlexSdk(myModule, flexSdkRootPath, annotation == null ? TargetPlatform.Desktop : annotation.platform(), flexVersion);
  }

  private void doSetupFlexSdk(final Module module, final String flexSdkRootPath, final TargetPlatform targetPlatform, final String sdkVersion) {
    final AccessToken token = WriteAction.start();
    try {
      final String sdkName = generateSdkName(sdkVersion);
      Sdk sdk = ProjectJdkTable.getInstance().findJdk(sdkName);
      if (sdk == null) {
        final FlexSdkType2 sdkType = FlexSdkType2.getInstance();
        sdk = PeerFactory.getInstance().createProjectJdk(sdkType.suggestSdkName(null, flexSdkRootPath), "", flexSdkRootPath, sdkType);
        assert sdk != null;
        // we have own logic about setupSdkPaths
        //sdkType.setupSdkPaths(sdk);
        ProjectJdkTable.getInstance().addJdk(sdk);

        final Sdk finalSdk = sdk;
        Disposer.register(ApplicationManager.getApplication(), new Disposable() {
          @Override
          public void dispose() {
            final AccessToken t = WriteAction.start();
            try {
              ProjectJdkTable.getInstance().removeJdk(finalSdk);
            }
            finally {
              t.finish();
            }
          }
        });
      }

      final SdkModificator modificator = sdk.getSdkModificator();
      modificator.setName(sdkName);
      modificator.setVersionString(FlexSdkUtils.readFlexSdkVersion(sdk.getHomeDirectory()));
      modifySdk(sdk, modificator);
      modificator.commitChanges();

      final Sdk finalSdk = sdk;
      JSTestUtils.modifyBuildConfiguration(module, new Consumer<ModifiableFlexIdeBuildConfiguration>() {
        public void consume(final ModifiableFlexIdeBuildConfiguration bc) {
          bc.setNature(new BuildConfigurationNature(targetPlatform, false, getOutputType()));
          bc.getDependencies().setSdkEntry(Factory.createSdkEntry(finalSdk.getName()));
        }
      });
    }
    finally {
      token.finish();
    }
  }

  protected OutputType getOutputType() {
    return OutputType.Application;
  }

  protected String generateSdkName(String sdkVersion) {
    return "test-" + sdkVersion;
  }
  
  protected void modifySdk(Sdk sdk, SdkModificator sdkModificator) {
    final VirtualFile sdkHome = sdk.getHomeDirectory();
    assert sdkHome != null;
    final VirtualFile frameworksDir = sdkHome.findChild("frameworks");
    assert frameworksDir != null;

    //VirtualFile frameworkRB = frameworksDir.findFileByRelativePath("libs/framework_rb.swc");
    //if (frameworkRB != null) {
    //  sdkModificator.addRoot(frameworkRB, OrderRootType.CLASSES);
    //}

    sdkModificator.addRoot(DesignerTests.getVFile("lib/playerglobal"), OrderRootType.CLASSES);
    FlexSdkUtils.addFlexSdkSwcRoots(sdkModificator, frameworksDir);
  }

  protected void addLibrary(SdkModificator sdkModificator, String path) {
    if (path.charAt(0) != '/') {
      path = DesignerTests.getTestDataPath() + "/lib/" + path;
    }

    VirtualFile virtualFile = DesignerTests.getVFile(path);
    VirtualFile jarFile = JarFileSystem.getInstance().getJarRootForLocalFile(virtualFile);
    assert jarFile != null;

    
    libs.add(new Pair<VirtualFile, VirtualFile>(virtualFile, jarFile));
    sdkModificator.addRoot(jarFile, OrderRootType.CLASSES);
  }

  protected String getFlexVersion() {
    try {
      Flex annotation = getClass().getMethod(getName()).getAnnotation(Flex.class);
      if (annotation == null || annotation.version().isEmpty()) {
        annotation = getClass().getAnnotation(Flex.class);
      }

      assert annotation != null && !annotation.version().isEmpty();
      return annotation.version();
    }
    catch (NoSuchMethodException e) {
      throw new AssertionFailedError(e.getMessage());
    }
  }

  private Flex getFlexAnnotation() {
    try {
      return getClass().getMethod(getName()).getAnnotation(Flex.class);
    }
    catch (NoSuchMethodException e) {
      throw new AssertionFailedError(e.getMessage());
    }
  }
  
  protected boolean isRequireLocalStyleHolder() {
    Flex annotation = getFlexAnnotation();
    return annotation != null && annotation.requireLocalStyleHolder();
  }

  protected void assertAfterInitLibrarySets(List<XmlFile> unregisteredDocumentReferences) throws IOException {
  }

  protected VirtualFile[] configureByFiles(String... paths) throws Exception {
    final VirtualFile[] files = new VirtualFile[paths.length];
    for (int i = 0; i < paths.length; i++) {
      VirtualFile file = getSource(paths[i]);
      assertNotNull(file);
      files[i] = file;
    }
    return configureByFiles(null, files, null);
  }

  protected VirtualFile[] configureByFiles(@Nullable VirtualFile rawProjectRoot,
                                           VirtualFile[] files,
                                           @Nullable VirtualFile[] auxiliaryFiles) throws Exception {
    final VirtualFile[] sourceFiles = super.configureByFiles(rawProjectRoot, files, auxiliaryFiles);
    launchAndInitializeApplication();
    return sourceFiles;
  }

  private void launchAndInitializeApplication() throws Exception {
    if (DesignerApplicationManager.getInstance().isApplicationClosed()) {
      changeServicesImplementation();

      new DesignerApplicationLauncher(myModule, new DesignerApplicationLauncher.PostTask() {
        @Override
        public boolean run(Module module,
                           ProjectComponentReferenceCounter projectComponentReferenceCounter,
                           ProgressIndicator indicator,
                           ProblemsHolder problemsHolder) {
          assertTrue(DocumentProblemManager.getInstance().toString(problemsHolder.getProblems()), problemsHolder.isEmpty());

          client = (TestClient)Client.getInstance();
          client.flush();

          try {
            assertAfterInitLibrarySets(projectComponentReferenceCounter.unregistered);
          }
          catch (IOException e) {
            throw new AssertionError(e);
          }

          return true;
        }
      }).run(new EmptyProgressIndicator());
    }
    else {
      client = (TestClient)Client.getInstance();
      final ProblemsHolder problemsHolder = new ProblemsHolder();
      ProjectComponentReferenceCounter projectComponentReferenceCounter =
        LibraryManager.getInstance().registerModule(myModule, problemsHolder, isRequireLocalStyleHolder());
      assertTrue(problemsHolder.isEmpty());
      assertAfterInitLibrarySets(projectComponentReferenceCounter.unregistered);
    }

    socketInputHandler = (TestSocketInputHandler)SocketInputHandler.getInstance();
    applicationLaunchedAndInitialized();
  }

  protected abstract void changeServicesImplementation();

  protected void applicationLaunchedAndInitialized() {
  }

  @Override
  protected void tearDown() throws Exception {
    if (client != null) {
      try {
        client.closeProject(myProject);
      }
      finally {
        super.tearDown();
      }
    }
  }
}