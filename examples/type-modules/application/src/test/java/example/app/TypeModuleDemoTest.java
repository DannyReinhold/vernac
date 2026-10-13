// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package example.app;
import example.app.domain.Account;
import example.shared.domain.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TypeModuleDemoTest {
    @Test void compiledLibraryFactoriesAndCollectionsAreUsedByTheApplication() {
        var price=Money.of(Amount.of(new BigDecimal("12.30")),Currency.getInstance("EUR"));
        var labels=Labels.of(List.of(Label.of("shared"),Label.of("contract")));
        var account=Account.create(ExternalRef.create(),price,labels,Kind.PREMIUM);
        assertSame(price,account.money());assertEquals(2,account.labels().size());
        assertEquals(new BigDecimal("12.30"),account.money().amount().bigDecimal());
        System.out.println("External type-module account: "+account);
    }
}
