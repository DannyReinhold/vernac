// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.vernac.compiler.analyzer.SemanticValidationException;
import org.vernac.compiler.testutil.InMemoryJavaCompiler;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CollectionContractTest {
    private InMemoryJavaCompiler.CompilationOutput compile(String body) {
        var result = InMemoryJavaCompiler.compile(new VernacCompiler().compileSource("namespace collections; " + body));
        assertTrue(result.success(), result.diagnostics().toString());
        return result;
    }
    private Object call(Object receiver, String name, Class<?> parameter, Object argument) throws Exception {
        return receiver.getClass().getMethod(name, parameter).invoke(receiver, argument);
    }
    private Object call(Object receiver, String name) throws Exception { return receiver.getClass().getMethod(name).invoke(receiver); }
    private Object of(Class<?> type, List<?> items) throws Exception { return type.getMethod("of", Iterable.class).invoke(null, items); }

    @Test void listsSupportAllOccurrencesQueriesAndOwnMethods() throws Exception {
        var output = compile("""
                value Name(String value) list Names behavior {
                    public boolean hasDuplicates() { return !self.duplicates().isEmpty(); }
                    private int answer() { return 42; }
                };
                value Status = OPEN | DONE set Statuses;
                id TaskId list;
                value Wrapped(Names, TaskIds? ids);
                """);
        Class<?> name = output.loadClass("collections.domain.Name"), names = output.loadClass("collections.domain.Names");
        Object a = name.getMethod("of", String.class).invoke(null, "A"), b = name.getMethod("of", String.class).invoke(null, "B"), c = name.getMethod("of", String.class).invoke(null, "C");
        var source = new ArrayList<>(List.of(a, b, a, c, b));
        Object list = of(names, source); source.clear();
        assertEquals(5, call(list, "size"));
        Object duplicates = call(list, "duplicates");
        assertEquals(List.of(a, b, a, b), call(duplicates, "asList"));
        assertEquals(List.of(a, b), call(call(duplicates, "distinct"), "asList"));
        assertEquals(List.of(a, b, c), call(call(list, "distinct"), "asList"));
        assertEquals(true, call(list, "hasDuplicates"));
        assertThrows(NoSuchMethodException.class, () -> names.getDeclaredMethod("answer"));
        assertEquals(2, call(list, "count", name, a));
        assertEquals(true, call(list, "contains", name, a));
        assertEquals(Optional.of(a), call(list, "find", name, a));
        assertEquals(Optional.of(a), call(list, "first"));
        assertEquals(Optional.of(b), call(list, "last"));
        assertEquals(c, call(list, "get", int.class, 3));
        assertEquals(List.of(b, a, c, b), call(call(list, "minus", name, a), "asList"));
        assertEquals(List.of(b, c, b), call(call(list, "minusAll", name, a), "asList"));
        assertEquals(List.of(a, a, c), call(call(list, "matching", Iterable.class, List.of(a, c, a)), "asList"));
        assertEquals(List.of(a, a), call(call(list, "matching", name, a), "asList"));
        assertEquals(List.of(c), call(call(list, "minusAll", Iterable.class, List.of(a, b)), "asList"));
        assertEquals(6, call(call(list, "plus", name, c), "size"));
        assertEquals(10, call(call(list, "plusAll", Iterable.class, list), "size"));
        assertEquals(List.of(a, a), call(call(list, "filter", java.util.function.Predicate.class,
                (java.util.function.Predicate<Object>) a::equals), "asList"));
        assertEquals(5, call(list, "size"));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) call(list, "asList")).clear());
        Object empty = names.getMethod("empty").invoke(null);
        assertEquals(Optional.empty(), call(empty, "first"));
        assertEquals(Optional.empty(), call(empty, "last"));
        assertEquals(List.of(), call(call(empty, "duplicates"), "asList"));
        assertInstanceOf(IndexOutOfBoundsException.class, assertThrows(InvocationTargetException.class,
                () -> call(empty, "get", int.class, 0)).getCause());
        assertEquals(list, of(names, List.of(a, b, a, c, b)));
        assertEquals(list.hashCode(), of(names, List.of(a, b, a, c, b)).hashCode());
        assertNotEquals(list, of(names, List.of(b, a, a, c, b)));
        assertTrue(list.toString().startsWith("Names"));
        Class<?> status = output.loadClass("collections.domain.Status"), statuses = output.loadClass("collections.domain.Statuses");
        var values = status.getEnumConstants();
        Object set = of(statuses, List.of(values[0], values[1], values[0]));
        assertEquals(2, call(set, "size"));
        assertEquals(1, call(set, "count", status, values[0]));
        assertEquals(set, of(statuses, List.of(values[1], values[0])));
        assertEquals(set.hashCode(), of(statuses, List.of(values[1], values[0])).hashCode());
        assertThrows(NoSuchMethodException.class, () -> statuses.getMethod("duplicates"));
        assertThrows(NoSuchMethodException.class, () -> statuses.getMethod("get", int.class));
        assertThrows(UnsupportedOperationException.class, () -> ((Set<?>) call(set, "asSet")).clear());
        Class<?> wrapped = output.loadClass("collections.domain.Wrapped");
        assertNotNull(wrapped.getMethod("of", names));
        assertEquals(Optional.empty(), wrapped.getMethod("ids").invoke(wrapped.getMethod("of", names).invoke(null, list)));
    }

    @Test void entityAndAggregateCollectionsPreserveIdentityInstances() throws Exception {
        for (String kind : List.of("entity", "aggregate")) {
            var output = compile("id AccountId; value Balance(int); " + kind + " Account[AccountId](Balance balance) list Accounts;");
            Class<?> id = output.loadClass("collections.domain.AccountId"), account = output.loadClass("collections.domain.Account"), accounts = output.loadClass("collections.domain.Accounts");
            Object identity = id.getMethod("create").invoke(null);
        Class<?> balance = output.loadClass("collections.domain.Balance");
        Object hundred = balance.getMethod("of", int.class).invoke(null, 100);
        Object twoHundred = balance.getMethod("of", int.class).invoke(null, 200);
            Method restore = kind.equals("entity") ? account.getMethod("reconstitute", id, balance)
                    : account.getMethod("reconstitute", id, balance, java.time.Instant.class, java.time.Instant.class, long.class);
            Object first = kind.equals("entity") ? restore.invoke(null, identity, hundred)
                    : restore.invoke(null, identity, hundred, java.time.Instant.EPOCH, java.time.Instant.EPOCH, 0L);
            Object second = kind.equals("entity") ? restore.invoke(null, identity, twoHundred)
                    : restore.invoke(null, identity, twoHundred, java.time.Instant.EPOCH, java.time.Instant.EPOCH, 1L);
            Object list = of(accounts, List.of(first, second));
            var duplicates = (List<?>) call(call(list, "duplicates"), "asList");
            assertSame(first, duplicates.get(0)); assertSame(second, duplicates.get(1));
            assertSame(first, ((List<?>) call(call(call(list, "duplicates"), "distinct"), "asList")).getFirst());
            assertSame(first, ((Optional<?>) call(list, "by", id, identity)).orElseThrow());
            assertSame(first, ((Optional<?>) call(list, "find", account, second)).orElseThrow());
            assertEquals(true, call(list, "contains", id, identity));
            assertEquals(1, call(call(list, "minusId", id, identity), "size"));
            assertSame(second, ((List<?>) call(call(list, "minusId", id, identity), "asList")).getFirst());
            assertEquals(0, call(call(list, "minusAllId", id, identity), "size"));
        }
    }

    @Test void setsNeverReplaceTheExistingInstance() throws Exception {
        var output = compile("id AccountId; value Balance(int); entity Account[AccountId](Balance balance) set Accounts;");
        Class<?> id = output.loadClass("collections.domain.AccountId"), account = output.loadClass("collections.domain.Account"), accounts = output.loadClass("collections.domain.Accounts");
        Object identity = id.getMethod("create").invoke(null);
        Class<?> balance = output.loadClass("collections.domain.Balance");
        Object hundred = balance.getMethod("of", int.class).invoke(null, 100);
        Object twoHundred = balance.getMethod("of", int.class).invoke(null, 200);
        Method restore = account.getMethod("reconstitute", id, balance);
        Object first = restore.invoke(null, identity, hundred);
        Object second = restore.invoke(null, identity, twoHundred);
        Object set = of(accounts, List.of(first, second));
        assertSame(first, ((Set<?>) call(set, "asSet")).iterator().next());
        Object plus = call(set, "plus", account, second);
        assertSame(first, ((Optional<?>) call(plus, "by", id, identity)).orElseThrow());
        assertSame(first, ((Set<?>) call(call(set, "plusAll", Iterable.class, List.of(second)), "asSet")).iterator().next());
    }

    @Test void crossNamespaceCollectionFieldsAndImportsWork(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("model")); Files.createDirectories(root.resolve("view"));
        Files.writeString(root.resolve("model/id.vernac"), "namespace model; id TaskId list;");
        Files.writeString(root.resolve("model/name.vernac"), "namespace model; value Name(String) set Names; value Local(TaskIds);");
        Files.writeString(root.resolve("view/view.vernac"), "namespace view; import model.TaskIds; value View(TaskIds, model.Names? names);");
        var result = new VernacCompiler().compileProject(root);
        var output = InMemoryJavaCompiler.compile(result.generatedFiles());
        assertTrue(output.success(), output.diagnostics().toString());
        assertNotNull(output.loadClass("view.domain.View").getMethod("taskIds"));
    }

    @Test void rejectsInvalidFieldsPackagesAndApiCollisions() {
        for (String body : List.of(
                "id TaskId; value NumberValue(int); entity Task[TaskId](NumberValue value) list Tasks; value Broken(Tasks);",
                "id TaskId; value NumberValue(int); aggregate Task[TaskId](NumberValue value) set Tasks; value Broken(Tasks);",
                "id TaskId list behavior { package elsewhere; };",
                "value Name(String) list Names; value Names(String);",
                "value Name(String) list String;",
                "value Name(String) collection;",
                "value Name(String) list behavior { public int size() { return 0; } };",
                "value Name(String) list behavior { public int count(Name other) { return 0; } };",
                "value Name(String) list behavior { public String duplicates() { return \"x\"; } };",
                "id TaskId; value NumberValue(int); entity Task[TaskId](NumberValue value) list behavior { public String by(TaskId id) { return \"x\"; } };"))
            assertThrows(SemanticValidationException.class, () -> new VernacCompiler().compileSource("namespace collections; " + body), body);
    }
}
