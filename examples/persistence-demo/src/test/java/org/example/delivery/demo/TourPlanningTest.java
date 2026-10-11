package org.example.delivery.demo;

import org.example.delivery.domain.*;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.DomainValidationException;

import java.math.BigDecimal;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.*;

/** Domain-service behavior without Spring, JDBC or a database. */
class TourPlanningTest {
    private Tour tour(String title, Stops stops) {
        return Tour.create(Title.of(title), Money.of(new BigDecimal("12.30"),
                Currency.getInstance("EUR")), Tags.empty(), TourStatus.PLANNED,
                stops, Stops.empty());
    }

    @Test void movesTheSameStopAndClearsSourceRecommendations() {
        var stop = Stop.create(Address.of("First Street", "Bremen"), Parcels.empty());
        var source = tour("Source", Stops.of(stop));
        var target = tour("Target", Stops.empty());
        source.prefer(stop.id());

        new TourPlanning().moveStop(source, target, stop.id());

        assertTrue(source.allStops().isEmpty());
        assertTrue(source.preferredStop().isEmpty());
        assertSame(stop, target.allStops().by(stop.id()).orElseThrow());
    }

    @Test void rejectsSameTourBeforeRemovingItsStop() {
        var stop = Stop.create(Address.of("First Street", "Bremen"), Parcels.empty());
        var tour = tour("Only tour", Stops.of(stop));
        assertThrows(DomainValidationException.class,
                () -> new TourPlanning().moveStop(tour, tour, stop.id()));
        assertSame(stop, tour.allStops().by(stop.id()).orElseThrow());
    }
}
