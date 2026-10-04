package org.vernac.runtime.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.vernac.runtime.DomainEvent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class JdbcEventDispatcher implements EventDispatcher {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public JdbcEventDispatcher(
            NamedParameterJdbcTemplate jdbcTemplate,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void dispatch(String aggregateType, String aggregateId, List<DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        List<MapSqlParameterSource> outboxBatch = new ArrayList<>();

        for (DomainEvent event : events) {
            if (event instanceof OutboxDomainEvent outbox) {
                outboxBatch.add(new MapSqlParameterSource()
                        .addValue("id", outbox.eventId())
                        .addValue("eventType", outbox.eventType())
                        .addValue("aggregateType", aggregateType)
                        .addValue("aggregateId", aggregateId)
                        .addValue("payload", serializePayload(outbox))
                        .addValue("occurredOn", Timestamp.from(outbox.occurredOn()))
                        .addValue("createdAt", Timestamp.from(Instant.now()))
                        .addValue("status", "PENDING"));
            } else {
                // In-Memory Events direkt an den Spring ApplicationContext
                this.eventPublisher.publishEvent(event);
            }
        }

        if (!outboxBatch.isEmpty()) {
            String sql = """
                    INSERT INTO vernac_outbox 
                    (id, event_type, aggregate_type, aggregate_id, payload, occurred_on, created_at, status)
                    VALUES (:id, :eventType, :aggregateType, :aggregateId, :payload::jsonb, :occurredOn, :createdAt, :status)
                    """;
            this.jdbcTemplate.batchUpdate(sql, outboxBatch.toArray(MapSqlParameterSource[]::new));
        }
    }

    private String serializePayload(Object event) {
        try {
            return this.objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event payload: " + event, e);
        }
    }
}