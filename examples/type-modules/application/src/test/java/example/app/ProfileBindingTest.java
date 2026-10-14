// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package example.app;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import example.app.demo.ModuleDemoApplication;
import example.shared.domain.PaymentProvider;
import static org.junit.jupiter.api.Assertions.*;
class ProfileBindingTest {
    @Test void springBootLoadsGeneratedBindingAndProfileConfiguration() {
        try(var dev=new SpringApplicationBuilder(ModuleDemoApplication.class).web(WebApplicationType.NONE).profiles("dev").run()) {
            assertEquals("NoopPaymentProvider",dev.getBean(PaymentProvider.class).getClass().getSimpleName());
            assertEquals(1,dev.getBeanNamesForType(PaymentProvider.class).length);
        }
        try(var prod=new SpringApplicationBuilder(ModuleDemoApplication.class).web(WebApplicationType.NONE).profiles("prod")
                .run("--payment-fixture.api-key=local-fixture-only")) {
            assertEquals("ConfiguredTestPaymentProvider",prod.getBean(PaymentProvider.class).getClass().getSimpleName());
            assertEquals(1,prod.getBeanNamesForType(PaymentProvider.class).length);
        }
    }
}
