package de.chriswohlbrecht.vehicletemplates;

import static org.assertj.core.api.Assertions.assertThat;

import de.chriswohlbrecht.vehicletemplates.CatalogValidator.Problem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The checker against the valid fixture ({@code src/test/resources/fixtures/valid}) and copies of it with one
 * mistake each: every rule reports a clear message for the right file.
 */
class CatalogValidatorTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/valid");
    private static final String TEMPLATE = "motorcycle/example/demo-2020.yaml";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private final CatalogValidator validator = new CatalogValidator(Path.of("schema"), TODAY);

    @TempDir
    Path catalog;

    @BeforeEach
    void copyFixture() throws IOException {
        try (Stream<Path> files = Files.walk(FIXTURE)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Path target = catalog.resolve(FIXTURE.relativize(source).toString());
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
            }
        }
    }

    private List<Problem> validate() {
        return validator.validate(catalog);
    }

    /** Replaces {@code from} (must occur) in a catalog file. */
    private void edit(String file, String from, String to) throws IOException {
        Path path = catalog.resolve(file);
        String content = Files.readString(path);
        assertThat(content).contains(from);
        Files.writeString(path, content.replace(from, to));
    }

    private void write(String file, String content) throws IOException {
        Path path = catalog.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private void assertOneProblem(String file, String messagePart) {
        assertThat(validate()).singleElement().satisfies(problem -> {
            assertThat(problem.file()).isEqualTo(file);
            assertThat(problem.message()).contains(messagePart);
        });
    }

    @Test
    void validFixturePasses() {
        assertThat(validate()).isEmpty();
    }

    @Test
    void reportsInvalidYaml() throws IOException {
        write(TEMPLATE, "id: [unclosed");

        assertOneProblem(TEMPLATE, "Kein gültiges YAML");
    }

    @Test
    void reportsSchemaViolations() throws IOException {
        edit(TEMPLATE, "market: EU", "market: US");

        assertOneProblem(TEMPLATE, "Schema: /market");
    }

    @Test
    void reportsUnknownFields() throws IOException {
        edit(TEMPLATE, "market: EU", "market: EU\ncolour: red");

        assertOneProblem(TEMPLATE, "colour");
    }

    @Test
    void reportsTaskWithoutSource() throws IOException {
        edit(TEMPLATE, "    intervalKm: 40000\n    sources: [forum-a, dealer]\n", "    intervalKm: 40000\n");

        assertOneProblem(TEMPLATE, "sources");
    }

    @Test
    void reportsSourceWithoutUrlAndLocator() throws IOException {
        edit(TEMPLATE, "    url: https://dealer.example.net/demo\n", "");

        assertThat(validate()).isNotEmpty().allSatisfy(problem -> assertThat(problem.file()).isEqualTo(TEMPLATE));
    }

    @Test
    void reportsCitedSourceThatIsNotListed() throws IOException {
        edit(TEMPLATE, "    firstDueKm: 1000\n    sources: [manual]\n    details", "    firstDueKm: 1000\n    sources: [handbook]\n    details");

        assertOneProblem(TEMPLATE, "Aufgabe engine-oil: Quelle handbook ist unter 'sources' nicht angegeben");
    }

    @Test
    void reportsUnusedSource() throws IOException {
        edit(TEMPLATE, "sources: [forum-a, dealer]", "sources: [manual]");

        assertThat(validate()).extracting(Problem::message).containsExactlyInAnyOrder(
                "Quelle forum-a: wird von keiner Aufgabe genutzt.",
                "Quelle dealer: wird von keiner Aufgabe genutzt.");
    }

    @Test
    void reportsForumAsOnlySource() throws IOException {
        edit(TEMPLATE, "sources: [forum-a, dealer]", "sources: [forum-a]");
        edit(TEMPLATE, "    intervalKm: 10000\n    intervalMonths: 12\n    firstDueKm: 1000\n    sources: [manual]",
                "    intervalKm: 10000\n    intervalMonths: 12\n    firstDueKm: 1000\n    sources: [manual, dealer]");

        assertOneProblem(TEMPLATE, "Aufgabe valve-clearance: Forenquelle allein reicht nicht");
    }

    @Test
    void reportsForumConfirmedOnlyBySameSite() throws IOException {
        edit(TEMPLATE, "https://dealer.example.net/demo", "https://www.forum-a.example.org/t/2");

        assertOneProblem(TEMPLATE, "Forenquelle allein reicht nicht");
    }

    @Test
    void reportsTaskKeyMissingFromDictionary() throws IOException {
        edit(TEMPLATE, "key: valve-clearance", "key: valve-adjustment");

        assertOneProblem(TEMPLATE, "Aufgabe valve-adjustment: Schlüssel steht nicht im Wörterbuch");
    }

    @Test
    void reportsDuplicateTask() throws IOException {
        edit(TEMPLATE, "key: valve-clearance\n    intervalKm: 40000", "key: engine-oil\n    intervalKm: 20000");

        assertOneProblem(TEMPLATE, "Aufgabe engine-oil: kommt mehrfach vor");
    }

    @Test
    void reportsDetailLabelMissingFromDictionary() throws IOException {
        edit(TEMPLATE, "label: oil", "label: oil-grade");

        assertOneProblem(TEMPLATE, "Detail oil-grade: Bezeichnung steht nicht");
    }

    @Test
    void reportsTaskWithoutInterval() throws IOException {
        edit(TEMPLATE, "    intervalKm: 40000\n", "");

        assertOneProblem(TEMPLATE, "Aufgabe valve-clearance: braucht intervalKm oder intervalMonths");
    }

    @Test
    void reportsOneTimeTaskWithInterval() throws IOException {
        edit(TEMPLATE, "    oneTime: true\n", "    oneTime: true\n    intervalKm: 5000\n");

        assertOneProblem(TEMPLATE, "Aufgabe inspection: einmalige Aufgabe (oneTime) darf kein Intervall haben");
    }

    @Test
    void reportsValueOutsidePlausibleRange() throws IOException {
        edit(TEMPLATE, "intervalKm: 10000", "intervalKm: 100000");

        assertOneProblem(TEMPLATE, "Aufgabe engine-oil: intervalKm = 100000 liegt außerhalb der plausiblen Spanne 1000–30000");
    }

    @Test
    void acceptsValueOutsideRangeWithReason() throws IOException {
        edit(TEMPLATE, "intervalKm: 10000", "intervalKm: 100000\n    plausibilityException: Synthetic oil, manufacturer specifies 100000 km");

        assertThat(validate()).isEmpty();
    }

    @Test
    void reportsWrongTypeDirectory() throws IOException {
        Files.createDirectories(catalog.resolve("car/example"));
        Files.move(catalog.resolve(TEMPLATE), catalog.resolve("car/example/demo-2020.yaml"));

        assertOneProblem("car/example/demo-2020.yaml", "Typ MOTORCYCLE gehört in den Ordner motorcycle/");
    }

    @Test
    void reportsIdNotMatchingFile() throws IOException {
        edit(TEMPLATE, "id: example-demo-2020", "id: example-demo-2021");

        assertOneProblem(TEMPLATE, "id muss example-demo-2020 sein");
    }

    @Test
    void reportsFutureRetrievalDate() throws IOException {
        edit(TEMPLATE, "    locator: p. 80\n    retrieved: 2026-11-01", "    locator: p. 80\n    retrieved: 2026-12-24");

        assertOneProblem(TEMPLATE, "Quelle manual: Abrufdatum 2026-12-24 liegt in der Zukunft");
    }

    @Test
    void reportsYearsInWrongOrder() throws IOException {
        edit(TEMPLATE, "years: { from: 2020, to: 2023 }", "years: { from: 2020, to: 2019 }");

        assertOneProblem(TEMPLATE, "years: 'to' liegt vor 'from'");
    }

    @Test
    void reportsOverlappingYearsOfTheSameModel() throws IOException {
        String later = Files.readString(catalog.resolve(TEMPLATE))
                .replace("id: example-demo-2020", "id: example-demo-2023")
                .replace("model: Demo 700", "model: DEMO-700")
                .replace("years: { from: 2020, to: 2023 }", "years: { from: 2023 }");
        write("motorcycle/example/demo-2023.yaml", later);

        assertOneProblem(TEMPLATE, "Baujahre überschneiden sich mit motorcycle/example/demo-2023.yaml");
    }

    @Test
    void acceptsFollowingYearsOfTheSameModel() throws IOException {
        String later = Files.readString(catalog.resolve(TEMPLATE))
                .replace("id: example-demo-2020", "id: example-demo-2024")
                .replace("years: { from: 2020, to: 2023 }", "years: { from: 2024 }");
        write("motorcycle/example/demo-2024.yaml", later);

        assertThat(validate()).isEmpty();
    }

    @Test
    void reportsMissingDictionary() throws IOException {
        Files.delete(catalog.resolve(CatalogValidator.DICTIONARY));

        assertThat(validate()).extracting(Problem::toString).contains("tasks.yaml: Das Wörterbuch fehlt.");
    }

    @Test
    void reportsInvalidDictionary() throws IOException {
        edit(CatalogValidator.DICTIONARY, "  oil: { de: Öl, en: Oil }", "  oil: { de: Öl }");

        assertOneProblem(CatalogValidator.DICTIONARY, "Schema: /details/oil");
    }

    @Test
    void reportsRangeTheWrongWayRound() throws IOException {
        edit(CatalogValidator.DICTIONARY, "intervalKm: [5000, 100000]", "intervalKm: [100000, 5000]");

        assertThat(validate()).extracting(Problem::toString)
                .contains("tasks.yaml: valve-clearance: Spanne intervalKm für MOTORCYCLE ist verkehrt herum.");
    }

    @Test
    void reportsFilesThatAreNotYaml() throws IOException {
        write("motorcycle/example/notes.txt", "x");

        assertOneProblem("motorcycle/example/notes.txt", "Nur .yaml-Dateien");
    }

    @Test
    void slugAndNormalise() {
        assertThat(CatalogValidator.slug("Royal Enfield")).isEqualTo("royal-enfield");
        assertThat(CatalogValidator.slug("Moto Guzzi ")).isEqualTo("moto-guzzi");
        assertThat(CatalogValidator.normalise("CB 650 R")).isEqualTo(CatalogValidator.normalise("cb650r"));
        assertThat(CatalogValidator.normalise("R 1250 GS")).isEqualTo(CatalogValidator.normalise("R1250-GS"));
    }
}
