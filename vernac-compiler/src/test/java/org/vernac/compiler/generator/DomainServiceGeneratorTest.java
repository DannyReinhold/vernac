// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.compiler.generator;

import com.palantir.javapoet.JavaFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.pipeline.VernacCompiler;

import static org.assertj.core.api.Assertions.assertThat;

class DomainServiceGeneratorTest {

    private final VernacCompiler compiler = new VernacCompiler();

    @Test
    @DisplayName("Generiert eine Spring @Service Bean mit Vorbedingungen und execute-Methode")
    void shouldGenerateDomainService() {
        String dsl = """
                package com.example.energy;
                
                value WattHours(int value) validates {
                    require(value >= 0, "Non-negative");
                }
                
                service TariffCalculator(WattHours capacity, WattHours storedEnergy, WattHours) : WattHours validates {
                    require(capacity.value() > 0, "Capacity must be positive");
                } {
                    int available = Math.max(0, storedEnergy.value() - wattHours.value());
                    return WattHours.of(Math.max(0, capacity.value() - available));
                }
                """;

        var result = compiler.compileSource(dsl);
        JavaFile file = result.generatedFiles().stream()
                .filter(f -> f.typeSpec().name().equals("TariffCalculator"))
                .findFirst()
                .orElseThrow();

        String code = file.toString();

        assertThat(code).contains("@Service");
        assertThat(code).contains("public class TariffCalculator");
        assertThat(code).contains("public WattHours execute(WattHours capacity, WattHours storedEnergy, WattHours wattHours)");
        assertThat(code).contains("Objects.requireNonNull(capacity, \"capacity must not be null\")");
        assertThat(code).contains("if (!(capacity.value()>0))");
        assertThat(code).contains("throw new DomainValidationException(\"Capacity must be positive\")");
        assertThat(code).contains("int available = Math.max(0, storedEnergy.value() - wattHours.value());");
        assertThat(code).contains("return WattHours.of(Math.max(0,capacity.value()-available));");
    }
}