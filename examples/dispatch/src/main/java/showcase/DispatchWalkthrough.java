package showcase;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.vernac.runtime.DomainValidationException;
import showcase.integration.CarrierSimulator;
import showcase.routing.domain.*;
import showcase.routing.usecase.*;

/** Exercises the same generated usecases as the UI, with actual database transactions. */
@Component
public class DispatchWalkthrough {
  private final CreateTour create;
  private final AddStop add;
  private final MoveStop move;
  private final ReleaseTour release;
  private final DispatchTour dispatch;
  private final TourRepository tours;
  private final CarrierSimulator carrier;
  private final NamedParameterJdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public DispatchWalkthrough(
      CreateTour create,
      AddStop add,
      MoveStop move,
      ReleaseTour release,
      DispatchTour dispatch,
      TourRepository tours,
      CarrierSimulator carrier,
      NamedParameterJdbcTemplate jdbc,
      PlatformTransactionManager manager) {
    this.create = create;
    this.add = add;
    this.move = move;
    this.release = release;
    this.dispatch = dispatch;
    this.tours = tours;
    this.carrier = carrier;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(manager);
  }

  public void run() throws InterruptedException {
    String run = UUID.randomUUID().toString().substring(0, 8);
    var source = create.execute(Title.of("Walkthrough " + run + " North"), Capacity.of(20));
    var small = create.execute(Title.of("Walkthrough " + run + " Express"), Capacity.of(3));
    var target = create.execute(Title.of("Walkthrough " + run + " South"), Capacity.of(20));
    add.execute(
        source, Customer.of("Demo customer"), Address.of("Demo street", "Bremen"), Units.of(8));
    var stop = tx.execute(s -> tours.byId(source).stops().first().orElseThrow().id());
    try {
      move.execute(source, small, stop);
      throw new AssertionError("Capacity rejection missing");
    } catch (DomainValidationException expected) {
    }
    check(
        Boolean.TRUE.equals(
            tx.execute(
                s ->
                    tours.byId(source).stops().contains(stop)
                        && tours.byId(small).stops().isEmpty())),
        "Rejected move rolls back without losing the stop");
    move.execute(source, target, stop);
    check(
        Boolean.TRUE.equals(
            tx.execute(
                s ->
                    tours.byId(source).stops().isEmpty()
                        && tours.byId(target).stops().contains(stop))),
        "Transfer persists both aggregate changes");
    release.execute(target);
    carrier.failNextRequest();
    dispatch.execute(target);
    check(
        tx.execute(s -> tours.byId(target).tourState()) == TourState.DISPATCHED,
        "Dispatch commits before deferred carrier work completes");
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      Integer count =
          jdbc.queryForObject(
              "SELECT count(*) FROM dispatch_receipt WHERE tour_id=:id",
              Map.of("id", target.value()),
              Integer.class);
      if (count != null && count == 1) break;
      Thread.sleep(100);
    }
    check(
        jdbc.queryForObject(
                "SELECT count(*) FROM dispatch_receipt WHERE tour_id=:id",
                Map.of("id", target.value()),
                Integer.class)
            == 1,
        "Carrier receipt committed after retry");
    var jobs =
        jdbc.queryForList(
            """
            SELECT d.status,d.attempts FROM "vernac"."@delivery" d
            JOIN "showcase.routing"."@event:TourDispatched" e ON e."@eventId"=d."eventId"
            WHERE e."tourId"=:id
            """,
            Map.of("id", target.value()));
    check(
        jobs.size() == 1
            && "SUCCEEDED".equals(jobs.getFirst().get("status"))
            && ((Number) jobs.getFirst().get("attempts")).intValue() == 2,
        "One durable delivery succeeds on its second attempt");
    try {
      dispatch.execute(target);
      throw new AssertionError("Repeated dispatch accepted");
    } catch (DomainValidationException expected) {
    }
    check(
        jdbc.queryForObject(
                "SELECT count(*) FROM \"showcase.routing\".\"@event:TourDispatched\" WHERE"
                    + " \"tourId\"=:id",
                Map.of("id", target.value()),
                Integer.class)
            == 1,
        "Rejected repeated dispatch creates no extra event");
  }

  private static void check(boolean result, String message) {
    if (!result) throw new AssertionError(message);
    System.getLogger(DispatchWalkthrough.class.getName())
        .log(System.Logger.Level.INFO, "Verified: " + message);
  }
}
