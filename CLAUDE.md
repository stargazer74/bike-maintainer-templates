# CLAUDE.md

Curated catalog of manufacturer maintenance schedules for Bike Maintainer (Epic
stargazer74/bike-maintainer-backend#150). Data in `catalog/`, JSON schemas in `schema/`, the checker as JUnit tests.
The README (German) is the reference for format, source rules and checks; this file adds the rules for working here.

## Commands

```bash
./mvnw verify                      # check the catalog and the checker, build the artifact
./mvnw install                     # same, plus install de.chris-wohlbrecht:vehicle-templates locally (backend build)
./mvnw test -Dtest=CatalogTest     # only the real catalog
./mvnw test -Dgroups=links -Dtests.excluded= -Dtest=LinkCheckTest   # source links (internet), target/link-check.md
```

Java 25. Without a local JDK 25: `docker run --rm -u $(id -u):$(id -g) -e HOME=/tmp -v ~/.m2:/tmp/.m2 -v $PWD:/w -w /w
eclipse-temurin:25-jdk ./mvnw -Dmaven.repo.local=/tmp/.m2/repository verify`.

## Data rules (non-negotiable)

- **Only facts and source references.** Values (intervals, grades, quantities, settings) plus title, URL, locator and
  retrieval date. Never copy text, tables, figures or PDFs from manuals; descriptions are own words in `tasks.yaml`.
- **Never invent a value.** Every task and detail cites a source that actually states it. If no source is reachable,
  leave the task or the whole model out and say so in the PR. Do not "fill in typical values".
- **Manufacturer first.** `MANUFACTURER` sources (owner's manual, service booklet, official site) over anything else;
  `FORUM` only together with an independent second source from another site.
- **Locator:** page number or section in the manual, so a reviewer finds the value quickly.
- **One file per model and year range.** The range covers every model year the source applies to (cover, type
  designation, facelift information), not just the year of the manual you found; the file name carries the first
  year. A new file starts when the schedule changes (facelift, new engine, other intervals). Leave a neighbouring
  year out unless a source shows it has the same schedule. Ranges of a model must not overlap.
- **Priorities:** older models first (model year up to 2018), chosen by the vehicle stock in Germany (KBA), because
  their owners are the ones who do their own maintenance. Capture the details a mechanic needs whenever the source
  states them: oil grade and quantity, valve clearance intake/exhaust, spark plug and gap, chain slack, coolant and
  brake fluid.
- **Workshop manuals** (Haynes, Bucheli etc.) are allowed as secondary sources (`REPAIR_PORTAL` is not right for
  them; use `PRESS`): values with page reference only, never text.
- **Out-of-range values:** check twice against the source, then `plausibilityException` with the reason. Never widen
  a range in `tasks.yaml` just to make one model pass.
- **Units:** km and months. Convert miles or years from the source exactly (1 year = 12 months) and keep the original
  in the locator if it helps the review.

## Batches and PRs

- One PR per batch (one make and vehicle type). The description lists the models, models left out (and why) and
  randomly drawn samples (template, task, value, link to the locator) for the review.
- New task keys or detail labels go into `tasks.yaml` in the same PR, with name and description in German and English
  and plausible ranges for the vehicle type.

## Code

- The checker (`CatalogValidator`) lives in test sources: the artifact contains only data. Messages are German (read
  by catalog maintainers), code and comments English.
- Every rule has a test in `CatalogValidatorTest` that breaks a copy of the valid fixture
  (`src/test/resources/fixtures/valid/`, made-up values, not part of the catalog).
- Dependency versions in the `<properties>` of `pom.xml`; empty line between `<dependency>` blocks.
