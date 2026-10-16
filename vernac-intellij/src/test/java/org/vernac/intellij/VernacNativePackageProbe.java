// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.core.*;
import com.intellij.lang.*;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.ResolveScopeManager;
import com.intellij.psi.impl.file.PsiPackageImpl;
import com.intellij.psi.search.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightVirtualFile;

/**
 * Core SDK PSI harness, isolated from the test runner's application singleton. The caller scope is
 * supplied explicitly; this does not run MultiHostInjector or a popup. Production generators, live
 * Java PSI, finder and package lookup are exercised.
 */
public final class VernacNativePackageProbe {
  static class Environment extends JavaCoreProjectEnvironment {
    GlobalSearchScope live;

    Environment(Disposable parent, CoreApplicationEnvironment app) {
      super(parent, app);
    }

    @Override
    protected com.intellij.openapi.roots.FileIndexFacade createFileIndexFacade() {
      return new com.intellij.mock.MockFileIndexFacade(getProject()) {
        @Override
        public boolean isInSource(VirtualFile file) {
          // The core default treats every file as source. Live Java memory files
          // are not physical Java source roots in the installed project model.
          return file.isInLocalFileSystem() && super.isInSource(file);
        }
      };
    }

    @Override
    protected ResolveScopeManager createResolveScopeManager(PsiManager manager) {
      return new ResolveScopeManager() {
        public GlobalSearchScope getResolveScope(PsiElement element) {
          return live == null ? GlobalSearchScope.allScope(manager.getProject()) : live;
        }

        public GlobalSearchScope getDefaultResolveScope(VirtualFile file) {
          return GlobalSearchScope.allScope(manager.getProject());
        }

        public GlobalSearchScope getUseScope(PsiElement element) {
          return GlobalSearchScope.allScope(manager.getProject());
        }
      };
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    boolean fixed = Boolean.parseBoolean(args[0]);
    var disposable = Disposer.newDisposable();
    try {
      var app = new JavaCoreApplicationEnvironment(disposable);
      CoreApplicationEnvironment.registerApplicationExtensionPoint(
          MetaLanguage.EP_NAME, MetaLanguage.class);
      app.addExplicitExtension(
          com.intellij.platform.syntax.psi.ElementTypeConverters.getInstance(),
          JavaLanguage.INSTANCE,
          new com.intellij.platform.syntax.psi.CommonElementTypeConverterFactory());
      CoreApplicationEnvironment.registerApplicationExtensionPoint(
          ExtensionPointName.create("com.intellij.lang.psiAugmentProvider"),
          com.intellij.psi.augment.PsiAugmentProvider.class);
      CoreApplicationEnvironment.registerApplicationExtensionPoint(
          com.intellij.codeInsight.JavaExpressionTypeNullabilityPatcher.EP_NAME,
          com.intellij.codeInsight.JavaExpressionTypeNullabilityPatcher.class);
      app.registerApplicationService(
          com.intellij.psi.util.JavaClassSupers.class,
          new com.intellij.psi.impl.JavaClassSupersImpl());
      var env = new Environment(disposable, app);
      var project = env.getProject();
      // Parameter-type analysis consults this service even when the fixture has
      // no external annotation roots. Keep native PSI/type processing intact.
      project.registerService(com.intellij.codeInsight.ExternalAnnotationsManager.class,
          new com.intellij.codeInsight.BaseExternalAnnotationsManager(PsiManager.getInstance(project)) {
            protected boolean hasAnyAnnotationsRoots() { return false; }
            protected java.util.List<VirtualFile> getExternalAnnotationsRoots(VirtualFile file) {
              return java.util.List.of();
            }
            public boolean hasConfiguredAnnotationRoot(PsiModifierListOwner owner) { return false; }
          });
      var root =
          java.nio.file.Files.createDirectories(
              java.nio.file.Path.of(args[1]).resolve("src/main/vernac"));
      var source = root.resolve("sample/model.vernac");
      java.nio.file.Files.createDirectories(source.getParent());
      java.nio.file.Files.writeString(source, "namespace sample; value EditorTest(int); id TaskId list TaskIds; value State = NEW | DONE set States; value Label(String) list Labels, set UniqueLabels; "
          + "entity Entry[TaskId](mut Label) behavior {read Label caption(){return self.label();} modify void rename(Label label){self.label(label);}} list Entries, set SelectedEntries; "
          + "aggregate Folder[TaskId](mut Entries) behavior {modify void replace(Entries entries){self.entries(entries);}} list Folders; "
          + "event Changed(Label); port Notices {void send(Label);} "
          + "service Policy uses Notices behavior {public Label choose(Label label){return label;}} "
          + "usecase Choose(Label) returns (Label) uses Policy behavior {execute {return Result.of(label);}} "
          + "repository FolderRepository for Folder {} synchronous listener Alert on Changed uses FolderRepository behavior {execute {}} "
          + "adapter LocalNotices implements Notices {schema Payload(String text); behavior {public void send(Label label){}}}");
      var service = new VernacLiveValues(project);
      project.registerService(VernacLiveValues.class, service);
      // A physical package may enter the facade cache before any live snapshot.
      var javaRoot = java.nio.file.Path.of(args[1]).resolve("java-sources");
      java.nio.file.Files.createDirectories(javaRoot.resolve("early/domain"));
      java.nio.file.Files.writeString(
          javaRoot.resolve("early/domain/Existing.java"),
          "package early.domain; public class Existing {}");
      env.addSourcesToClasspath(app.getLocalFileSystem().findFileByPath(javaRoot.toString()));
      check(service.knownSnapshots().isEmpty(), "cold package lookup has no editor model");
      var early = new VernacLiveValueFinder(project).findPackage("early.domain");
      check(
          early instanceof VernacLivePackage,
          "physical domain packages are wrapped before snapshot loading");
      check(
          new VernacLiveValueFinder(project).findPackage("missing.domain") == null,
          "missing packages are not invented");
      check(service.knownSnapshots().isEmpty(), "package lookup does not load models");
      var snapshot = service.snapshot(root);
      assertCopiedHostScope(app, env, source);

      var factory = PsiFileFactory.getInstance(project);
      var light =
          new LightVirtualFile(
              "EditorTest.java",
              JavaLanguage.INSTANCE,
              snapshot.api.values().get("sample.domain.EditorTest").javaSource());
      var original = app.getLocalFileSystem().findFileByPath(source.toString());
      light.setOriginalFile(original);
      light.setWritable(false);
      var provider = new SingleRootFileViewProvider(PsiManager.getInstance(project), light, false);
      var liveJava = new VernacLiveJavaFile(provider, original, snapshot);
      provider.forceCachedPsi(liveJava);
      var type = liveJava.getClasses()[0];
      var secondLight =
          new LightVirtualFile(
              "EditorTest.java",
              JavaLanguage.INSTANCE,
              snapshot.api.values().get("sample.domain.EditorTest").javaSource());
      secondLight.setOriginalFile(original);
      secondLight.setWritable(false);
      var secondProvider =
          new SingleRootFileViewProvider(PsiManager.getInstance(project), secondLight, false);
      var secondJava = new VernacLiveJavaFile(secondProvider, original, snapshot);
      secondProvider.forceCachedPsi(secondJava);
      if (!fixed) {
        liveJava.putUserData(PsiFileFactory.ORIGINAL_FILE, null);
        secondJava.putUserData(PsiFileFactory.ORIGINAL_FILE, null);
      }
      check(
          PsiManager.getInstance(project).areElementsEquivalent(type, secondJava.getClasses()[0])
              == fixed,
          "class identity survives Java view recreation");
      check(liveJava.getOriginalFile() == liveJava, "Java context is not replaced by DSL PSI");
      snapshot.classes.put("sample.domain.EditorTest", type);
      for (var entry : snapshot.api.values().entrySet()) {
        if (entry.getKey().equals("sample.domain.EditorTest")) continue;
        var apiLight = new LightVirtualFile(entry.getKey() + ".java", JavaLanguage.INSTANCE,
            entry.getValue().javaSource());
        apiLight.setOriginalFile(original);
        var apiProvider = new SingleRootFileViewProvider(PsiManager.getInstance(project), apiLight, false);
        var apiFile = new VernacLiveJavaFile(apiProvider, original, snapshot);
        apiProvider.forceCachedPsi(apiFile);
        snapshot.classes.put(entry.getKey(), apiFile.getClasses()[0]);
      }
      env.live =
          new VernacLiveScope(
              GlobalSearchScope.allScope(project), new LightVirtualFile("model.vernac"), snapshot);
      var production = new VernacLiveValueFinder(project);
      var finder =
          new PsiElementFinder() {
            public PsiClass findClass(String name, GlobalSearchScope scope) {
              return production.findClass(name, scope);
            }

            public PsiClass[] findClasses(String name, GlobalSearchScope scope) {
              return production.findClasses(name, scope);
            }

            public PsiClass[] getClasses(PsiPackage pkg, GlobalSearchScope scope) {
              return production.getClasses(pkg, scope);
            }

            public java.util.Set<String> getClassNames(PsiPackage pkg, GlobalSearchScope scope) {
              return production.getClassNames(pkg, scope);
            }

            public PsiPackage findPackage(String name) {
              var found = production.findPackage(name);
              return found == null || fixed
                  ? found
                  : new PsiPackageImpl(PsiManager.getInstance(project), name);
            }
          };
      CoreApplicationEnvironment.registerExtensionPoint(
          project.getExtensionArea(),
          "com.intellij.jvm.elementProvider",
          com.intellij.lang.jvm.facade.JvmElementProvider.class);
      CoreApplicationEnvironment.registerExtensionPoint(
          project.getExtensionArea(), PsiElementFinder.EP.getName(), PsiElementFinder.class);
      env.addProjectExtension(ExtensionPointName.create(PsiElementFinder.EP.getName()), finder);
      check(
          JavaPsiFacade.getInstance(project).findClass("sample.domain.EditorTest", env.live)
              == type,
          "direct scoped lookup");
      check(
          JavaPsiFacade.getInstance(project)
                  .findClass("sample.domain.EditorTest", GlobalSearchScope.allScope(project))
              == null,
          "no publication to ordinary Java");
      var file =
          (PsiJavaFile)
              factory.createFileFromText(
                  "Use.java",
                  JavaLanguage.INSTANCE,
                  "package sample.usecase; import sample.domain.EditorTest; class Use { void run(){"
                      + " EditorTest.of(0); sample.domain.EditorTest.of(0); } }");
      var pkg = JavaPsiFacade.getInstance(project).findPackage("sample.domain");
      if (fixed) {
        assertValueSideReferences(factory, snapshot);
        assertMutableReferences(factory, snapshot);
        check(snapshot.find("sample.domain.Changed").findMethodsByName("create", false).length > 0, "live event factory");
        check(snapshot.find("sample.domain.Notices").findMethodsByName("send", false).length == 1, "live port operation");
        check(snapshot.find("sample.domain.access.PolicyAccess").findMethodsByName("notices", false).length == 1, "live service self dependency");
        check(snapshot.find("sample.usecase.Choose").findMethodsByName("execute", false).length == 1, "live usecase execute");
        check(snapshot.find("sample.usecase.access.ChooseAccess").findMethodsByName("policy", false).length == 1, "live usecase self dependency");
        check(snapshot.find("sample.usecase.Choose.Result").isRecord(), "live nested result record");
        check(snapshot.find("sample.domain.FolderRepository") != null,
            "repository API: " + new org.vernac.compiler.pipeline.ProjectTypeResolver().resolveForAnalysis(snapshot.model).diagnostics());
        check(snapshot.find("sample.domain.FolderRepository").findMethodsByName("save", false).length == 1, "live repository save");
        check(snapshot.find("sample.adapter.inbound.event.access.AlertAccess").findMethodsByName("folderRepository", false).length == 1, "live listener self dependency");
        check(snapshot.find("sample.adapter.outbound.access.LocalNoticesAccess").findMethodsByName("invocation", false).length == 1, "live adapter invocation");
        check(snapshot.find("sample.adapter.outbound.dto.LocalNoticesDtos.Payload").isRecord(), "live nested DTO record");
      }

      check(
          (pkg.findClassByShortName("EditorTest", env.live).length > 0) == fixed,
          "smart package lookup");
      check(
          pkg.findClassByShortName("EditorTest", GlobalSearchScope.allScope(project)).length == 0,
          "package lookup must not publish live types to ordinary Java");
      String originalText = file.getText();
      check(
          file.getImportList().getImportStatements()[0].resolve() == type,
          "synthetic explicit import resolves");
      for (var call : PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression.class))
        check(
            call.resolveMethod() != null && call.resolveMethod().getName().equals("of"),
            "factory reference resolves");
      com.intellij.openapi.project.DumbService.getInstance(project)
          .runWithAlternativeResolveEnabled(
              () -> {
                check(
                    (pkg.findClassByShortName("EditorTest", env.live).length > 0) == fixed,
                    "alternative package lookup");
                check(
                    pkg.hasClassWithShortName("EditorTest", env.live) == fixed,
                    "alternative package presence");
                var partial =
                    factory.createFileFromText(
                        "Partial.java",
                        JavaLanguage.INSTANCE,
                        "package sample.usecase; import sample.domain.EditorTest; class Partial {"
                            + " void run(){ EditorTest.o; sample.domain.EditorTest.o; } }");
                for (var ref :
                    PsiTreeUtil.findChildrenOfType(partial, PsiReferenceExpression.class))
                  if ("o".equals(ref.getReferenceName())) {
                    check(
                        ((PsiReferenceExpression) ref.getQualifierExpression()).resolve() == type,
                        "member qualifier resolves");
                    var names = new java.util.TreeSet<String>();
                    ref.processVariants(
                        new com.intellij.psi.scope.BaseScopeProcessor() {
                          public boolean execute(PsiElement element, ResolveState state) {
                            if (element instanceof PsiMethod m
                                && m.hasModifierProperty(PsiModifier.STATIC)
                                && m.hasModifierProperty(PsiModifier.PUBLIC))
                              names.add(m.getName());
                            return true;
                          }
                        });
                    check(
                        names.contains("of"),
                        "native member processor exposes public static factory");
                  }
              });
      check(file.getText().equals(originalText), "resolution does not change imports or user text");
      check(!java.nio.file.Files.exists(root.resolve("target")), "no generated output");
      System.out.println(
          fixed ? "NATIVE_PACKAGE_FIXED_OK" : "NATIVE_PACKAGE_LEGACY_MISS_REPRODUCED");
    } finally {
      Disposer.dispose(disposable);
    }
  }

  private static void assertCopiedHostScope(
      JavaCoreApplicationEnvironment app, Environment env, java.nio.file.Path source) {
    app.registerFileType(VernacFileType.INSTANCE, "vernac");
    app.registerParserDefinition(new VernacParserDefinition());
    app.addExplicitExtension(
        com.intellij.platform.syntax.psi.ElementTypeConverters.getInstance(),
        VernacLanguage.INSTANCE,
        new com.intellij.platform.syntax.psi.CommonElementTypeConverterFactory());
    var virtual = app.getLocalFileSystem().findFileByPath(source.toString());
    var provider =
        new SingleRootFileViewProvider(PsiManager.getInstance(env.getProject()), virtual, true);
    var host = new VernacPsiFile(provider);
    provider.forceCachedPsi(host);
    var copy = (VernacPsiFile) host.copy();
    check(
        copy != host && !copy.getViewProvider().getVirtualFile().isInLocalFileSystem(),
        "completion host copy is nonphysical");
    check(copy.getOriginalFile() == host, "copy retains the original local model");
    var scope = VernacLiveScope.find(copy.getFileResolveScope());
    check(scope != null, "copied host retains the live scope");
    check(
        scope.completionClasses().contains("sample.domain.EditorTest"),
        "copied host retains visible types");
    check(
        scope.snapshot == VernacLiveScope.find(host.getFileResolveScope()).snapshot,
        "copy uses the current model rather than a separate stale snapshot");
  }

  private static void assertValueSideReferences(PsiFileFactory factory, VernacLiveValues.Snapshot snapshot) {
    var file = factory.createFileFromText("ValueSide.java", JavaLanguage.INSTANCE,
        "package sample.usecase; import sample.domain.*; class ValueSide { void run(){"
        + " TaskId.create(); TaskIds.empty(); State.NEW.name(); States.empty(); Labels.empty(); UniqueLabels.empty(); } }");
    var references = PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression.class);
    for (String name : java.util.List.of("TaskId", "TaskIds", "State", "States", "Labels", "UniqueLabels")) {
      var reference = references.stream().filter(r -> name.equals(r.getText())).findFirst().orElseThrow();
      check(reference.resolve() == snapshot.find("sample.domain." + name), "live value-side reference " + name);
    }
    check(references.stream().filter(r -> r.getText().equals("State.NEW")).findFirst().orElseThrow()
        .resolve() instanceof PsiEnumConstant, "enum constant resolves before generation");
    for (var expression : java.util.Map.of("TaskId.cre", "create", "Labels.em", "empty",
        "TaskIds.em", "empty", "UniqueLabels.em", "empty", "State.N", "NEW").entrySet()) {
      var incomplete = factory.createFileFromText("Incomplete.java", JavaLanguage.INSTANCE,
          "package sample.usecase; import sample.domain.*; class Incomplete { void run(){" + expression.getKey() + ";} }");
      var ref = PsiTreeUtil.findChildrenOfType(incomplete, PsiReferenceExpression.class).stream()
          .filter(r -> r.getText().equals(expression.getKey())).findFirst().orElseThrow();
      var names = new java.util.HashSet<String>();
      ref.processVariants(new com.intellij.psi.scope.BaseScopeProcessor() {
        public boolean execute(PsiElement element, ResolveState state) {
          if (element instanceof PsiNamedElement named) names.add(named.getName());
          return true;
        }
      });
      check(names.contains(expression.getValue()), "native member discovery " + expression.getKey());
    }
  }

  private static void assertMutableReferences(PsiFileFactory factory, VernacLiveValues.Snapshot snapshot) {
    var file = factory.createFileFromText("Mutable.java", JavaLanguage.INSTANCE,
        "package sample.domain; import sample.domain.access.*; class Mutable {"
        + " static void read(EntryRead self){self.ca;}"
        + " static void modify(EntryAccess self){self.la;}"
        + " static void inspect(FolderRead self){self.up;}"
        + " static void start(){ Entry.cre; Folder.cre; Entries.em; Entries.empty().by; } }");
    var references = PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression.class);
    var read = references.stream().filter(r -> r.getText().equals("self.ca")).findFirst().orElseThrow();
    var modify = references.stream().filter(r -> r.getText().equals("self.la")).findFirst().orElseThrow();
    var readNames = memberNames(read);
    var modifyNames = memberNames(modify);
    check(readNames.contains("caption/0") && readNames.contains("label/0"), "read getters and behaviors");
    check(!readNames.contains("label/1") && !readNames.contains("rename/1") && !readNames.contains("emit/1"),
        "read context has no modifying API");
    check(modifyNames.contains("label/0") && modifyNames.contains("label/1")
        && modifyNames.contains("rename/1") && modifyNames.contains("emit/1"), "access inherits read and write");
    for (var expression : java.util.Map.of("self.up", "updatedAt/0", "Entry.cre", "create/1",
        "Folder.cre", "create/1", "Entries.em", "empty/0", "Entries.empty().by", "by/1").entrySet()) {
      var ref = references.stream().filter(r -> r.getText().equals(expression.getKey())).findFirst().orElseThrow();
      check(memberNames(ref).contains(expression.getValue()), "mutable member discovery " + expression.getKey());
    }
    var access = snapshot.find("sample.domain.access.EntryAccess");
    check(access != null && access.isInterface(), "generated access view is an interface");
    check(java.util.Arrays.stream(snapshot.find("sample.domain.Entry").getInterfaces())
        .noneMatch(t -> "sample.domain.access.EntryAccess".equals(t.getQualifiedName())),
        "entity does not implement its access interface");
  }

  private static java.util.Set<String> memberNames(PsiReferenceExpression ref) {
    var names = new java.util.HashSet<String>();
    ref.processVariants(new com.intellij.psi.scope.BaseScopeProcessor() {
      public boolean execute(PsiElement element, ResolveState state) {
        if (element instanceof PsiMethod method)
          names.add(method.getName()+"/"+method.getParameterList().getParametersCount());
        return true;
      }
    });
    return names;
  }
}
