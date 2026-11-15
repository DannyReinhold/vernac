// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.restrictions;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.vernac.saas.identity.domain.AccountId;
import org.vernac.saas.subscriptions.domain.*;
import org.vernac.saas.restrictions.domain.*;

class RestrictionDomainTest {
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final UsageWindow WINDOW = UsageWindow.of(NOW, NOW.plusSeconds(60));
    static final BenefitKey EXPORTS = BenefitKey.of("exports");
    static QuotaBudget budget() {
        var account = AccountId.create();
        return QuotaBudget.create(MeteringKeys.budget(account, EXPORTS, WINDOW), account, EXPORTS, WINDOW, Units.of(0), Units.of(0));
    }
    @Test void finiteZeroAndUnlimitedRemainDistinctAndOverflowNeverGrants() {
        var budget = budget(); budget.consume(Units.of(0), Allowance.of(0));
        assertThrows(RestrictionFailure.class, () -> budget.consume(Units.of(1), Allowance.of(0)));
        budget.consume(Units.of(3), Allowance.of(3));
        assertThrows(RestrictionFailure.class, () -> budget.reserve(Units.of(1), Allowance.of(3)));
        budget.consume(Units.of(10), Allowance.of(-1)); assertEquals(13, budget.consumed().value());
        assertThrows(RestrictionFailure.class, () -> MeteringRules.requireRoom(Long.MAX_VALUE, 0, 1, -1));
        assertFalse(MeteringRules.validTotal(Long.MAX_VALUE, 1));
    }
    @Test void reservationsConsumeRoomUntilConfirmedOrKnownUnused() {
        var budget = budget(); budget.reserve(Units.of(3), Allowance.of(5));
        assertThrows(RestrictionFailure.class, () -> budget.consume(Units.of(3), Allowance.of(5)));
        budget.confirm(Units.of(2)); assertEquals(2, budget.consumed().value()); assertEquals(1, budget.reserved().value());
        budget.release(Units.of(1)); assertEquals(0, budget.reserved().value());
        assertThrows(RestrictionFailure.class, () -> budget.release(Units.of(1)));
    }
    @Test void keysBindAccountBenefitWindowAndLogicalOperationsAcrossPeriodEdges() {
        var account = AccountId.create(); var operation = UsageOperationKey.of("export:one");
        assertEquals(MeteringKeys.budget(account, EXPORTS, WINDOW), MeteringKeys.budget(account, EXPORTS, WINDOW));
        assertNotEquals(MeteringKeys.budget(account, EXPORTS, WINDOW), MeteringKeys.budget(account, EXPORTS, UsageWindow.of(WINDOW.end(), WINDOW.end().plusSeconds(60))));
        assertNotEquals(MeteringKeys.budget(account, EXPORTS, WINDOW), MeteringKeys.budget(AccountId.create(), EXPORTS, WINDOW));
        assertFalse(MeteringKeys.reservation(account, operation).toString().contains(operation.encoded()));
    }
    @Test void reservationFinalStatesCannotBeReversed() {
        var budget = budget(); var account = budget.account();
        var row = QuotaReservation.create(MeteringKeys.reservation(account, UsageOperationKey.of("one")), budget.id(), account, EXPORTS, WINDOW, Units.of(2), ReservationStatus.RESERVED);
        row.confirm(); assertEquals(ReservationStatus.CONFIRMED, row.status());
        assertThrows(RestrictionFailure.class, row::release); assertThrows(RestrictionFailure.class, row::confirm);
    }
}
