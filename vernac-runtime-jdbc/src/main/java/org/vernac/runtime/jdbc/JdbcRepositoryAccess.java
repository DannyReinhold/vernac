// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.*;
import org.vernac.runtime.pagination.*;

import java.util.*;
import java.util.function.*;

/**
 * Explicit generated-adapter extension facility. Read callbacks are synchronous and eagerly
 * checked; only complete canonical roots loaded by their own read context may cross its boundary.
 * SQL and arbitrary Java remain trusted developer code, not a sandbox for user-supplied queries.
 */
@NullMarked
public final class JdbcRepositoryAccess<A extends AggregateRoot<?>, I> {
    @FunctionalInterface
    public interface Work<A extends AggregateRoot<?>, I, R> {
        R execute(Read<A, I> read);
    }

    public interface Read<A extends AggregateRoot<?>, I> {
        A byId(I id);

        Optional<A> optional(I id);

        List<A> roots(JdbcRootQuery query);

        <C extends DomainList<A>> OffsetPage<A, C> offset(
                JdbcRootQuery query, OffsetRequest request, Function<List<A>, C> collection);

        <C extends DomainList<A>> CountedOffsetPage<A, C> countedOffset(
                JdbcRootQuery query, OffsetRequest request, Function<List<A>, C> collection);

        <C extends DomainList<A>> CursorPage<A, C> cursor(
                JdbcRootQuery query,
                CursorRequest request,
                Function<List<A>, C> collection,
                JdbcCursorCodec codec);
    }

    private final JdbcAggregateStore<A> store;
    private final Function<I, UUID> identity;
    private final int maximum;
    private final String fingerprint;

    JdbcRepositoryAccess(
            JdbcAggregateStore<A> store,
            Function<I, UUID> identity,
            int maximum,
            String fingerprint) {
        this.store = Objects.requireNonNull(store);
        this.identity = Objects.requireNonNull(identity);
        this.maximum = maximum;
        this.fingerprint = Objects.requireNonNull(fingerprint);
        PageSize.of(1).checkMaximum(maximum);
    }

    public A single(Work<A, I, A> work) {
        return read(work, (scope, value) -> scope.root(value));
    }

    public Optional<A> optional(Work<A, I, Optional<A>> work) {
        return read(
                work,
                (scope, value) -> {
                    Objects.requireNonNull(value).ifPresent(scope::root);
                    return value;
                });
    }

    public List<A> list(Work<A, I, List<A>> work) {
        return read(
                work,
                (scope, value) -> {
                    scope.roots(value);
                    return List.copyOf(value);
                });
    }

    public Set<A> set(Work<A, I, Set<A>> work) {
        return read(
                work,
                (scope, value) -> {
                    scope.roots(value);
                    return Collections.unmodifiableSet(new LinkedHashSet<>(value));
                });
    }

    public <C extends DomainList<A>> C collection(Work<A, I, C> work) {
        return read(
                work,
                (scope, value) -> {
                    scope.roots(value.asList());
                    return value;
                });
    }

    public <C extends DomainList<A>, P extends Page<A, C>> P page(Work<A, I, P> work) {
        return read(
                work,
                (scope, value) -> {
                    scope.page(value);
                    return value;
                });
    }

    public long count(JdbcRootQuery query) {
        try {
            return store.extensionCount(Objects.requireNonNull(query));
        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw new RepositoryExtensionException(
                    RepositoryExtensionException.Code.STORAGE_FAILURE);
        }
    }

    public boolean exists(JdbcRootQuery query) {
        return count(query) > 0;
    }

    public A save(A value) {
        return store.save(value);
    }

    public void delete(A value) {
        store.delete(value);
    }

    private <R> R read(Work<A, I, R> work, BiFunction<Scope, R, R> check) {
        Objects.requireNonNull(work);
        var scope = new Scope();
        try {
            R result = work.execute(scope);
            scope.check();
            R checked = Objects.requireNonNull(check.apply(scope, result));
            scope.check();
            return checked;
        } catch (RepositoryExtensionException
                | org.vernac.runtime.tenancy.TenancyException
                | AggregateNotFoundException
                | DomainValidationException
                | PaginationContractException
                | org.springframework.dao.OptimisticLockingFailureException safe) {
            throw safe;
        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw new RepositoryExtensionException(
                    RepositoryExtensionException.Code.STORAGE_FAILURE);
        } catch (RuntimeException invalid) {
            throw new RepositoryExtensionException(
                    RepositoryExtensionException.Code.INVALID_RESULT);
        } finally {
            scope.active = false;
        }
    }

    private final class Scope implements Read<A, I> {
        private final Thread thread = Thread.currentThread();
        private final Object transaction = store.extensionTransaction();
        private final Map<A, Object> loaded = new IdentityHashMap<>();
        private final Set<Page<?, ?>> pages = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean active = true;

        void check() {
            if (!active || Thread.currentThread() != thread)
                throw new RepositoryExtensionException(
                        RepositoryExtensionException.Code.SCOPE_EXPIRED);
            if (store.extensionTransaction() != transaction)
                throw new RepositoryExtensionException(
                        RepositoryExtensionException.Code.SCOPE_CHANGED);
        }

        private A track(A value) {
            loaded.put(value, store.extensionSnapshot(value));
            return value;
        }

        public A byId(I id) {
            check();
            return track(store.byId(identity.apply(Objects.requireNonNull(id))));
        }

        public Optional<A> optional(I id) {
            try {
                return Optional.of(byId(id));
            } catch (AggregateNotFoundException absent) {
                return Optional.empty();
            }
        }

        public List<A> roots(JdbcRootQuery query) {
            check();
            var window =
                    store.offset(
                            query.condition(),
                            ordering(query),
                            query.parameters(),
                            OffsetRequest.ofOffset(0, maximum),
                            false,
                            maximum);
            if (window.hasNext())
                throw new RepositoryExtensionException(
                        RepositoryExtensionException.Code.RESULT_LIMIT_EXCEEDED);
            return window.items().stream().map(this::track).toList();
        }

        public <C extends DomainList<A>> OffsetPage<A, C> offset(
                JdbcRootQuery query, OffsetRequest request, Function<List<A>, C> collection) {
            check();
            var window =
                    store.offset(
                            query.condition(),
                            ordering(query),
                            query.parameters(),
                            request,
                            false,
                            maximum);
            var page =
                    OffsetPage.of(
                            collection.apply(window.items().stream().map(this::track).toList()),
                            request,
                            window.hasNext());
            pages.add(page);
            return page;
        }

        public <C extends DomainList<A>> CountedOffsetPage<A, C> countedOffset(
                JdbcRootQuery query, OffsetRequest request, Function<List<A>, C> collection) {
            check();
            var window =
                    store.offset(
                            query.condition(),
                            ordering(query),
                            query.parameters(),
                            request,
                            true,
                            maximum);
            var page =
                    CountedOffsetPage.of(
                            collection.apply(window.items().stream().map(this::track).toList()),
                            request,
                            window.totalElements());
            pages.add(page);
            return page;
        }

        public <C extends DomainList<A>> CursorPage<A, C> cursor(
                JdbcRootQuery query,
                CursorRequest request,
                Function<List<A>, C> collection,
                JdbcCursorCodec codec) {
            check();
            var window =
                    store.cursor(
                            "repository-extension",
                            fingerprint,
                            query.condition(),
                            List.of(new JdbcCursorSort("UUID", List.of("id"), query.reverse())),
                            query.parameters(),
                            request,
                            maximum,
                            codec);
            var page =
                    CursorPage.of(
                            collection.apply(window.items().stream().map(this::track).toList()),
                            request,
                            window.nextCursor());
            pages.add(page);
            return page;
        }

        void page(Page<A, ? extends DomainList<A>> value) {
            if (value == null || !pages.contains(value))
                throw new RepositoryExtensionException(
                        RepositoryExtensionException.Code.UNTRACKED_RESULT);
            roots(value.items().asList());
        }

        A root(A value) {
            if (value == null || !loaded.containsKey(value))
                throw new RepositoryExtensionException(
                        RepositoryExtensionException.Code.UNTRACKED_RESULT);
            if (!loaded.get(value).equals(store.extensionSnapshot(value)))
                throw new RepositoryExtensionException(
                        RepositoryExtensionException.Code.MODIFIED_READ_RESULT);
            return value;
        }

        void roots(Collection<A> values) {
            if (values == null || values.size() > maximum)
                throw new RepositoryExtensionException(
                        RepositoryExtensionException.Code.INVALID_RESULT);
            var identities = new HashSet<Object>();
            for (var value : values) {
                root(value);
                if (!identities.add(value.id()))
                    throw new RepositoryExtensionException(
                            RepositoryExtensionException.Code.INVALID_RESULT);
            }
        }
    }

    private static String ordering(JdbcRootQuery query) {
        return "\"id\"" + (query.reverse() ? " DESC" : " ASC");
    }
}
