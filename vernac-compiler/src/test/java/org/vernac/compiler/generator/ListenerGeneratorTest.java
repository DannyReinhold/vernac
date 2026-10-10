// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.generator;
import org.junit.jupiter.api.Test;
import org.vernac.compiler.pipeline.VernacCompiler;
import static org.assertj.core.api.Assertions.*;

class ListenerGeneratorTest {
    @Test void shouldGenerateSynchronousListenerWithDependencyInjection() {
        var files=new VernacCompiler().compileSource("""
                namespace example.energy;
                event Overheated();
                service Notifications behavior { public void send() {} }
                synchronous listener Alert on Overheated uses Notifications behavior {
                    execute { self.notifications().send(); }
                }
                synchronous listener Log on Overheated behavior { execute implemented by custom.Log; }
                """).generatedFiles();
        String code=files.stream().map(Object::toString).reduce("",String::concat);
        assertThat(code).contains("package example.energy.adapter.inbound.event;", "implements EventHandler", "@Service",
                "private final Notifications notifications", "self.notifications().send()", "custom.Log.execute(self, event)",
                "return \"example.energy.Alert\"", "return Overheated.class", "AlertAccess")
                .doesNotContain("TransactionalEventListener", "@Transactional", "AFTER_COMMIT");
    }
    @Test void rejectsCustomPackageAndGeneratesDeferredDelivery() {
        var compiler=new VernacCompiler();
        assertThatThrownBy(() -> compiler.compileSource("namespace example; event Ping(); synchronous listener Alert on Ping behavior { package custom; execute {} }"));
        assertThat(compiler.compileSource("namespace example; event Ping(); deferred listener Alert on Ping behavior { execute {} }").generatedFiles().toString())
                .contains("boolean deferred()", "return true", "JdbcPingEvents");
    }
}
