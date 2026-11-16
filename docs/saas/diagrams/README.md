# Fachliche Ablaufdiagramme

Die `.mmd`-Dateien sind die bearbeitbaren Mermaid-Quellen. Die gleichnamigen SVG-Dateien sind daraus erzeugte statische Grafiken für die Website ohne JavaScript.

- [01-gesamtbild](./01-gesamtbild.svg) · [Quelle](./01-gesamtbild.mmd)
- [02-registrierung](./02-registrierung.svg) · [Quelle](./02-registrierung.mmd)
- [03-anmeldung](./03-anmeldung.svg) · [Quelle](./03-anmeldung.mmd)
- [04-passwort](./04-passwort.svg) · [Quelle](./04-passwort.mmd)
- [05-mitglieder](./05-mitglieder.svg) · [Quelle](./05-mitglieder.mmd)
- [06-owner](./06-owner.svg) · [Quelle](./06-owner.mmd)
- [07-produktzugriff](./07-produktzugriff.svg) · [Quelle](./07-produktzugriff.mmd)
- [08-kontingente](./08-kontingente.svg) · [Quelle](./08-kontingente.mmd)
- [09-bestellung](./09-bestellung.svg) · [Quelle](./09-bestellung.mmd)
- [10-abonnement](./10-abonnement.svg) · [Quelle](./10-abonnement.mmd)
- [11-rechnung](./11-rechnung.svg) · [Quelle](./11-rechnung.mmd)
- [12-erstattung](./12-erstattung.svg) · [Quelle](./12-erstattung.mmd)
- [13-mail](./13-mail.svg) · [Quelle](./13-mail.mmd)
- [14-kontoschliessung](./14-kontoschliessung.svg) · [Quelle](./14-kontoschliessung.mmd)
- [15-wiederherstellung](./15-wiederherstellung.svg) · [Quelle](./15-wiederherstellung.mmd)
- [16-integration](./16-integration.svg) · [Quelle](./16-integration.mmd)
- [17-freigabe](./17-freigabe.svg) · [Quelle](./17-freigabe.mmd)
- [18-neustart](./18-neustart.svg) · [Quelle](./18-neustart.mmd)

Renderer: Mermaid CLI 11.12.0, neutrales Theme, HTML-Labels aus. Nach Quellenänderungen beide Dateien aktualisieren und die Darstellung prüfen.

Regeneration aus dem Repository-Verzeichnis (Mermaid CLI 11.12.0 im PATH):

```sh
python3 tools/render-saas-diagrams.py
```

Ein abweichender CLI-Pfad ist mit `--cli` möglich. Für eine vorhandene Chromium-
Installation kann `--puppeteer-config` auf eine eigene Puppeteer-Konfiguration
zeigen. Die SVGs werden erst ersetzt, wenn alle Diagramme erfolgreich gerendert
wurden. Die Website braucht dafür weder JavaScript noch externe Grafikdienste.
