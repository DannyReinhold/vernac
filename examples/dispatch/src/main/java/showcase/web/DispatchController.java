package showcase.web;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import showcase.integration.CarrierSimulator;
import showcase.routing.domain.*;
import showcase.routing.usecase.*;

/** HTTP DTOs are deliberately separate from domain objects and usecase contracts. */
@RestController
@RequestMapping("/api")
public class DispatchController {
  private final TourRepository tours;
  private final CreateTour create;
  private final AddStop add;
  private final MoveStop move;
  private final ReleaseTour release;
  private final DispatchTour dispatch;
  private final NamedParameterJdbcTemplate jdbc;
  private final CarrierSimulator carrier;

  public DispatchController(
      TourRepository tours,
      CreateTour create,
      AddStop add,
      MoveStop move,
      ReleaseTour release,
      DispatchTour dispatch,
      NamedParameterJdbcTemplate jdbc,
      CarrierSimulator carrier) {
    this.tours = tours;
    this.create = create;
    this.add = add;
    this.move = move;
    this.release = release;
    this.dispatch = dispatch;
    this.jdbc = jdbc;
    this.carrier = carrier;
  }

  public record NewTour(String title, int capacity) {}

  public record NewStop(String customer, String street, String city, int units) {}

  public record Transfer(UUID source, UUID target, UUID stop) {}

  public record StopView(String id, String customer, String street, String city, int units) {}

  public record TourView(
      String id, String title, String state, int load, int capacity, List<StopView> stops) {}

  public record Board(
      List<TourView> tours,
      List<Map<String, Object>> receipts,
      List<Map<String, Object>> deliveries) {}

  @GetMapping("/board")
  @Transactional(readOnly = true)
  public Board board(@RequestParam(defaultValue = "") String search) {
    var found = search.isBlank() ? tours.all() : tours.search(search);
    var views =
        found.stream()
            .map(
                t ->
                    new TourView(
                        t.id().asString(),
                        t.title().string(),
                        t.tourState().name(),
                        t.totalUnits(),
                        t.capacity().intValue(),
                        t.stops().stream()
                            .map(
                                s ->
                                    new StopView(
                                        s.id().asString(),
                                        s.customer().string(),
                                        s.address().street(),
                                        s.address().city(),
                                        s.units().intValue()))
                            .toList()))
            .toList();
    var receipts =
        jdbc.queryForList(
            "SELECT title, quote_reference, amount, currency, received_at FROM dispatch_receipt"
                + " ORDER BY received_at DESC LIMIT 30",
            Map.of());
    var deliveries =
        jdbc.queryForList(
            "SELECT status, attempts, \"listenerId\" AS listener, \"lastError\" AS error FROM"
                + " \"vernac\".\"@delivery\" ORDER BY \"createdAt\" DESC LIMIT 30",
            Map.of());
    return new Board(views, receipts, deliveries);
  }

  @PostMapping("/tours")
  public Map<String, String> create(@RequestBody NewTour input) {
    return Map.of(
        "id", create.execute(Title.of(input.title()), Capacity.of(input.capacity())).asString());
  }

  @PostMapping("/tours/{id}/stops")
  public void add(@PathVariable UUID id, @RequestBody NewStop input) {
    add.execute(
        TourId.of(id),
        Customer.of(input.customer()),
        Address.of(input.street(), input.city()),
        Units.of(input.units()));
  }

  @PostMapping("/move")
  public void move(@RequestBody Transfer input) {
    move.execute(TourId.of(input.source()), TourId.of(input.target()), StopId.of(input.stop()));
  }

  @PostMapping("/tours/{id}/release")
  public void release(@PathVariable UUID id) {
    release.execute(TourId.of(id));
  }

  @PostMapping("/tours/{id}/dispatch")
  public void dispatch(@PathVariable UUID id) {
    dispatch.execute(TourId.of(id));
  }

  @PostMapping("/carrier/fail-next")
  public void failNext() {
    carrier.failNextRequest();
  }
}
