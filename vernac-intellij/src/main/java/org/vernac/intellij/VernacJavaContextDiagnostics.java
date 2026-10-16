// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** Explicit, read-only inspection of the real installed Java/host context. */
final class VernacJavaContextDiagnostics {
    private VernacJavaContextDiagnostics() {}

    static String report(Project project, PsiFile host, int offset) {
        StringBuilder out = new StringBuilder("Vernac embedded Java context diagnostics v4\n");
        try {
        out.append("No source bodies, environment variables or credentials are collected.\n")
                .append("The report includes type names and IDE implementation class names.\n\n");
        line(out, "host.psi", () -> host.getClass().getName());
        line(out, "host.language", () -> host.getLanguage().getID());
        var virtual = host.getViewProvider().getVirtualFile();
        line(out, "host.local", () -> virtual.isInLocalFileSystem());
        line(out, "host.offset", () -> offset);
        line(out, "finders.registered", () -> PsiElementFinder.EP.getExtensions(project).stream()
                .map(x -> x.getClass().getName()).filter(n -> n.startsWith("org.vernac.")).toList());
        line(out, "shortNames.registered", () -> PsiShortNamesCache.EP_NAME.getExtensionList(project).stream()
                .map(x -> x.getClass().getName()).filter(n -> n.startsWith("org.vernac.")).toList());
        GlobalSearchScope hostScope = scope(out, "host", host);
        if (!virtual.isInLocalFileSystem()) return out.toString();
        var root = VernacNamespaces.sourceRoot(virtual.toNioPath().getParent());
        line(out, "conventionalSourceRoot.found", root::isPresent);
        if (root.isEmpty()) return out.toString();
        var service = project.getService(VernacLiveValues.class);
        line(out, "snapshots.alreadyLoaded", () -> service.knownSnapshots().size());
        var snapshot = service.snapshot(root.get());
        line(out, "snapshot.modelAvailable", () -> snapshot.model != null);
        line(out, "snapshot.sourceCount", () -> snapshot.model == null ? 0 : snapshot.model.sources().size());
        line(out, "snapshot.declaredDomainTypes", () -> sorted(snapshot.api.valueNames()));
        line(out, "snapshot.publishedJavaApis", () -> sorted(snapshot.api.values().keySet()));
        var forced = new VernacLiveScope(hostScope == null ? GlobalSearchScope.EMPTY_SCOPE : hostScope,
                virtual, snapshot);
        line(out, "snapshot.visibleCandidates", forced::completionClasses);

        PsiElement injected = InjectedLanguageManager.getInstance(project)
                .findInjectedElementAt(host, Math.min(offset, Math.max(0, host.getTextLength() - 1)));
        line(out, "injected.element", () -> injected == null ? "NONE" : injected.getClass().getName());
        GlobalSearchScope javaScope = null;
        if (injected != null) {
            line(out, "injected.language", () -> injected.getLanguage().getID());
            javaScope = scope(out, "injected", injected);
            var javaFile = injected.getContainingFile();
            if (javaFile instanceof PsiJavaFile java && java.getImportList() != null)
                line(out, "injected.imports", () -> Arrays.stream(java.getImportList().getImportStatements())
                        .map(PsiImportStatement::getQualifiedName).filter(Objects::nonNull).limit(50).toList());
            var reference = com.intellij.psi.util.PsiTreeUtil.getParentOfType(injected, PsiReferenceExpression.class, false);
            if (reference != null) {
                line(out, "caret.referenceName", reference::getReferenceName);
                line(out, "caret.referenceTarget", () -> target(reference.resolve()));
                if (reference.getQualifierExpression() instanceof PsiReferenceExpression qualifier)
                    line(out, "caret.qualifierTarget", () -> target(qualifier.resolve()));
            }
            var context = javaFile.getContext();
            line(out, "injected.contextPsi", () -> context == null ? "NONE" : context.getContainingFile().getClass().getName());
            scope(out, "injected.originalFile", injected.getContainingFile().getOriginalFile());
            // This is a plain PSI-copy probe, not a simulated invocation of Java completion.
            var copy = (PsiFile) javaFile.copy();
            scope(out, "injected.psiCopy", copy);
            var copyContext = copy.getContext();
            line(out, "injected.psiCopy.contextPsi", () -> copyContext == null ? "NONE" : copyContext.getContainingFile().getClass().getName());
            if (copyContext != null) {
                scope(out, "injected.psiCopy.context", copyContext);
                line(out, "injected.psiCopy.originalHostLocal", () -> copyContext.getContainingFile()
                        .getOriginalFile().getViewProvider().getVirtualFile().isInLocalFileSystem());
            }
        }
        GlobalSearchScope actual = javaScope;
        for (String name : sorted(snapshot.api.valueNames()).stream().limit(40).toList()) {
            out.append("\nTYPE ").append(name).append('\n');
            line(out, "  api.published", () -> snapshot.api.values().containsKey(name));
            line(out, "  directLiveClass", () -> describe(snapshot.find(name)));
            line(out, "  originMetadata", () -> {
                var type = snapshot.find(name);
                var origin = type == null ? null : type.getContainingFile().getUserData(PsiFileFactory.ORIGINAL_FILE);
                return origin == null ? "NONE" : origin.getClass().getName();
            });
            line(out, "  factoryMethods", () -> snapshot.find(name) == null ? "NOT_PUBLISHED" :
                    Arrays.stream(snapshot.find(name).getMethods())
                            .filter(m -> m.hasModifierProperty(PsiModifier.PUBLIC) && m.hasModifierProperty(PsiModifier.STATIC))
                            .map(VernacJavaContextDiagnostics::target).toList());
            line(out, "  package.implementation", () -> {
                var pkg = JavaPsiFacade.getInstance(project).findPackage(name.substring(0, name.lastIndexOf('.')));
                return pkg == null ? "NOT_FOUND" : pkg.getClass().getName();
            });
            line(out, "  package.injectedScope", () -> packageLookup(project, name, actual, false));
            line(out, "  package.alternativeResolve", () -> packageLookup(project, name, actual, true));
            line(out, "  facade.hostScope", () -> hostScope == null ? "NO_SCOPE" :
                    describe(JavaPsiFacade.getInstance(project).findClass(name, hostScope)));
            line(out, "  facade.forcedLiveScope", () ->
                    describe(JavaPsiFacade.getInstance(project).findClass(name, forced)));
            line(out, "  facade.injectedScope", () -> actual == null ? "NO_SCOPE" :
                    describe(JavaPsiFacade.getInstance(project).findClass(name, actual)));
            line(out, "  shortNames.injectedScope", () -> actual == null ? "NO_SCOPE" :
                    Arrays.stream(PsiShortNamesCache.getInstance(project)
                            .getClassesByName(name.substring(name.lastIndexOf('.') + 1), actual))
                            .map(VernacJavaContextDiagnostics::describe).toList());
        }
        out.append("\nLimits: 40 domain API probes; this is not a completion-popup test.\n");
        return out.toString();
        } catch (RuntimeException | LinkageError failure) {
            failure(out, "diagnostic", failure);
            return out.toString();
        }
    }

    private static GlobalSearchScope scope(StringBuilder out, String label, PsiElement element) {
        try {
            var scope = element.getResolveScope();
            line(out, label + ".scopeChain", () -> scopeChain(scope));
            line(out, label + ".liveScope", () -> VernacLiveScope.find(scope) != null);
            return scope;
        } catch (RuntimeException | LinkageError failure) {
            failure(out, label + ".scope", failure);
            return null;
        }
    }

    static List<String> scopeChain(GlobalSearchScope scope) {
        List<String> names = new ArrayList<>();
        Set<GlobalSearchScope> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        record Node(GlobalSearchScope scope, int depth) {}
        Deque<Node> pending = new ArrayDeque<>();
        pending.add(new Node(scope, 0));
        while (!pending.isEmpty() && names.size() < 32) {
            var node = pending.removeFirst();
            if (!visited.add(node.scope())) continue;
            names.add("  ".repeat(Math.min(node.depth(), 16)) + node.scope().getClass().getName());
            for (var child : VernacLiveScope.children(node.scope()))
                pending.addLast(new Node(child, node.depth() + 1));
        }
        return List.copyOf(names);
    }

    private static Object packageLookup(Project project, String name, GlobalSearchScope scope, boolean alternative) {
        if (scope == null) return "NO_SCOPE";
        var pkg = JavaPsiFacade.getInstance(project).findPackage(name.substring(0, name.lastIndexOf('.')));
        if (pkg == null) return "NO_PACKAGE";
        List<String> found = new ArrayList<>();
        Runnable lookup = () -> Arrays.stream(pkg.findClassByShortName(name.substring(name.lastIndexOf('.') + 1), scope))
                .map(VernacJavaContextDiagnostics::describe).forEach(found::add);
        if (alternative) com.intellij.openapi.project.DumbService.getInstance(project).runWithAlternativeResolveEnabled(lookup::run);
        else lookup.run();
        return found;
    }

    private static String target(PsiElement element) {
        if (element instanceof PsiClass type) return describe(type);
        if (element instanceof PsiMethod method)
            return (method.getContainingClass() == null ? "" : method.getContainingClass().getQualifiedName() + ".")
                    + method.getName() + " [public=" + method.hasModifierProperty(PsiModifier.PUBLIC)
                    + ", static=" + method.hasModifierProperty(PsiModifier.STATIC) + "]";
        return element == null ? "NOT_FOUND" : element.getClass().getName();
    }

    private static SortedSet<String> sorted(Collection<String> values) {
        return new TreeSet<>(values);
    }

    private static String describe(PsiClass type) {
        return type == null ? "NOT_FOUND" : type.getQualifiedName() + " ["
                + type.getClass().getName() + ", valid=" + type.isValid()
                + ", file=" + type.getContainingFile().getClass().getName() + "]";
    }

    private static void line(StringBuilder out, String name, Supplier<?> value) {
        try {
            Object result = value.get();
            out.append(name).append(" = ").append(result).append('\n');
        } catch (RuntimeException | LinkageError failure) {
            failure(out, name, failure);
        }
    }

    private static void failure(StringBuilder out, String name, Throwable failure) {
        if (failure instanceof ProcessCanceledException canceled) throw canceled;
        if (failure instanceof CancellationException canceled) throw canceled;
        // Avoid arbitrary exception messages, which can embed user source or connection details.
        out.append(name).append(" = ERROR ").append(failure.getClass().getName()).append('\n');
    }
}
