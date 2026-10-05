// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.generator;

import com.palantir.javapoet.JavaFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.pipeline.VernacCompilationResult;
import org.vernac.compiler.pipeline.VernacCompiler;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ListenerGeneratorTest {

    private final VernacCompiler compiler = new VernacCompiler();

    @Test
    @DisplayName("Generiert Spring @Component Listener mit TransactionalEventListener und Dependency Injection")
    void shouldGenerateEventListenerWithTransactionalAnnotation() {
        String dsl = """
                package com.example.energy;
                
                id StorageId;
                outbox event StorageOverheated(StorageId storageId, int temperatureCelsius);
                
                port NotificationPort {
                    void sendAlert(String message) {
                        adapter custom;
                    }
                }
                
                listener StorageOverheated {
                    use NotificationPort notifications;
                
                    notifications.sendAlert("Storage " + event.storageId().value() + " overheated: " + event.temperatureCelsius());
                }
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        List<String> typeNames = result.generatedFiles().stream()
                .map(f -> f.typeSpec().name())
                .toList();

        assertThat(typeNames).contains("StorageOverheatedListener");

        JavaFile listenerFile = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("StorageOverheatedListener"))
                .findFirst()
                .orElseThrow();

        String code = listenerFile.toString();
        String normalizedCode = code.replaceAll("\\s+", " ");

        assertThat(code).contains("@Component");
        assertThat(code).contains("public class StorageOverheatedListener");
        assertThat(code).contains("private final NotificationPort notifications;");
        assertThat(code).contains("public StorageOverheatedListener(NotificationPort notifications)");
        assertThat(code).contains("this.notifications = Objects.requireNonNull(notifications, \"notifications must not be null\");");
        assertThat(normalizedCode).contains("@TransactionalEventListener( phase = TransactionPhase.AFTER_COMMIT )");
        assertThat(code).contains("public void on(StorageOverheated event)");
        assertThat(code).contains("notifications.sendAlert(\"Storage \" + event.storageId().value() + \" overheated: \" + event.temperatureCelsius());");
    }

    @Test
    @DisplayName("Unterstützt package-Override im Listener-Block")
    void shouldSupportCustomPackageInListener() {
        String dsl = """
                package com.example.energy;
                
                event SystemAlertTriggered(String message);
                
                listener SystemAlertTriggered {
                    package com.example.alerts;
                }
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        JavaFile listenerFile = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("SystemAlertTriggeredListener"))
                .findFirst()
                .orElseThrow();

        assertThat(listenerFile.packageName()).isEqualTo("com.example.alerts");
    }
}