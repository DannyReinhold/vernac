package example.documents;

import example.documents.domain.DeletionCount;
import example.documents.domain.DocumentAudit;
import example.documents.domain.DocumentDeleted;
import example.documents.domain.DocumentId;
import example.documents.domain.DocumentPresence;
import example.documents.domain.DocumentStore;
import example.documents.domain.DocumentTitle;
import example.documents.usecase.AddDocument;
import example.documents.usecase.DeleteDocument;
import example.documents.usecase.HasDocument;
import example.documents.usecase.RecordedDeletions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.vernac.runtime.DomainEvent;
import org.vernac.runtime.DomainValidationException;
import org.vernac.runtime.EventId;
import org.vernac.runtime.authorization.AuthorizationGuard;
import org.vernac.runtime.authorization.PermissionDecision;
import org.vernac.runtime.events.EventHandler;
import org.vernac.runtime.events.EventOrigin;
import org.vernac.runtime.events.EventStorage;
import org.vernac.runtime.tenancy.TenantId;

import java.util.List;
import java.util.UUID;

public final class PostgresFixtures {
    private PostgresFixtures() {}

    @Configuration(proxyBeanMethods = false)
    @Import(Database.class)
    public static class Editor {
        @Bean AuthorizationGuard guard() {
            return WorkspaceFixtures.guardFor("editor", PermissionDecision.ALLOW);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(Database.class)
    public static class Reader {
        @Bean AuthorizationGuard guard() {
            return WorkspaceFixtures.guardFor("reader", PermissionDecision.DENY);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({Database.class, Editor.class})
    public static class FailingDelivery {
        @Bean EventHandler failingListener() {
            return new EventHandler() {
                @Override public String listenerId() { return "workspace.rejected-delivery"; }
                @Override public Class<DocumentDeleted> eventClass() { return DocumentDeleted.class; }
                @Override public void handle(DomainEvent event) { throw new DomainValidationException("Injected delivery rejection"); }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({AddDocument.class, HasDocument.class, DeleteDocument.class, RecordedDeletions.class})
    static class Database {
        @Bean TenantId tenant() {
            return TenantId.of(UUID.randomUUID());
        }

        @Bean Tables tables() {
            return new Tables();
        }

        @Bean DataSourceTransactionManager transactionManager(Tables tables) {
            return new DataSourceTransactionManager(tables.source);
        }

        @Bean DocumentStore store(Tables tables) {
            return new DocumentStore() {
                @Override public void save(DocumentId id, DocumentTitle title) {
                    tables.jdbc.update("insert into " + tables.name("documents") + " values (?, ?)", id.value(), title.string());
                }

                @Override public DocumentPresence contains(DocumentId id) {
                    boolean exists = Boolean.TRUE.equals(tables.jdbc.queryForObject(
                            "select exists (select 1 from " + tables.name("documents") + " where id = ?)",
                            Boolean.class, id.value()));
                    return exists ? DocumentPresence.PRESENT : DocumentPresence.ABSENT;
                }

                @Override public void delete(DocumentId id) {
                    tables.jdbc.update("delete from " + tables.name("documents") + " where id = ?", id.value());
                }
            };
        }

        @Bean DocumentAudit audit(Tables tables) {
            return id -> DeletionCount.of(tables.jdbc.queryForObject(
                    "select count(*) from " + tables.name("deletions") + " where document_id = ?",
                    Integer.class, id.value()));
        }

        @Bean EventStorage deletionEvents(Tables tables) {
            return new EventStorage() {
                @Override public String listenerId() { return "workspace.postgres.deletions"; }
                @Override public String eventType() { return "example.documents.DocumentDeleted"; }
                @Override public Class<DocumentDeleted> eventClass() { return DocumentDeleted.class; }
                @Override public int tenantDeliveryVersion() { return 1; }
                @Override public void persist(DomainEvent event, List<String> listeners, EventOrigin origin) {
                    origin.requireCurrent();
                    persist(event, listeners);
                }
                @Override public void persist(DomainEvent event, List<String> listeners) {
                    var deleted = (DocumentDeleted) event;
                    tables.jdbc.update("insert into " + tables.name("deletions") + " values (?, ?, ?)",
                            event.eventId().value(), deleted.documentId().value(), java.sql.Timestamp.from(event.occurredAt()));
                }
                @Override public DomainEvent load(EventId id) {
                    return tables.jdbc.queryForObject("select document_id, occurred_at from " + tables.name("deletions") + " where event_id = ?",
                            (row, number) -> DocumentDeleted.reconstitute(id, row.getTimestamp(2).toInstant(),
                                    DocumentId.of(row.getObject(1, UUID.class))), id.value());
                }
            };
        }
    }

    static final class Tables implements AutoCloseable {
        private final String schema = "vernac_case_" + UUID.randomUUID().toString().replace("-", "");
        private final DriverManagerDataSource source;
        private final JdbcTemplate jdbc;

        Tables() {
            String url = System.getenv("VERNAC_PG_TEST_URL");
            if (url == null || !url.startsWith("jdbc:postgresql:"))
                throw new IllegalStateException("Set VERNAC_PG_TEST_URL to a disposable PostgreSQL database for the postgres profile");
            source = new DriverManagerDataSource(url, System.getenv("VERNAC_PG_TEST_USER"),
                    System.getenv("VERNAC_PG_TEST_PASSWORD"));
            jdbc = new JdbcTemplate(source);
            jdbc.execute("create schema " + schema);
            try {
                jdbc.execute("create table " + name("documents") + " (id uuid primary key, title text not null)");
                jdbc.execute("create table " + name("deletions") + " (event_id uuid primary key, document_id uuid not null, occurred_at timestamptz not null)");
            } catch (RuntimeException failure) {
                try { close(); }
                catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw failure;
            }
        }

        private String name(String table) {
            return schema + "." + table;
        }

        @Override public void close() {
            jdbc.execute("drop schema " + schema + " cascade");
        }
    }
}
