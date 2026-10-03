package org.vernac.example.home.energy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.vernac.example.home.energy.domain.*;
import org.vernac.example.home.energy.usecase.OptimizeEnergyFlow;

@Component
public class HomeEnergyDemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HomeEnergyDemoRunner.class);

    private final EnergyStorageRepository storageRepository;
    private final OptimizeEnergyFlow optimizeEnergyFlow;

    public HomeEnergyDemoRunner(
            EnergyStorageRepository storageRepository,
            OptimizeEnergyFlow optimizeEnergyFlow
    ) {
        this.storageRepository = storageRepository;
        this.optimizeEnergyFlow = optimizeEnergyFlow;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("==================================================================");
        log.info("🚀 VERNAC HOME ENERGY SHOWCASE GESTARTET");
        log.info("==================================================================");

        // 1. Initialzustand für EnergyStorage in PostgreSQL anlegen
        StorageId storageId = StorageId.create();
        WattHours capacity = WattHours.of(10_000);
        BatterySoc initialSoc = BatterySoc.of(25);
        WattHours initialReserve = WattHours.of(2_500);

        log.info("1. Initialisiere Speicher {} mit 2.500 Wh (25% SOC)...", storageId.value());
        EnergyStorage initialStorage = EnergyStorage.create(storageId, capacity, initialSoc, initialReserve);
        storageRepository.save(initialStorage);
        log.info("   -> Gespeichert in DB mit Version {}", initialStorage.version());

        // 2. UseCase aufrufen: Lädt Speicher, ruft REST-Port ab, lädt auf und speichert
        log.info("2. Führe UseCase 'OptimizeEnergyFlow' aus...");
        OptimizeEnergyFlow.Result result = optimizeEnergyFlow.execute(storageId);

        log.info("   -> UseCase abgeschlossen! Generiertes Result-Record:");
        log.info("      StorageId:     {}", result.id().value());
        log.info("      Neuer SOC:     {}%", result.soc().percent());
        log.info("      Energie-Stand: {} Wh", result.storedEnergy().value());

        // 3. Zur Gegenprobe frisch aus DB lesen
        EnergyStorage reloaded = storageRepository.byId(storageId);
        log.info("3. DB-Prüfung nach Transaktion: SOC={}%, Version={}",
                reloaded.soc().percent(), reloaded.version());

        log.info("==================================================================");
        log.info("✅ DURCHSTICH ERFOLGREICH ABGESCHLOSSEN");
        log.info("==================================================================");
    }
}