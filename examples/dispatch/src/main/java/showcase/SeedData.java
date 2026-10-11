package showcase;

import java.util.Map;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import showcase.routing.domain.*;
import showcase.routing.usecase.*;

@Component
@org.springframework.core.annotation.Order(0)
public class SeedData implements CommandLineRunner {
  private final TourRepository tours;
  private final CreateTour create;
  private final AddStop add;
  private final NamedParameterJdbcTemplate jdbc;

  public SeedData(
      TourRepository tours, CreateTour create, AddStop add, NamedParameterJdbcTemplate jdbc) {
    this.tours = tours;
    this.create = create;
    this.add = add;
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void run(String... args) {
    jdbc.queryForObject("SELECT pg_advisory_xact_lock(720020) IS NULL", Map.of(), Boolean.class);
    if (!tours.all().isEmpty()) return;
    var north = create.execute(Title.of("North · Riverside"), Capacity.of(24));
    var south = create.execute(Title.of("South · Old Town"), Capacity.of(18));
    var express = create.execute(Title.of("Express · City Centre"), Capacity.of(8));
    add.execute(
        north, Customer.of("Harbour Coffee"), Address.of("Am Wall 8", "Bremen"), Units.of(8));
    add.execute(
        north, Customer.of("Studio Nord"), Address.of("Überseetor 12", "Bremen"), Units.of(6));
    add.execute(south, Customer.of("Market Hall"), Address.of("Markt 1", "Bremen"), Units.of(9));
    add.execute(
        express, Customer.of("Atelier West"), Address.of("Langenstraße 4", "Bremen"), Units.of(3));
    System.getLogger(getClass().getName())
        .log(
            System.Logger.Level.INFO,
            "Dispatch seeded. Open http://localhost:8087 — all carrier calls are local"
                + " simulations.");
  }
}
