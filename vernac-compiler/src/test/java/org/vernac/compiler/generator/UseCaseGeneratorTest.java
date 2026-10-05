// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.pipeline.VernacCompilationResult;
import org.vernac.compiler.pipeline.VernacCompiler;

import static org.assertj.core.api.Assertions.assertThat;

class UseCaseGeneratorTest {

    private final VernacCompiler compiler = new VernacCompiler();

    @Test
    @DisplayName("Generiert vollständigen Spring-Service für UseCase mit Conventions")
    void shouldGenerateUseCaseService() {
        String dsl = """
                package com.example.shop;
                
                id OrderId;
                aggregate Order[OrderId](String status);
                repository for Order {}
                
                usecase CancelOrder(OrderId id, String reason) validates {
                    require(!reason.isBlank(), "Reason required");
                } {
                    use OrderRepository;
                    load Order by id;
                    order.status();
                    save order;
                    return (order.id(), reason as cancellationReason);
                }
                """;

        VernacCompilationResult result = compiler.compileSource(dsl);

        // Prüfen, ob UseCase im usecase-Package liegt
        var useCaseFile = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("CancelOrder"))
                .findFirst();

        assertThat(useCaseFile).isPresent();
        assertThat(useCaseFile.get().packageName()).isEqualTo("com.example.shop.usecase");

        String code = useCaseFile.get().toString();
        assertThat(code).contains("@Service");
        assertThat(code).contains("@Transactional");
        assertThat(code).contains("public static record Result(OrderId id, String cancellationReason)");
        assertThat(code).contains("this.orderRepository.byId(id)");
        assertThat(code).contains("this.orderRepository.save(order)");
    }
}