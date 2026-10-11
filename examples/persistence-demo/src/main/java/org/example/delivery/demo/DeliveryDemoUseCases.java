package org.example.delivery.demo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Currency;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.example.delivery.domain.*;

/** Usecases own transactions; the generated repository requires one. */
@Service
@Transactional
public class DeliveryDemoUseCases {
    private final TourRepository tours;
    public DeliveryDemoUseCases(TourRepository tours) { this.tours=tours; }

    public TourId create() { return create(Title.of("Delivery demo"),TourStatus.PLANNED); }

    public TourId create(Title title,TourStatus status) {
        Parcel shared=Parcel.create(TrackingNumber.of("SHARED"),Weight.of(new BigDecimal("1.250")));
        Stop first=Stop.create(Address.of("First Street","Bremen"),Parcels.of(shared));
        Stop second=Stop.create(Address.of("Second Street","Bremen"),Parcels.of(shared));
        Tour tour=Tour.create(title,Money.of(new BigDecimal("12.30"),Currency.getInstance("EUR")),
                Tags.of(Tag.of("fragile"),Tag.of("fragile")),status,
                Stops.of(first,first,second),Stops.of(first));
        tour.prefer(first.id());
        tours.save(tour);
        return tour.id();
    }
    @Transactional(readOnly = true)
    public Tour load(TourId id) { return tours.byId(id); }
    @Transactional(readOnly = true)
    public Tours withStatus(TourStatus status) { return tours.withStatus(status); }
    @Transactional(readOnly = true)
    public Tours otherStatus(TourStatus status) { return tours.otherStatus(status); }
    @Transactional(readOnly = true)
    public Tours createdSince(Instant cutoff) { return tours.createdSince(cutoff); }
    @Transactional(readOnly = true)
    public Optional<Tour> titled(Title title) { return tours.titled(title); }

    @Transactional(readOnly = true)
    public Tours titledLike(String pattern) { return tours.titledLike(pattern); }
    @Transactional(readOnly = true)
    public Tours titleContains(String text) { return tours.titleContains(text); }
    @Transactional(readOnly = true)
    public Tours titleStartsWith(String text) { return tours.titleStartsWith(text); }
    @Transactional(readOnly = true)
    public Tours titleEndsWith(String text) { return tours.titleEndsWith(text); }

    public void changeAndReorder(TourId id) {
        Tour tour=tours.byId(id);
        tour.changeAddress(tour.allStops().get(0).id(),Address.of("Changed Street","Bremen"));
        tour.reverseStops();
        tours.save(tour);
    }
    public void recordNotes(TourId id) { Tour tour=tours.byId(id);tour.recordNotes(Notes.of(null,null));tours.save(tour); }
    public void clearRecommendations(TourId id) { Tour tour=tours.byId(id);tour.clearRecommendations();tours.save(tour); }
    public void removeStop(TourId id,StopId stopId) { Tour tour=tours.byId(id);tour.removeStop(stopId);tours.save(tour); }
    public void saveDetached(Tour tour) { tours.save(tour); }
    public void delete(TourId id) { tours.delete(tours.byId(id)); }
    public void conflictingInstances(TourId id) {
        Tour tour=tours.byId(id); Stop first=tour.allStops().get(0);
        Stop competing=Stop.reconstitute(first.id(),Address.of("Conflicting snapshot","Bremen"),first.parcels());
        tour.addStop(competing);
        tours.save(tour);
    }
}
