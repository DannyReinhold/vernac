package org.example.delivery.application;

import org.example.delivery.domain.Notes;
import org.example.delivery.domain.TourId;
import org.example.delivery.domain.TourRepository;
import org.example.delivery.usecase.RecordTourNotesExternally.Result;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class RecordTourNotesImplementation {

    private RecordTourNotesImplementation() {
    }

    public static Result execute(
            TourId tourId,
            Notes notes,
            TourRepository tours
    ) {
        var tour = tours.byId(tourId);
        tour.recordNotes(notes);
        tours.save(tour);

        return Result.of(tour.id(), tour.notes().orElseThrow());
    }
}