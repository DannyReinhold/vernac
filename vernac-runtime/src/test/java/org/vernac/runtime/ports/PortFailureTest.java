// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.ports;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.VernacDomainException;
import org.vernac.runtime.VernacTechnicalException;
import static org.junit.jupiter.api.Assertions.*;

class PortFailureTest {
    enum Shipping { BLOCKED }
    enum Robot { BLOCKED }

    @Test void businessCodesRetainTheirEnumTypeAndSelectedDiagnosticsAreImmutable() {
        var invocation = AdapterInvocation.create("Shipping", "Carrier", "ship")
                .withDetail("parcelId", "42");
        var details = new HashMap<>(Map.of("vendor.code", "E42"));
        var cause = new IllegalStateException("vendor failure");
        var failure = new PortRejectedException(invocation, Shipping.BLOCKED, "Shipping rejected", details, cause);
        details.put("vendor.code", "changed");
        assertInstanceOf(VernacDomainException.class, failure);
        assertSame(invocation, failure.invocation());
        assertSame(cause, failure.getCause());
        assertEquals(Shipping.BLOCKED, failure.code());
        assertEquals(Shipping.BLOCKED, failure.code(Shipping.class).orElseThrow());
        assertTrue(failure.code(Robot.class).isEmpty());
        assertEquals("E42", failure.transportDetails().get("vendor.code"));
        assertThrows(UnsupportedOperationException.class, () -> failure.transportDetails().put("x", "y"));
    }

    @Test void nonHttpAdaptersUseTheSameFailureContractWithoutTransportDependencies() {
        var invocation = AdapterInvocation.create("Arm", "VendorRobot", "move");
        var local = new LocalIntegrationException(invocation, IntegrationCategory.CONFIGURATION, "Calibration unavailable");
        var remote = new RemoteIntegrationException(invocation, IntegrationCategory.REMOTE_FAILURE, "Controller failed");
        var communication = new IntegrationCommunicationException(invocation, IntegrationCategory.COMMUNICATION, "Connection lost");
        for (var failure : new PortInvocationException[]{local, remote, communication}) {
            assertInstanceOf(VernacTechnicalException.class, failure);
            assertSame(invocation, failure.invocation());
            assertTrue(failure.transportDetails().isEmpty());
        }
    }
}
