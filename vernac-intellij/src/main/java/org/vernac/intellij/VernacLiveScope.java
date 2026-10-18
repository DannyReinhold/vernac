// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij;

import com.intellij.injected.editor.VirtualFileWindow;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.*;
import com.intellij.testFramework.LightVirtualFileBase;

import java.nio.file.Path;
import java.util.*;
import org.vernac.compiler.pipeline.BehaviorImports;
import org.vernac.compiler.pipeline.VernacProject;
import org.vernac.compiler.symbols.FileTypeScope;
import org.vernac.compiler.tooling.EditorValueObjects;

/** Marker confined to Vernac's Java contexts, preserving the module's ordinary search scope. */
final class VernacLiveScope extends DelegatingGlobalSearchScope {
    final VernacLiveValues.Snapshot snapshot;
    private final VirtualFile host;

    VernacLiveScope(GlobalSearchScope base, VirtualFile host, VernacLiveValues.Snapshot snapshot) {
        super(base, host, snapshot);
        this.host = host;
        this.snapshot = snapshot;
    }

    @Override
    public boolean contains(VirtualFile file) {
        if (file.equals(host)) return true;
        if (file instanceof VirtualFileWindow injected && injected.getDelegate().equals(host))
            return true;
        if (file instanceof LightVirtualFileBase light
                && light.getOriginalFile() != null
                && light.getOriginalFile().isInLocalFileSystem()
                && light.getOriginalFile().toNioPath().startsWith(snapshot.root)) return true;
        return super.contains(file);
    }

    Set<String> completionClasses() {
        if (snapshot.model == null || !host.isInLocalFileSystem()) return Set.of();
        return completionClasses(snapshot.model, snapshot.api,
                host.toNioPath().toAbsolutePath().normalize());
    }

    static Set<String> completionClasses(VernacProject model,
            EditorValueObjects.Model api, Path path) {
        var source = model.sources().stream().filter(s -> s.path().equals(path)).findFirst();
        if (source.isEmpty()) return Set.of();
        Set<String> namespaces = new HashSet<>();
        model.sources().forEach(s -> namespaces.add(s.unit().namespace()));
        var scope = new FileTypeScope(
                source.get().unit(), model.symbols(), namespaces);
        Set<String> names = new TreeSet<>(BehaviorImports.visible(model, scope).values());
        names.retainAll(api.values().keySet());
        names.retainAll(api.valueNames());
        return names;
    }

    static VernacLiveScope find(GlobalSearchScope scope) {
        // ResolveScopeManager.withFile() unites the host scope with the injected file.
        // The resulting UnionScope is not a DelegatingGlobalSearchScope.
        Set<GlobalSearchScope> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<GlobalSearchScope> pending = new ArrayDeque<>();
        pending.add(scope);
        VernacLiveScope found = null;
        while (!pending.isEmpty()) {
            var next = pending.removeFirst();
            if (!visited.add(next)) continue;
            if (next instanceof VernacLiveScope live) {
                // Never choose an arbitrary host or snapshot from a combined scope.
                if (found != null && (found.snapshot != live.snapshot || !found.host.equals(live.host)))
                    return null;
                found = live;
            } else {
                pending.addAll(children(next));
            }
        }
        return found;
    }

    static List<GlobalSearchScope> children(GlobalSearchScope scope) {
        if (scope instanceof DelegatingGlobalSearchScope wrapper)
            return List.of(wrapper.getDelegate());
        if (scope instanceof UnionScope union) return Arrays.asList(union.getMyScopes());
        return List.of();
    }
}
