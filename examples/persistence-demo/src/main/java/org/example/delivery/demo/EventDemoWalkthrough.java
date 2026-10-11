package org.example.delivery.demo;

import java.time.Duration;
import java.util.*;
import org.example.delivery.domain.*;
import org.example.delivery.usecase.PublishDemoNotice;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs outside a transaction while awaiting the real, automatically scheduled worker. */
@Component
public final class EventDemoWalkthrough {
    private static final Logger log=LoggerFactory.getLogger(EventDemoWalkthrough.class);
    private static final String JOBS="\"vernac\".\"@delivery\"";
    private final PublishDemoNotice publisher;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public EventDemoWalkthrough(PublishDemoNotice publisher,NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.publisher=publisher;this.jdbc=jdbc;transaction=new TransactionTemplate(manager);
    }
    public void run() throws InterruptedException {
        String title="Event demo "+UUID.randomUUID();
        DemoNotice committed=Objects.requireNonNull(transaction.execute(status->{
            publisher.execute(Title.of(title));
            var event=EventDemoProbe.takeObserved();
            var jobs=jobs(event);
            require(jobs.size()==2 && jobs.stream().allMatch(j->"PENDING".equals(j.get("status"))),
                    "Both delivery jobs are pending inside the publisher transaction");
            return event;
        }));
        awaitSuccess(committed);
        var jobs=jobs(committed);
        for(var job:jobs) {
            String listener=(String)job.get("listenerId");
            int expected=listener.endsWith(".RetryDemoNotice")?2:1;
            require(((Number)job.get("attempts")).intValue()==expected,"Expected attempts for "+listener);
        }
        require(eventCount(committed)==1,"One recorded event supplies both successful delivery jobs");
        EventDemoProbe.forget(committed.eventId());

        DemoNotice rolledBack=Objects.requireNonNull(transaction.execute(status->{
            publisher.execute(Title.of(title+" rolled back"));
            var event=EventDemoProbe.takeObserved();status.setRollbackOnly();return event;
        }));
        require(eventCount(rolledBack)==0 && jobs(rolledBack).isEmpty(),
                "Publisher rollback removed the event and both delivery jobs");
        log.info("Event walkthrough passed. Recorded event {} and two SUCCEEDED jobs remain for inspection.",committed.eventId().asString());
    }
    private void awaitSuccess(DemoNotice event) throws InterruptedException {
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        do {
            var jobs=jobs(event);
            if(jobs.size()==2 && jobs.stream().allMatch(j->"SUCCEEDED".equals(j.get("status")))) return;
            if(jobs.stream().anyMatch(j->Set.of("FAILED","BLOCKED").contains(j.get("status"))))
                throw new IllegalStateException("Event delivery failed: "+jobs);
            Thread.sleep(100);
        } while(System.nanoTime()<deadline);
        throw new IllegalStateException("Timed out waiting for the event worker. Check vernac.events.enabled and queue backlog: "+jobs(event));
    }
    private List<Map<String,Object>> jobs(DemoNotice event) {
        return jdbc.queryForList("SELECT \"listenerId\",status,attempts,\"lastError\" FROM "+JOBS+
                " WHERE \"eventType\"='org.example.delivery.DemoNotice' AND \"eventId\"=:id ORDER BY \"listenerId\"",
                Map.of("id",event.eventId().value()));
    }
    private int eventCount(DemoNotice event) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM \"org.example.delivery\".\"@event:DemoNotice\" WHERE \"@eventId\"=:id",
                Map.of("id",event.eventId().value()),Integer.class));
    }
    private static void require(boolean condition,String message) {
        if(!condition) throw new IllegalStateException(message);
        log.info("Verified: {}",message);
    }
}
