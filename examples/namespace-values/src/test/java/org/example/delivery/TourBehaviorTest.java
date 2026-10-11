package org.example.delivery;

import org.example.delivery.domain.*;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.DomainValidationException;

import static org.junit.jupiter.api.Assertions.*;

class TourBehaviorTest {
    @Test void modifiesTheExistingChildWithoutReplacingItsIdentity() {
        var stop = Stop.create(Address.of("Bremen"), Units.of(10));
        var id = stop.id();
        var tour = Tour.create(TourName.of("Morning tour"), Stops.of(stop));

        tour.changeDelivery(id, Units.of(40));

        assertSame(stop, tour.stops().by(id).orElseThrow());
        assertEquals(id, stop.id());
        assertEquals(40, stop.units().intValue());
        assertTrue(tour.note().isEmpty());
    }

    @Test void rejectsInvalidCreationAndDoesNotPromiseMutationRollback() {
        assertThrows(DomainValidationException.class,
                () -> Stop.create(Address.of("Bremen"), Units.of(-1)));
        var stop = Stop.create(Address.of("Bremen"), Units.of(10));
        var tour = Tour.create(TourName.of("Morning tour"), Stops.of(stop));
        assertThrows(DomainValidationException.class,
                () -> tour.changeDelivery(stop.id(), Units.of(-1)));
        // Current contract: validation rejects the operation but does not restore state.
        assertEquals(-1, stop.units().intValue());
    }
}
