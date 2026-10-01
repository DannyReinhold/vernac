package org.vernac.example.home.energy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.vernac.example.home.energy.domain.*;

import java.util.Optional;

@Component
public class HomeEnergyDemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HomeEnergyDemoRunner.class);

    private final EnergyStorageRepository storageRepository;
    private final SolarForecastProvider solarForecastProvider;

    public HomeEnergyDemoRunner(
            EnergyStorageRepository storageRepository,
            SolarForecastProvider solarForecastProvider
    ) {
        this.storageRepository = storageRepository;
        this.solarForecastProvider = solarForecastProvider;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info("==================================================================");
        log.info("🚀 VERNAC HOME ENERGY DEMO GESTARTET");
        log.info("==================================================================");

        // 1. Typsichere IDs und Value Objects erzeugen
        StorageId storageId = StorageId.create();
        WattHours capacity = WattHours.of(10_000);
        BatterySoc soc = BatterySoc.of(55);
        WattHours currentReserve = WattHours.of(2_500);

        log.info("1. Erzeuge EnergyStorage-Aggregat mit ID: {}", storageId.value());
        EnergyStorage storage = EnergyStorage.create(storageId, capacity, soc, currentReserve);

        // 2. Im PostgreSQL-Repository speichern
        log.info("2. Speichere Aggregat in PostgreSQL...");
        storageRepository.save(storage);
        log.info("   -> Gespeichert mit Version: {}", storage.version());

        // 3. Outbound REST-Port abfragen
        log.info("3. Frage SolarForecastProvider via generiertem REST-Adapter ab...");
        Optional<WattHours> forecast = solarForecastProvider.fetchExpectedYield(storageId);
        forecast.ifPresentOrElse(
                yield -> log.info("   -> Erwarteter Ertrag erhalten: {} Wh", yield.value()),
                () -> log.warn("   -> Keine Vorhersage gefunden!")
        );

        // 4. Aus der Datenbank neu laden
        log.info("4. Lade Storage aus PostgreSQL neu...");
        EnergyStorage loaded = storageRepository.byId(storageId);
        log.info("   -> Erfolgreich geladen: ID={}, Kapazität={} Wh, SOC={}%",
                loaded.id().value(), loaded.capacity().value(), loaded.batterySoc().percent());

        log.info("==================================================================");
        log.info("✅ DURCHSTICH ERFOLGREICH ABGESCHLOSSEN");
        log.info("==================================================================");
    }
}