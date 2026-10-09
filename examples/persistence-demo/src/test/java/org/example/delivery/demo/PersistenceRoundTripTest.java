package org.example.delivery.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.example.delivery.domain.*;
import org.vernac.runtime.AggregateNotFoundException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.docker.compose.enabled=false","demo.run=false",
        "spring.datasource.url=${vernac.test.url:jdbc:postgresql://localhost:55434/delivery_test}",
        "spring.datasource.username=${VERNAC_TEST_USER:delivery}","spring.datasource.password=${VERNAC_TEST_PASSWORD:local-demo-only}",
        "spring.datasource.hikari.maximum-pool-size=2"})
@EnabledIfSystemProperty(named="vernac.test.database",matches="true")
class PersistenceRoundTripTest {
    @Autowired DeliveryDemoUseCases usecases;
    @Autowired TourRepository repository;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test void sharedGraphOrderAndReachabilityCleanup() {
        TourId id=usecases.create();
        Tour tour=usecases.load(id);
        Stop first=tour.allStops().get(0),second=tour.allStops().get(2);
        assertSame(first,tour.allStops().get(1));
        assertSame(first,tour.niceStops().get(0));
        assertSame(first,tour.preferredStop().orElseThrow());
        assertSame(first.parcels().iterator().next(),second.parcels().iterator().next());
        assertEquals(2,tour.price().amount().scale());
        assertEquals(tour.createdAt(),usecases.load(id).createdAt());
        assertEquals(2,tour.tags().size());
        assertTrue(tour.notes().isEmpty());
        usecases.recordNotes(id);
        assertTrue(usecases.load(id).notes().isPresent());
        assertTrue(usecases.load(id).notes().orElseThrow().description().isEmpty());
        usecases.changeAndReorder(id);
        Tour changed=usecases.load(id);
        assertEquals(second.id(),changed.allStops().get(0).id());
        assertEquals("Changed Street",changed.preferredStop().orElseThrow().address().street());
        usecases.clearRecommendations(id);
        assertEquals(2,count("Tour.@entity:org.example.delivery.Stop",id));
        usecases.removeStop(id,first.id());
        assertEquals(1,count("Tour.@entity:org.example.delivery.Stop",id));
        assertEquals(1,count("Tour.@entity:org.example.delivery.Parcel",id));
        usecases.removeStop(id,second.id());
        assertEquals(0,count("Tour.@entity:org.example.delivery.Stop",id));
        assertEquals(0,count("Tour.@entity:org.example.delivery.Parcel",id));
        assertEquals(0,count("Tour.@entity:org.example.delivery.Stop.parcels",id));
        usecases.delete(id);
        assertThrows(AggregateNotFoundException.class,()->usecases.load(id));
    }
    @Test void staleSnapshotsAndDuplicateInstancesDoNotOverwriteData() {
        TourId id=usecases.create(); Tour stale=usecases.load(id);
        usecases.changeAndReorder(id);
        assertThrows(OptimisticLockingFailureException.class,()->usecases.saveDetached(stale));
        long version=usecases.load(id).persistenceState().version();
        assertThrows(IllegalArgumentException.class,()->usecases.conflictingInstances(id));
        assertEquals(version,usecases.load(id).persistenceState().version());
        assertEquals(2,count("Tour.@entity:org.example.delivery.Stop",id));
        usecases.delete(id);
        assertThrows(OptimisticLockingFailureException.class,()->usecases.saveDetached(stale));
    }
    @Test void rollbackRestoresDatabaseButNotTheSuppliedJavaObject() {
        TourId id=usecases.create(); Tour supplied=usecases.load(id);
        long original=supplied.persistenceState().version();
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            supplied.clearRecommendations(); repository.save(supplied); status.setRollbackOnly();
        });
        assertEquals(original+1,supplied.persistenceState().version());
        Tour fresh=usecases.load(id);
        assertEquals(original,fresh.persistenceState().version());
        assertTrue(fresh.preferredStop().isPresent());
        usecases.delete(id);
    }
    @Test void repositoriesRequireAUsecaseTransaction() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                ()->repository.byId(TourId.create()));
    }
    private long count(String table,TourId id) {
        return jdbc.queryForObject("SELECT count(*) FROM \"org.example.delivery\".\""+table+"\" WHERE \"@aggregateId\"=:id",Map.of("id",id.value()),Long.class);
    }
}
