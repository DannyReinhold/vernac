package org.example.delivery.demo;

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
        TourId id=usecases.create();
        Tour initial=usecases.load(id);
        Stop first=initial.allStops().get(0),second=initial.allStops().get(2);
        require(first==initial.allStops().get(1),"Repeated list entries share the same instance");
        require(first==initial.niceStops().get(0),"Independent collections share entity instances");
        require(first==initial.preferredStop().orElseThrow(),"Direct references share the same entity instance");
        require(first.parcels().iterator().next()==second.parcels().iterator().next(),"Subentities are shared too");
        require(initial.price().amount().scale()==2,"BigDecimal scale survives the round trip");
        log.info("Created and loaded Tour {}: {}",id.asString(),initial);

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
    private static void require(boolean condition,String message) {
        if(!condition) throw new IllegalStateException(message);
        log.info("Verified: {}",message);
    }
}
