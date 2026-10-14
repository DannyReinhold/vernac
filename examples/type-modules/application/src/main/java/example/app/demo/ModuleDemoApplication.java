// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package example.app.demo;
import java.math.BigDecimal;
import java.util.Currency;
import org.slf4j.LoggerFactory;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import example.shared.domain.*;

/** A database-free profile-selection walkthrough. All providers are local fixtures. */
@SpringBootApplication(exclude=DataSourceAutoConfiguration.class)
public class ModuleDemoApplication {
    public static void main(String[] args) {SpringApplication.run(ModuleDemoApplication.class,args);}
    @Bean ApplicationRunner demonstrate(PaymentProvider provider) {
        return arguments -> {
            var money=Money.of(Amount.of(new BigDecimal("12.30")),Currency.getInstance("EUR"));
            var reference=provider.charge(money);
            LoggerFactory.getLogger(ModuleDemoApplication.class).info(
                    "Selected {} through the shared PaymentProvider contract; fixture returned {}. No payment was made.",
                    provider.getClass().getSimpleName(),reference);
        };
    }
}
