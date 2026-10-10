package org.example.delivery.demo;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.example.delivery.domain.*;

@Component
@ConditionalOnProperty(name="demo.run",havingValue="true",matchIfMissing=true)
public class PersistenceDemoRunner implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(PersistenceDemoRunner.class);
    private final DeliveryDemoUseCases usecases;
    public PersistenceDemoRunner(DeliveryDemoUseCases usecases) { this.usecases=usecases; }
    @Override public void run(ApplicationArguments args) {
        String runName="Query demo " + UUID.randomUUID();
        TourId id=usecases.create(Title.of(runName + " A"),TourStatus.PLANNED);
        Tour initial=usecases.load(id);
        Stop first=initial.allStops().get(0),second=initial.allStops().get(2);
        require(first==initial.allStops().get(1),"Repeated list entries share the same instance");
        require(first==initial.niceStops().get(0),"Independent collections share entity instances");
        require(first==initial.preferredStop().orElseThrow(),"Direct references share the same entity instance");
        require(first.parcels().iterator().next()==second.parcels().iterator().next(),"Subentities are shared too");
        require(initial.price().amount().scale()==2,"BigDecimal scale survives the round trip");
        log.info("Created and loaded Tour {}: {}",id.asString(),initial);

        demonstrateQueries(initial,runName);

        usecases.changeAndReorder(id);
        Tour changed=usecases.load(id);
        require(changed.preferredStop().orElseThrow().address().street().equals("Changed Street"),"Entity changes survive reload");
        try { usecases.saveDetached(initial); throw new IllegalStateException("Expected optimistic lock conflict"); }
        catch(OptimisticLockingFailureException expected) { log.info("Expected optimistic lock conflict for stale snapshot"); }
        try { usecases.conflictingInstances(id); throw new IllegalStateException("Expected identity conflict"); }
        catch(IllegalArgumentException expected) { log.info("Expected conflicting-entity-instance rejection: {}",expected.getMessage()); }

        usecases.clearRecommendations(id);
        require(usecases.load(id).allStops().size()==3,"Removing one relationship preserves shared state");
        usecases.removeStop(id,first.id());
        Tour remaining=usecases.load(id);
        require(remaining.allStops().size()==1,"All references to the first stop were removed");
        require(remaining.allStops().get(0).parcels().size()==1,"Shared parcel remains reachable through second stop");
        usecases.removeStop(id,second.id());
        require(usecases.load(id).allStops().isEmpty(),"Last stop was removed");
        log.info("Persistence walkthrough passed. Tour {} remains in the database for the migration tutorial.",id.asString());
    }
    private void demonstrateQueries(Tour initial,String runName) {
        TourId completed=usecases.create(Title.of(runName + " B"),TourStatus.COMPLETED);
        try {
            TourId planned=usecases.create(Title.of(runName + " C"),TourStatus.PLANNED);
            try {
                Set<TourId> thisRun=Set.of(initial.id(),completed,planned);
                // The repository searches the whole database. Restrict assertions/logs to this run's fixtures.
                List<Tour> matching=usecases.withStatus(TourStatus.PLANNED).stream()
                        .filter(tour -> thisRun.contains(tour.id())).toList();
                require(matching.stream().map(Tour::id).toList().equals(List.of(initial.id(),planned)),
                        "Status equality returns planned tours in title order");
                log.info("Query withStatus(PLANNED), ordered by title: {}",
                        matching.stream().map(tour -> tour.title().string()).toList());
                Tour loaded=matching.getFirst();
                require(loaded.allStops().get(0)==loaded.allStops().get(1)
                                && loaded.allStops().get(0)==loaded.preferredStop().orElseThrow(),
                        "Query results contain complete graphs with shared child instances");

                var different=usecases.otherStatus(TourStatus.PLANNED).stream()
                        .filter(tour -> thisRun.contains(tour.id())).map(Tour::id).toList();
                require(different.equals(List.of(completed)),"Status inequality finds the completed tour");
                log.info("Query otherStatus(PLANNED): {}",different);

                var recent=usecases.createdSince(initial.createdAt()).stream()
                        .filter(tour -> thisRun.contains(tour.id())).toList();
                require(recent.stream().map(Tour::id).collect(java.util.stream.Collectors.toSet()).equals(thisRun),
                        "createdAt >= cutoff includes the boundary tour and both newer tours");
                for(int i=1;i<recent.size();i++) require(!recent.get(i-1).createdAt().isBefore(recent.get(i).createdAt()),
                        "Timestamp results are sorted newest first");
                log.info("Query createdSince({}): {}",initial.createdAt(),
                        recent.stream().map(tour -> tour.title().string()).toList());

                require(usecases.titled(initial.title()).orElseThrow().id().equals(initial.id()),
                        "Optional title query returns its single match");
                require(usecases.titled(Title.of(runName + " missing")).isEmpty(),
                        "Optional title query returns empty for no match");
                log.info("Optional queries: existing title found; missing title returned Optional.empty()");
            } finally { usecases.delete(planned); }
        } finally { usecases.delete(completed); }
    }
    private static void require(boolean condition,String message) {
        if(!condition) throw new IllegalStateException(message);
        log.info("Verified: {}",message);
    }
}
