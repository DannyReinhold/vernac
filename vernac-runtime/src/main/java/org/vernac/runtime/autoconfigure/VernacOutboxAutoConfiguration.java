package org.vernac.runtime.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.vernac.runtime.outbox.EventDispatcher;
import org.vernac.runtime.outbox.JdbcEventDispatcher;

@AutoConfiguration(after = {
        JdbcTemplateAutoConfiguration.class,
        JacksonAutoConfiguration.class
})
@ConditionalOnClass({
        NamedParameterJdbcTemplate.class,
        ObjectMapper.class
})
public class VernacOutboxAutoConfiguration {

    @Bean
    @ConditionalOnBean({
            NamedParameterJdbcTemplate.class,
            ObjectMapper.class
    })
    @ConditionalOnMissingBean(EventDispatcher.class)
    public JdbcEventDispatcher vernacEventDispatcher(
            NamedParameterJdbcTemplate jdbcTemplate,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper
    ) {
        return new JdbcEventDispatcher(
                jdbcTemplate,
                eventPublisher,
                objectMapper
        );
    }
}