# Bike Maintainer – Wartungsplan-Vorlagen

Kuratierter Katalog der Wartungspläne von Fahrzeugherstellern für [Bike Maintainer](https://github.com/stargazer74/bike-maintainer-backend).
Wer ein Fahrzeug anlegt, bekommt den Plan seines Modells vorgeschlagen, statt die Tabelle aus dem Handbuch abzutippen
(Epic [backend#150](https://github.com/stargazer74/bike-maintainer-backend/issues/150)).

> **Richtwerte ohne Gewähr – maßgeblich ist das Handbuch deines Fahrzeugs.**

Der Katalog wird nicht zur Laufzeit aus dem Internet geladen: Dieses Repo baut ein Maven-Artefakt, das das Backend
beim Build einbindet. Neue Vorlagen kommen also mit Backend-Releases, auch in den Haushaltsmodus.

## Aufbau

```
catalog/
  tasks.yaml                          Wörterbuch: Aufgabenschlüssel, Namen/Beschreibungen DE/EN, plausible Spannen
  <typ>/<hersteller>/<modell>-<baujahr>.yaml   eine Vorlage je Modell und Baujahresbereich
schema/
  template.schema.json                JSON-Schema einer Vorlage
  tasks.schema.json                   JSON-Schema des Wörterbuchs
src/test/java/…                       Prüfskript (CatalogValidator) und seine Tests, Linkcheck
```

Typ-Ordner: `motorcycle`, `car`, `bicycle`, `e-bike`, `quad`, `scooter`. Hersteller-Ordner: Name klein, alles außer
Buchstaben und Ziffern wird zu `-` (`Royal Enfield` → `royal-enfield`).

## Format einer Vorlage

```yaml
# yaml-language-server: $schema=../../../schema/template.schema.json
id: honda-cb650r-2019              # <hersteller-ordner>-<dateiname>
type: MOTORCYCLE                   # wie in der API: MOTORCYCLE, CAR, BICYCLE, E_BIKE, QUAD, SCOOTER
make: Honda
model: CB650R
aliases: [CB 650 R, CB650RA]       # weitere Schreibweisen für die Suche
years: { from: 2019, to: 2023 }    # Modelljahre einschließlich; ohne `to` bis heute
market: EU                         # Angaben in km
sources:
  - id: manual
    kind: MANUFACTURER             # MANUFACTURER | DEALER | PRESS | REPAIR_PORTAL | FORUM
    title: Honda CB650R Fahrerhandbuch 2019, Wartungsplan
    url: https://…                 # url und/oder locator
    locator: S. 112                # Fundstelle
    retrieved: 2026-11-13          # Abrufdatum
tasks:
  - key: engine-oil                # Schlüssel aus catalog/tasks.yaml
    intervalKm: 12000
    intervalMonths: 12
    firstDueKm: 1000               # erste Fälligkeit (Kilometerstand); firstDueMonths entsprechend
    sources: [manual]
    details:                       # optional: Sorte, Menge, Teil, Einstellwert – je mit Quelle
      - { label: oil, value: "10W-30, JASO MA", quantity: "2,3 l", sources: [manual] }
notes: { de: …, en: … }            # optional
```

- **Baujahre:** Eine Vorlage umfasst alle Baujahre, für die ihre Quelle gilt – laut Deckblatt, Typbezeichnung oder
  Angaben zur Modellpflege, nicht nur das Baujahr des gefundenen Handbuchs. Ändert sich der Wartungsplan (Modellpflege,
  neuer Motor, andere Intervalle), beginnt eine neue Datei; der Dateiname trägt das erste Baujahr des Bereichs. Ist
  nicht belegt, dass ein Nachbarjahr denselben Plan hat, bleibt es draußen.
- **Intervalle** wie im Aufgabenmodell der API: `intervalKm`, `intervalMonths`, `firstDueKm`, `firstDueMonths`,
  `oneTime`. Eine wiederkehrende Aufgabe braucht `intervalKm` oder `intervalMonths`; eine einmalige (`oneTime: true`,
  z. B. Einfahrinspektion) eine erste Fälligkeit und kein Intervall.
- **Variable Intervalle** (Autos mit Longlife): den festen Höchstwert aus dem Handbuch nehmen und in `notes` erklären.
- **Texte:** Name und Beschreibung kommen aus dem Wörterbuch; eine Vorlage darf sie mit `name`/`description`
  (je `de` und `en`) für ihre Aufgabe überschreiben.
- **Plausible Spannen** stehen im Wörterbuch je Aufgabe und Fahrzeugtyp. Liegt ein echter Herstellerwert außerhalb,
  bekommt die Aufgabe `plausibilityException: <Begründung>`.

Editoren mit YAML-Language-Server (VS Code, IntelliJ) prüfen und vervollständigen über die `$schema`-Zeile.

## Quellen

1. **Herstellerangaben bevorzugt:** Fahrerhandbuch, Serviceheft, offizielle Herstellerseite (`MANUFACTURER`).
2. **Zweitrangige Quellen** sind erlaubt (`DEALER`, `PRESS`, `REPAIR_PORTAL`) und werden in der App gekennzeichnet.
3. **Foren** (`FORUM`) zählen nur, wenn eine zweite, unabhängige Quelle (andere Seite) dasselbe sagt.
4. **Nur Fakten:** In die Dateien kommen nur Werte und Quellenangaben (Titel, URL, Fundstelle, Abrufdatum). Texte,
   Tabellen, Abbildungen und PDFs aus Handbüchern werden nie übernommen; Beschreibungen sind eigene Worte.
5. **Nichts erfinden:** Wo keine Quelle erreichbar ist, gibt es keine Vorlage bzw. keine Aufgabe.

## Prüfregeln

`./mvnw verify` prüft den ganzen Katalog (`CatalogTest`) und meldet alle Probleme auf einmal:

- Wörterbuch und Vorlagen entsprechen ihrem Schema (Pflichtfelder, Typen, keine unbekannten Felder).
- Pfad, Typ-Ordner, Hersteller-Ordner und `id` passen zusammen; jede `id` gibt es nur einmal.
- Jede Aufgabe und jedes Detail hat eine Quelle, jede zitierte Quelle ist angegeben, jede angegebene wird genutzt.
- Forenquellen nur zusammen mit einer zweiten Quelle von einer anderen Seite.
- Aufgabenschlüssel und Detail-Bezeichnungen stehen im Wörterbuch; jede Aufgabe kommt je Vorlage nur einmal vor.
- Intervalle: siehe oben (wiederkehrend bzw. einmalig).
- Werte liegen in der plausiblen Spanne für Typ und Aufgabe, sonst `plausibilityException` mit Begründung.
- Baujahresbereiche desselben Modells (Typ, Hersteller, Modell ohne Leerzeichen/Bindestriche) überschneiden sich
  nicht; `to` liegt nicht vor `from`; Abrufdaten liegen nicht in der Zukunft.

Ein **wöchentlicher Linkcheck** (`.github/workflows/link-check.yml`) ruft alle Quellenlinks auf und meldet tote
(404/410, unbekannter Host) in einem Issue „Tote Quellenlinks im Katalog“; er blockiert nichts. Lokal:
`./mvnw test -Dgroups=links -Dtests.excluded= -Dtest=LinkCheckTest`, Bericht in `target/link-check.md`.

## Neue Vorlagen (Stapel)

**Reihenfolge:** zuerst ältere Modelle (Baujahr bis 2018), ausgewählt nach dem Bestand in Deutschland
(KBA-Bestandsstatistik nach Marke und Modell) – an ihnen wird am ehesten selbst geschraubt. Neuere Modelle folgen
später. Weil Selbstschrauber vor allem die Werte zur Arbeit brauchen, werden die **Details** mit erfasst, soweit die
Quelle sie nennt: Ölsorte und -menge, Ventilspiel Ein-/Auslass, Zündkerze und Elektrodenabstand, Kettendurchhang,
Kühl- und Bremsflüssigkeit.

Gearbeitet wird in **Stapeln**: ein Hersteller mit einem Fahrzeugtyp, ein PR je Stapel. Die PR-Beschreibung enthält

- die Modelle des Stapels und welche ohne Vorlage bleiben (keine Quelle erreichbar),
- **zufällig gezogene Stichproben**: je Stichprobe Vorlage, Aufgabe, Wert und Link zur Fundstelle.

Geprüft werden die Stichproben gegen die Quelle; stimmen sie, wird gemergt.

## Fehler melden

Nutzer können keine eigenen Vorlagen einreichen, aber Fehler melden: Issue-Vorlage „Fehler in einer Vorlage melden“.
Web- und Android-App öffnen sie vorausgefüllt mit Vorlagen-ID und Aufgabe (ohne Kontodaten):

```
https://github.com/stargazer74/bike-maintainer-templates/issues/new?template=fehler-in-vorlage.yml&vorlage=<id>&aufgabe=<key>
```

## Artefakt

```bash
./mvnw install    # prüft und legt de.chris-wohlbrecht:vehicle-templates im lokalen Maven-Repo ab
```

Das JAR enthält nur Daten, auf dem Klassenpfad unter `vehicle-templates/`: `tasks.yaml`, die Vorlagen
(`vehicle-templates/<typ>/<hersteller>/*.yaml`) und die Schemas (`vehicle-templates/schema/`). Wie die API-Spec wird
es nicht in einer Registry veröffentlicht; die CI des Backends baut es aus diesem Repo.

## Lizenz

- **Daten** (`catalog/`): [CC BY 4.0](LICENSE-DATA) – Nutzung frei mit Namensnennung
  „Bike Maintainer – Wartungsplan-Vorlagen“ und Link auf dieses Repo.
- **Code** (Prüfskript, Build, Schemas): [MIT](LICENSE).

Herstellernamen und Modellbezeichnungen sind Marken ihrer Inhaber und dienen nur der Zuordnung.
