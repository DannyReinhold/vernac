// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http.development;

import jakarta.servlet.Filter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.cache.NonCacheableCacheEntryValidity;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ITemplateResolver;
import org.thymeleaf.templateresolver.TemplateResolution;
import org.thymeleaf.templateresource.ClassLoaderTemplateResource;
import org.thymeleaf.templateresource.ITemplateResource;
import org.thymeleaf.templateresource.StringTemplateResource;
import org.vernac.runtime.DevelopmentRevisions;

import java.util.Map;

/** Opt-in Spring Boot integration for synchronous development requests. */
@Configuration(proxyBeanMethods = false)
public class DevelopmentWebConfiguration {
    @Bean
    public DevelopmentRevisions vernacDevelopmentRevisions() {
        return new DevelopmentRevisions();
    }

    @Bean
    public Filter vernacDevelopmentRevisionFilter(DevelopmentRevisions revisions) {
        return (request, response, chain) -> {
            try (var scope = revisions.pin()) {
                chain.doFilter(request, response);
            }
        };
    }

    @Bean
    public ITemplateResolver vernacDevelopmentTemplateResolver(DevelopmentRevisions revisions) {
        return new DevelopmentTemplateResolver(revisions);
    }

    private record DevelopmentTemplateResolver(DevelopmentRevisions revisions) implements ITemplateResolver {
        @Override
        public String getName() {
            return "Vernac development templates";
        }

        @Override
        public Integer getOrder() {
            return Integer.MIN_VALUE;
        }

        @Override
        public TemplateResolution resolveTemplate(IEngineConfiguration configuration, String ownerTemplate,
                                                  String template, Map<String, Object> attributes) {
            if (!template.startsWith("vernac-generated/") || template.contains("..") || template.contains("\\")) {
                return null;
            }
            String name = template.endsWith(".html") ? template : template + ".html";
            var selected = revisions.template(name).<ITemplateResource>map(StringTemplateResource::new)
                    .orElseGet(() -> new ClassLoaderTemplateResource("templates/" + name, "UTF-8"));
            return new TemplateResolution(selected, true, TemplateMode.HTML, false,
                    NonCacheableCacheEntryValidity.INSTANCE);
        }
    }
}
