package example.documents;

import example.documents.domain.DocumentPresence;
import example.documents.domain.DocumentTitle;
import example.documents.domain.DocumentId;
import example.documents.domain.DocumentAccess;
import example.documents.domain.DeleteVisibility;
import example.documents.authorization.DocumentPermissions;
import example.documents.usecase.ShowDocument;
import example.documents.domain.DocumentDeleted;
import org.vernac.runtime.DomainEvent;
import org.vernac.runtime.EventId;
import org.vernac.runtime.events.EventOrigin;
import org.vernac.runtime.events.EventStorage;
import java.util.List;
import example.documents.domain.DocumentStore;
import example.documents.usecase.AddDocument;
import example.documents.usecase.DeleteDocument;
import example.documents.usecase.HasDocument;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.vernac.runtime.authorization.AuthorizationGuard;
import org.vernac.runtime.authorization.PermissionDecision;
import org.vernac.runtime.authorization.VerifiedActor;
import org.vernac.runtime.tenancy.TenantId;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class WorkspaceFixtures {
    private WorkspaceFixtures() {}

    @Configuration(proxyBeanMethods = false)
    @Import(Workspace.class)
    public static class Editor {
        @Bean Policy policy() { return new Policy("editor", PermissionDecision.ALLOW); }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(Workspace.class)
    public static class Reader {
        @Bean Policy policy() { return new Policy("reader", PermissionDecision.DENY); }
    }

    static AuthorizationGuard guardFor(String subject, PermissionDecision decision) {
        return new Policy(subject, decision).guard();
    }

    record Policy(String subject, PermissionDecision decision) implements DocumentAccess {
        AuthorizationGuard guard() {
            var actor = new VerifiedActor("workspace-fixture", subject, VerifiedActor.Kind.HUMAN);
            return new AuthorizationGuard(() -> Optional.of(actor), request -> evaluate(request.permission()));
        }

        @Override public DeleteVisibility deleteVisibility(DocumentId id) {
            return evaluate(DocumentPermissions.DELETE) == PermissionDecision.ALLOW
                    ? DeleteVisibility.VISIBLE : DeleteVisibility.HIDDEN;
        }

        private PermissionDecision evaluate(org.vernac.runtime.authorization.PermissionKey key) {
            return key.qualifiedName().equals(DocumentPermissions.DELETE.qualifiedName()) ? decision : PermissionDecision.DENY;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({AddDocument.class, HasDocument.class, DeleteDocument.class, ShowDocument.class})
    static class Workspace {
        @Bean AuthorizationGuard authorization(Policy policy) {
            return policy.guard();
        }

        @Bean TenantId tenant() {
            return TenantId.of(UUID.randomUUID());
        }

        @Bean DocumentStore documents() {
            return new DocumentStore() {
                private final Map<DocumentId, DocumentTitle> values = new HashMap<>();
                @Override public void save(DocumentId id, DocumentTitle title) { values.put(id, title); }
                @Override public DocumentPresence contains(DocumentId id) {
                    return values.containsKey(id) ? DocumentPresence.PRESENT : DocumentPresence.ABSENT;
                }
                @Override public void delete(DocumentId documentId) { values.remove(documentId); }
            };
        }

        @Bean EventStorage deletionEvents() {
            return new EventStorage() {
                private final Map<EventId, DomainEvent> events = new HashMap<>();
                @Override public String listenerId() { return "workspace.memory.deletions"; }
                @Override public String eventType() { return "example.documents.DocumentDeleted"; }
                @Override public Class<DocumentDeleted> eventClass() { return DocumentDeleted.class; }
                @Override public int tenantDeliveryVersion() { return 1; }
                @Override public void persist(DomainEvent event, List<String> listeners) { events.put(event.eventId(), event); }
                @Override public DomainEvent load(EventId id) { return events.get(id); }
                @Override public void persist(DomainEvent event, List<String> listeners, EventOrigin origin) {
                    origin.requireCurrent();
                    persist(event, listeners);
                }
            };
        }

        @Bean AbstractPlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
                @Override protected void doCommit(DefaultTransactionStatus status) {}
                @Override protected void doRollback(DefaultTransactionStatus status) {}
            };
        }
    }
}
