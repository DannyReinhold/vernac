---
title: SaaS – automatisierte Testabdeckung
---
# Automatisierte Testabdeckung

Die eigenständige Dokumentanwendung besitzt 29 benannte Playwright-Szenarien.
Jedes startet eine eigene Datenbank mit eingeschränktem Laufzeitbenutzer und eine
HTTPS-Anwendung. Es gibt keine Abhängigkeit von Ergebnissen vorheriger Szenarien.
Die Schritte führen Kundenaktionen über Browser beziehungsweise HTTP mit echter
Sitzung und CSRF-Schutz aus. Geschäftsentscheidungen, generierter Code,
Transaktionen, PostgreSQL, Worker, PDF/XML-Erzeugung und Migrationen laufen real.

Nur äußere Grenzen werden kontrolliert: der persistente lokale Zahlungsanbieter,
der lokale Mailausgang, die Zeit und gezielt ausgelöste Renderer-/Versandfehler.
Ein Fehler nach erfolgter Provideroperation simuliert eine verlorene Antwort;
der lokale Providerdatensatz bleibt für die Wiederaufnahme erhalten.
Rechnungen, Zahlungen oder bezahlte Berechtigungen werden nicht per Test-SQL erzeugt.
Die zusätzlichen Kontrollrouten liegen unter `src/test` und dürfen nicht im
Produktions-JAR enthalten sein; der Starthelfer prüft das vor jedem Szenario.

## Fachliche Zuordnung

Eine Zuordnung bedeutet, dass die genannten Varianten automatisiert geprüft
werden. Sie behauptet keine vollständige Abdeckung sämtlicher Eingaben,
Race-Reihenfolgen oder externer Anbieter.

| Ablauf | Szenarien | Geprüfte Varianten |
| --- | --- | --- |
| S01 Registrierung | E01, E03 | Bestätigung, neutrale Duplikatanforderung, Tokenwiederholung, Ablauf |
| S02 Sitzung | E01 | Anmeldung, geschütztes Cookie, Abmeldung |
| S03 Passwort | E02 | Wiederherstellung, Änderung, Entwertung alter Sitzungen |
| S04 Konten | E04 | Organisation erstellen und Kontext wählen |
| S05 Einladung | E04, E05 | Annahme, Empfängerbindung, Widerruf |
| S06 Mitgliedsrechte | E04 | Leser darf nicht schreiben, Rollenwechsel und Entfernung wirken auf nächste Aktion |
| S07 Eigentum | E04 | Transfer und Wechsel der Abrechnungsberechtigung |
| S08 Produktaktion | E06, E25, E28 | Tenantisolation, Zugriffsschutz, echte Fachaktionen |
| S09 Grenzen | E06, E16 | Parallelität am letzten Platz, Rückabwicklung, Monatswechsel, Downgrade bei Überbestand |
| S10 Rechnungsdaten | E07, E27 | Adresse für künftige Dokumente, unveränderliche ausgestellte Rechnung |
| S11 Checkout | E07, E08, E12, E22, E28 | Idempotenz, ausstehende Zahlung ohne Pro-Rechte, verlorene Antwort, Rennen mit Schließung |
| S12 Zahlungswiederholung | E09–E11, E14 | Fehlgeschlagen, abgebrochen, abgelaufen, neuer Versuch derselben Bestellung |
| S13 Verlängerung | E13, E14, E23 | Exakte Periodengrenze, Fehler und Wiederherstellung, gekündigte Grenze |
| S14 Tarifende | E15, E16 | Kündigen, fortsetzen, erneut kündigen, Ablauf, Free-Downgrade mit Bestandserhalt |
| S15 Erstattung | E17, E18, E24, E25 | Teil-/Vollerlass, Wiederholung, reservierter Betrag, verlorene Antwort, Betreiberberechtigung |
| S16 Rechnung | E07, E17, E19, E25, E27 | Beträge und XML, Originalreferenz, Korrektur, unveränderte Bytes, Zugriffsschutz |
| S17 Versand/Rendering | E19–E21 | Wiederanlauf, gleiche Nummer, eine Benachrichtigung, unabhängige Workerphasen |
| S18 Kontoschließung | E15, E17, E22–E24 | Finanzielle Sperren, Ablauf/Freigabe, Checkout-/Verlängerungs-/Erstattungsparallelität |
| S19 Identität schließen | E26 | Letzter Owner geschützt, Identität nach Kontoschließung schließbar |
| S20 Betrieb | E12, E18, E20, E21, E27, E29 | Persistenter Wiederanlauf, echte Datenbankberechtigungsstörung, Migrationen auf befüllter Datenbank |

E22 hält eine echte Kontosperre in PostgreSQL und verlangt, dass beide konkurrierenden
Operationen an einer Datenbanksperre warten. E23/E24 ergänzen gleichzeitig gestartete
HTTP-/Worker-Aktionen. Die tieferen PostgreSQL-Integrationstests der Bausteine prüfen
zusätzlich Zustandsübergänge, Sperrregeln und Idempotenz unabhängig vom Browser.

## Ausführung und Abnahmekriterium

Nach Installation der passenden Producer und `mvn clean package` in der
Beispielanwendung: in `browser` mit `npm ci` und `npx playwright install chromium`
den Browser installieren; anschließend `python3 verify.py` im Beispielverzeichnis
mit den drei `VERNAC_PG_TEST_*`-Variablen ausführen. PostgreSQL muss temporäre
Datenbanken und Rollen anlegen dürfen. Echte Anbieterzugänge werden nicht benötigt.

Die CI führt genau diese Suite mit einem Worker und ohne Wiederholungen aus.
`verify.py` verwirft alte Ergebnisse vor dem Start und verlangt E01–E29 jeweils
genau einmal, ausschließlich erfolgreiche Resultate, keine globalen Runnerfehler
und die Zuordnung aller S01–S20. Fehlende oder übersprungene Tests ergeben kein
Grün. Diese Evidenzprüfung hat eigene Negativtests.

`target/acceptance-reports/scenarios.json` und `TEST-SaaSScenarios.xml` enthalten
benannte Ergebnisse und feste, bereinigte Diagnosen. Screenshots, Videos,
Browsertraces, Zugangsdaten, Einmallinks und rohe HTTP-Fehler werden nicht archiviert.
Die Anwendungsausgaben werden vor der temporären Bereinigung gegen die im Test
verwendeten Geheimnisse geprüft.

## Bewusste Grenzen

* Mollie-/Stripe-Sandbox und Brevo mit realen Zugängen bleiben separat durchzuführen;
  lokale HTTP-Adaptertests ersetzen keine Anbieterabnahme.
* E27 startet denselben Build gegen seine befüllte Datenbank neu und spielt Flyway
  erneut ein. Das ist ein Persistenz-/Migrationswiederholungstest, **kein Upgrade von
  einer früheren veröffentlichten SaaS-Version**. Für ein solches Upgrade muss eine
  fest versionierte Ausgangsversion samt repräsentativen Daten hinzukommen.
* Last-, Langzeit-, Infrastruktur-/Proxy- und vollständige Sicherheitsabnahme
  bleiben eigene Prüfungen. Automatisierte funktionale Tests ersetzen diese nicht.


## Upgradebasis ab 0.6.0

Die [verbindliche Migrationsrichtlinie](../development/upgrade-policy.md) legt 0.6.0
als erste unterstützte Basis fest. Für frühere Versionen wird kein Upgrade zugesagt.
Die Suite `UpgradeBaseline` stellt den versionierten Bestand pro Test frisch wieder
her und prüft fachliche Daten, Sitzungen, Mandantentrennung, Rechte, Rechnungsbytes,
Flyway-Historien und die Fortsetzung offener Zahlungen/Erstattungen. Siehe
[Ausführung und Grenzen](../../tests/upgrades/README.md). Der anfängliche Kandidat
beweist Wiederherstellbarkeit derselben Version; vor Veröffentlichung wird die Basis
vom freigegebenen Build eingefroren. Historische Upgrades werden ab der Folgerelease
gegen diesen unveränderten Bestand geprüft. E27 bleibt ein separater Neustarttest.

## Normaler Wizard-Start

Der zusätzliche CI-Job `wizard-start` erzeugt ein Projekt über die tatsächliche
Wizard-Implementierung mit eigenem Namespace, anderen Rollen und Exportlimit 3.
Er startet die normale Anwendung mit `./mvnw spring-boot:run` und echtem Docker
Compose, während Aufrufe von Python im PATH ausdrücklich scheitern. Registrierung,
Java-Mailwerkzeug, Verifikation, Anmeldung, Dokumentanlage und Betreiberstatus
werden geprüft. Nach Neustart müssen Session, Dokument, Zertifikat und Schlüssel
erhalten bleiben. Diese Abnahme verwendet keine Fixture-Anwendung und ergänzt die
29 fachlichen Szenarien sowie die versionierten Upgrade-Prüfungen.
