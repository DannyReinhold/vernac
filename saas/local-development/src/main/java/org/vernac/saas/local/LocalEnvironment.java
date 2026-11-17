// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.local;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;
/** Runs after profile YAML, before Boot starts Compose; explicit external settings retain precedence. */
public final class LocalEnvironment implements EnvironmentPostProcessor, Ordered {
    public int getOrder(){return ConfigDataEnvironmentPostProcessor.ORDER+1;}
    public void postProcessEnvironment(ConfigurableEnvironment environment,SpringApplication application) {
        if(!environment.acceptsProfiles(Profiles.of("local"))||!environment.getProperty("vernac.local.enabled",Boolean.class,false))return;
        configure(environment,Path.of(System.getProperty("user.dir")));
    }
    static void configure(ConfigurableEnvironment e,Path root) {
        try {
            if(!"127.0.0.1".equals(e.getRequiredProperty("server.address"))||!e.getProperty("server.ssl.enabled",Boolean.class,false))throw new IllegalStateException();
            int port=e.getProperty("LOCAL_DB_PORT",Integer.class,55439);if(port<1024||port>65535)throw new IllegalStateException();
            var state=LocalState.prepare(root);var properties=new LinkedHashMap<String,Object>();
            String url="jdbc:postgresql://127.0.0.1:"+port+"/workspace";
            properties.put("spring.datasource.url",url);properties.put("spring.datasource.username","workspace_runtime");properties.put("spring.datasource.password",state.value("runtime"));
            properties.put("spring.flyway.url",url);properties.put("spring.flyway.user","vernac");properties.put("spring.flyway.password",state.value("database"));
            properties.put("server.ssl.key-store",state.store().toString());properties.put("server.ssl.key-store-password",state.value("store"));
            properties.put("identity.admission-key-label","local-v1");properties.put("identity.admission-key",state.value("admission"));
            properties.put("identity.delivery-key-label","local-v1");properties.put("identity.delivery-key",state.value("delivery"));
            properties.put("identity.preview-capability",state.value("preview"));properties.put("billing.operator.capability",state.value("merchant"));
            String project=root.toAbsolutePath().getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]","-");
            if(!project.matches("[a-z0-9][a-z0-9_-]*"))throw new IllegalStateException();
            properties.put("spring.docker.compose.arguments[0]","--env-file="+state.composeEnvironment(port));
            properties.put("spring.docker.compose.arguments[1]","--project-name="+project);
            e.getPropertySources().addLast(new MapPropertySource("vernac-local-development",properties));
        }catch(Exception unavailable){throw new IllegalStateException("Local development configuration unavailable.");}
    }
}
