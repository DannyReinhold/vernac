// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;

import org.junit.jupiter.api.Test;
import org.vernac.compiler.pipeline.VernacCompiler;
import static org.assertj.core.api.Assertions.*;

class DomainServiceGeneratorTest {
    private final VernacCompiler compiler = new VernacCompiler();
    @Test void shouldGenerateDomainService() {
        var result = compiler.compileSource("""
                namespace example;
                value WattHours(int value);
                service TariffCalculator behavior {
                    public WattHours calculate(WattHours capacity, WattHours storedEnergy, WattHours)
                    validates { require(self.capacity().value() > 0, "Capacity must be positive"); } {
                        return WattHours.of(Math.max(0, capacity.value() - storedEnergy.value()));
                    }
                }
                """);
        String code = result.generatedFiles().toString();
        assertThat(code).contains("@Service", "@NullMarked", "calculate(", "TariffCalculatorAccess", "interface Input0",
                "DomainChecks.requireNonNull", "BehaviorContractException.requireResult", "self.capacity().value()", "DomainValidationException");
        assertThat(code).doesNotContain("@Transactional", "execute(");
    }
    @Test void rejectsInvalidContracts() {
        String prefix = "namespace example; value Title(String); ";
        for (String body : new String[]{
                "service S behavior { public void f(Title, Title) {} }",
                "service S behavior { public void f(Title a) {} public void f(Title? a) {} }",
                "service S behavior { public void f(Title self) {} }",
                "service S behavior { public void getClass() {} }",
                "service S behavior { public void f(Title? a) {} public void f(String? a) {} }",
                "service S uses Title behavior { public void f() {} }",
                "service S behavior { private void f() {} }",
                "service S behavior { public void f(Title title) validates { require(title.string().isBlank()); } {} }",
                "service S behavior { public void f() {} private void h() validates { require(true); } {} }"
        }) assertThatThrownBy(() -> compiler.compileSource(prefix + body)).as(body).isInstanceOf(RuntimeException.class);
    }
    @Test void permitsDependencyAndExternalImplementation() {
        String code = compiler.compileSource("""
                namespace example;
                value Title(String);
                service Policy behavior { public Title choose(Title) { return title; } }
                service Planning uses Policy behavior {
                    public Title? choose(Title? title) implemented by custom.Planning;
                    public Title fallback(Title title) { return self.policy().choose(title); }
                }
                """).generatedFiles().toString();
        assertThat(code).contains("custom.Planning.choose(self, title)", "Optional<Title>", "@Nullable Title", "Policy policy()", "self.policy().choose(title)");
    }
}
