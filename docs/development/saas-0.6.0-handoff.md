# Übergabe für Vernac 0.6.0

Die Abschnitte 1–5 bewahren den ursprünglichen Auftrag nach PR #92. Den aktuellen Umsetzungsstand enthält die Fortsetzung am Ende.

Arbeitsauftrag von Danny: Alle fünf offenen Themen implementieren, testen und per PR in `feature/0.6.0` mergen. Pro Thema ein PR, kleine Commits sind willkommen. Direktes Mergen ist autorisiert. Danny kann momentan nicht lokal testen. Vor Beginn Branchstand und offene PRs prüfen. Dieser Text beschreibt den Stand nach PR #92.

## Bereits erledigt

- #91 erlaubt IntelliJ bis 263.* und erhält die Untergrenze 262.10968.63. ZIP- und API-Checks grün. Vollständiger Plugin Verifier und interaktiver IDE-Test stehen aus.
- #92 hat die Spring-Anwendungskonfiguration von identity-auth und saas-document-workspace auf YAML umgestellt, einschließlich Wizard-Manifest. Der Consumer-Job scheiterte beim Download der Boot-BOM mit Maven Central HTTP 429, vor Ausführung der Tests. Beim nächsten Lauf die tatsächlichen Consumer-Ergebnisse prüfen.
- Die nachfolgenden fünf Punkte wurden in diesen PRs nicht implementiert.

## 1. Vollständig auf Jackson 3 migrieren

Ausgangspunkt: Root-POM, vernac-runtime-json (JsonValues, JsonPages), Generatoren für DTOs/HTTP, SaaS-Module einschließlich RoleCatalog, Spring-Konfiguration, Templates, Beispiele und Testprojekte. Nach jackson2, com.fasterxml.jackson, ObjectMapper, Serialisierern und generierten Imports suchen. Koordinaten und API anhand der Jackson-3-Dokumentation prüfen, insbesondere die weiterhin verwendeten Jackson-Annotations. Den Jackson-2-Kompatibilitätsmodus entfernen. Für 0.6.0 ist keine parallele Jackson-2-Unterstützung gewünscht. Prüfen: Dependency Tree, vollständiger Maven-Build und HTTP-Roundtrip einer frisch generierten Anwendung einschließlich DTO-Validierung und Fehlerpfad.

## 2. Rollen deklarativ in Vernac

Einstieg: permission-Deklarationen in Vernac.g4, Modell/Compiler, examples/saas-document-workspace/src/main/vernac und dessen roles.json, saas/identity/.../roles/RoleCatalog. Der Entwickler definiert Rollen und deren Berechtigungen im Modell. Rollenschlüssel der Beispiele erhalten. Unbekannte Berechtigungen und doppelte Rollen beim Kompilieren mit Quellposition melden. Eine interne generierte Ressource ist möglich. Keine manuelle Doppelpflege im Beispiel oder im Wizard-Projekt. Syntax, Sprachreferenz, IDE-Unterstützung, Compilerdiagnostik und tatsächliche Rechte für Reader/Editor im erzeugten Projekt prüfen.

## 3. SaaS-Anwendung und Modulgrenzen vereinfachen

Einstieg: BillingSetup, IdentitySetup, MerchantOperations, PaymentNotices, LocalPreview, DocumentActions, WorkspaceController und model.vernac im Dokumentenbeispiel. Zuerst wiederkehrende Verdrahtung und Worker-Lebenszyklen in die jeweils zuständigen Module mit Spring-Boot-Autokonfiguration übernehmen. Aktivierung und Providerwahl explizit halten. Die Anwendung entscheidet über Rollen, Tarife, Limits, Exportregeln und Oberfläche. Vorhandene Vernac-Usecases, Berechtigungen und MVC-Deklarationen verwenden. Fachlichen Ablauf im Modell lesbar machen. Keine beispielspezifischen Dokumentbegriffe in generische SaaS-Module übernehmen. Mandantentrennung, Transaktionen, Operatorrechte, Provider- und Webhookverhalten erhalten. Abnahme mit einem frisch erzeugten Projekt mit eigenem Namespace, anderen Rollen und verändertem Exportlimit sowie bestehenden Browser-/Integrationstests.

## 4. Start aus IntelliJ oder per Maven Wrapper ohne Python

Am etablierten Spring-Boot-Template orientieren. Spring Boots Docker-Compose-Unterstützung für lokale Dienste nutzen. run-local.py, compose.yaml, lokale YAML, POM, Wizard-Generator und Ressourcenmanifest gemeinsam prüfen. Der Launcher übernimmt derzeit mehr als den Datenbankstart: lokale HTTPS-Konfiguration, dauerhafte Schlüssel, getrennte Migrations-/Laufzeitrollen und Betreiberfunktionen. Diese Anforderungen mit möglichst wenig Anwendungscode erhalten. Entwickler können ein neues Projekt mit installiertem JDK und Docker aus der IDE oder über den Maven Wrapper starten und nach einem Neustart mit denselben Daten weiterarbeiten. Anleitung und Wizard-Ausgabe aktualisieren. Python für interne Repository-Abnahmetests ist zulässig, sofern das generierte Projekt es weder für normalen Start noch Betrieb benötigt. Lokalen Mailzugriff und sichere Rollenverteilung tatsächlich prüfen.

## 5. Vernac-Formatter

Ein direkt nutzbarer Formatter gehört zum Auftrag. Sinnvoller Einstieg ist die normale IntelliJ-Aktion „Reformat Code“ für .vernac. Grammatik, Lexer, Parser/PSI und vorhandene Java-Injection berücksichtigen. Am Dokumentenbeispiel zeigen, wie vormals gedrängte Deklarationen, Klammern und Einrückungen lesbar werden. Kommentare, Strings und eingebettetes Java semantisch erhalten. Tests für Idempotenz, unveränderte Token/Semantik und typische verschachtelte Deklarationen vorsehen. Erst nach diesen Prüfungen das Beispiel mit dem Formatter formatieren.

## Fertigmeldung

Für jedes Thema PR, gemergten Zielbranch, tatsächlich ausgeführte Checks und offene Prüflücken nennen. Das erzeugte Wizard-Projekt über den normalen Entwicklerpfad prüfen, nicht nur die Dateien des Quellbeispiels. Echte Provider-Abnahmen übernimmt Danny später. Für diese Arbeiten weder ein Marketplace- noch ein Maven-Central-Release veröffentlichen.

## Fortsetzung am 17.11.2026

- Jackson 3: PR #93 nach sechs erfolgreichen GitHub-Checks in `feature/0.6.0` gemergt. Lokal: 1.243 Java-Tests ohne Fehler/Skips; frisches SaaS-Wizard-Projekt mit 29 Browser-Szenarien; frischer HTTP-Wizard mit sieben Tests. Nur die gemeinsamen Jackson-Annotations bleiben unter `com.fasterxml`; Mustang-JSON-Abhängigkeit ausgeschlossen und PDF/XML separat geprüft.
- Deklarative Rollen: PR #94 enthält Grammatik, Compilerdiagnostik, generierte Ressource, Editorunterstützung und umgestellte Beispiele/Wizard. Lokal 24 gezielte Tests und E05 mit eigenem Namespace und anderen Rollen grün; Alle vier GitHub-Checks einschließlich Browser-/Upgrade-Abnahme erfolgreich; in `feature/0.6.0` gemergt.
- SaaS-Modulverdrahtung: PR #95 nach allen relevanten GitHub-Checks gemergt. Explizite Auto-Konfigurationen und verwaltete Worker, generische Webhook-/Betreiber-/Vorschauadapter in ihren Modulen, deklarative MVC-Flows und Anwendungsvorlagen. Frischer Wizard mit anderen Rollen und Exportlimit 3 bestand alle 29 Szenarien; Identity/Billing/Local-Mail-PostgreSQL-Checks grün.
- Start ohne Python: normaler IDE-/Maven-Start, geteilter lokaler Java-Support, persistente alte/neue Schlüssel und HTTPS, getrennte Datenbankrollen sowie Java-Mail-/Betreiberwerkzeuge implementiert. Lokal fünf Tests inklusive realer Rechteprüfung und normaler Start/Neustart gegen bereitgestelltes PostgreSQL grün. Die neue CI-Wizard-Abnahme prüft zusätzlich den tatsächlichen Spring-Boot-Docker-Compose-Start. Vor Merge deren Ergebnis prüfen.

- Start ohne Python: PR #96 nach erfolgreicher tatsächlicher Docker-Compose-Wizard-Abnahme und den übrigen Browser-/Upgrade-/IDE-/Tenant-Checks in `feature/0.6.0` gemergt.
- Formatter: Standard-IntelliJ-Service für `.vernac`, `.vapplication`, `.vmodule`, parserbasierte Java-Islands und abschließender Tokenvergleich implementiert. Kommentare/Strings/Java-Bodies bleiben erhalten; fehlerhafte Dateien bleiben unverändert. Dokumenten- und Identity-Modell erst nach erfolgreichen Idempotenz-/Token-/Semantiktests mit diesem Formatter formatiert. CI prüft zusätzlich Service und gepackte Plugin-/Compilerklassen. Interaktiver installierter IDE-Test und vollständiger Plugin Verifier bleiben separate Prüflücken; echte Providerabnahmen bleiben beim Maintainer. Es wurde kein Release veröffentlicht.
