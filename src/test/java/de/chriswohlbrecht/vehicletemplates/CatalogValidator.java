package de.chriswohlbrecht.vehicletemplates;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Checks a catalog directory: the dictionary {@code tasks.yaml} and every template {@code <type>/<make>/<name>.yaml}
 * against their JSON schemas, then the rules a schema cannot express (see README, "Prüfregeln"). Returns all problems
 * instead of stopping at the first, so one run shows everything to fix.
 */
public final class CatalogValidator {

    /** A problem in one file of the catalog; {@code file} is relative to the catalog directory. */
    public record Problem(String file, String message) {
        @Override
        public String toString() {
            return file + ": " + message;
        }
    }

    static final String DICTIONARY = "tasks.yaml";

    private static final Map<String, String> TYPE_DIRECTORIES = Map.of(
            "MOTORCYCLE", "motorcycle",
            "CAR", "car",
            "BICYCLE", "bicycle",
            "E_BIKE", "e-bike",
            "QUAD", "quad",
            "SCOOTER", "scooter");

    private final ObjectMapper yaml = YAMLMapper.builder().build();
    private final Schema templateSchema;
    private final Schema dictionarySchema;
    private final LocalDate today;

    public CatalogValidator(Path schemaDirectory, LocalDate today) {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        this.templateSchema = registry.getSchema(read(schemaDirectory.resolve("template.schema.json")));
        this.dictionarySchema = registry.getSchema(read(schemaDirectory.resolve("tasks.schema.json")));
        this.today = today;
    }

    public List<Problem> validate(Path catalog) {
        List<Problem> problems = new ArrayList<>();
        Dictionary dictionary = loadDictionary(catalog, problems);
        List<Template> templates = new ArrayList<>();
        for (Path file : templateFiles(catalog)) {
            String name = catalog.relativize(file).toString().replace('\\', '/');
            JsonNode node = parse(file, name, problems);
            if (node == null || !matchesSchema(templateSchema, node, name, problems)) {
                continue;
            }
            Template template = new Template(name, node);
            templates.add(template);
            checkTemplate(template, dictionary, problems);
        }
        checkUniqueIds(templates, problems);
        checkOverlappingYears(templates, problems);
        problems.sort(Comparator.comparing(Problem::file));
        return problems;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Files
    // ---------------------------------------------------------------------------------------------------------------

    private static List<Path> templateFiles(Path catalog) {
        try (Stream<Path> files = Files.walk(catalog)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().equals(DICTIONARY) || !file.getParent().equals(catalog))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode parse(Path file, String name, List<Problem> problems) {
        if (!name.endsWith(".yaml")) {
            problems.add(new Problem(name, "Nur .yaml-Dateien gehören in den Katalog."));
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            JsonNode node = yaml.readTree(in);
            if (node == null || node.isMissingNode() || node.isNull()) {
                problems.add(new Problem(name, "Die Datei ist leer."));
                return null;
            }
            return node;
        } catch (JacksonException e) {
            problems.add(new Problem(name, "Kein gültiges YAML: " + e.getOriginalMessage()));
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean matchesSchema(Schema schema, JsonNode node, String name, List<Problem> problems) {
        List<Error> errors = schema.validate(node);
        for (Error error : errors) {
            problems.add(new Problem(name, "Schema: " + error.getInstanceLocation() + " " + error.getMessage()));
        }
        return errors.isEmpty();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Dictionary
    // ---------------------------------------------------------------------------------------------------------------

    /** Task keys with their plausible ranges per vehicle type, and the detail labels. */
    private record Dictionary(Map<String, JsonNode> tasks, Set<String> detailLabels) {
        static final Dictionary EMPTY = new Dictionary(Map.of(), Set.of());
    }

    private Dictionary loadDictionary(Path catalog, List<Problem> problems) {
        Path file = catalog.resolve(DICTIONARY);
        if (!Files.isRegularFile(file)) {
            problems.add(new Problem(DICTIONARY, "Das Wörterbuch fehlt."));
            return Dictionary.EMPTY;
        }
        JsonNode node = parse(file, DICTIONARY, problems);
        if (node == null || !matchesSchema(dictionarySchema, node, DICTIONARY, problems)) {
            return Dictionary.EMPTY;
        }
        Map<String, JsonNode> tasks = new LinkedHashMap<>();
        node.get("tasks").properties().forEach(entry -> tasks.put(entry.getKey(), entry.getValue()));
        Set<String> labels = new HashSet<>(node.get("details").propertyNames());
        node.get("tasks").properties().forEach(entry -> checkRanges(entry.getKey(), entry.getValue(), problems));
        return new Dictionary(tasks, labels);
    }

    private static void checkRanges(String key, JsonNode task, List<Problem> problems) {
        JsonNode plausible = task.path("plausible");
        plausible.properties().forEach(type -> type.getValue().properties().forEach(field -> {
            JsonNode range = field.getValue();
            if (range.get(0).asInt() > range.get(1).asInt()) {
                problems.add(new Problem(DICTIONARY, "%s: Spanne %s für %s ist verkehrt herum."
                        .formatted(key, field.getKey(), type.getKey())));
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Templates
    // ---------------------------------------------------------------------------------------------------------------

    private record Template(String file, JsonNode node) {
        String id() {
            return node.get("id").asString();
        }

        String type() {
            return node.get("type").asString();
        }
    }

    private void checkTemplate(Template template, Dictionary dictionary, List<Problem> problems) {
        String file = template.file();
        JsonNode node = template.node();
        checkPath(template, problems);
        checkYears(file, node.get("years"), problems);
        Map<String, JsonNode> sources = checkSources(file, node.get("sources"), problems);

        Set<String> usedSources = new HashSet<>();
        Set<String> taskKeys = new HashSet<>();
        for (JsonNode task : node.get("tasks")) {
            String key = task.get("key").asString();
            String where = "Aufgabe " + key;
            if (!taskKeys.add(key)) {
                problems.add(new Problem(file, where + ": kommt mehrfach vor."));
            }
            JsonNode entry = dictionary.tasks().get(key);
            if (entry == null && !dictionary.tasks().isEmpty()) {
                problems.add(new Problem(file, where + ": Schlüssel steht nicht im Wörterbuch tasks.yaml."));
            }
            checkIntervals(file, where, task, problems);
            checkCitations(file, where, task.get("sources"), sources, usedSources, problems);
            for (JsonNode detail : task.path("details")) {
                String label = detail.get("label").asString();
                String detailWhere = where + ", Detail " + label;
                if (!dictionary.detailLabels().isEmpty() && !dictionary.detailLabels().contains(label)) {
                    problems.add(new Problem(file, detailWhere + ": Bezeichnung steht nicht unter 'details' im Wörterbuch."));
                }
                checkCitations(file, detailWhere, detail.get("sources"), sources, usedSources, problems);
            }
            if (entry != null) {
                checkPlausible(file, where, template.type(), task, entry, problems);
            }
        }
        for (String id : sources.keySet()) {
            if (!usedSources.contains(id)) {
                problems.add(new Problem(file, "Quelle " + id + ": wird von keiner Aufgabe genutzt."));
            }
        }
    }

    /** {@code <type>/<make>/<name>.yaml} with the id {@code <make>-<name>}; the make directory is the slug of make. */
    private static void checkPath(Template template, List<Problem> problems) {
        String[] parts = template.file().split("/");
        String expectedType = TYPE_DIRECTORIES.get(template.type());
        String makeSlug = slug(template.node().get("make").asString());
        if (parts.length != 3) {
            problems.add(new Problem(template.file(),
                    "Liegt nicht unter <typ>/<hersteller>/<modell>-<baujahr>.yaml, erwartet " + expectedType + "/"
                            + makeSlug + "/…"));
            return;
        }
        if (!parts[0].equals(expectedType)) {
            problems.add(new Problem(template.file(),
                    "Typ " + template.type() + " gehört in den Ordner " + expectedType + "/, nicht " + parts[0] + "/."));
        }
        if (!parts[1].equals(makeSlug)) {
            problems.add(new Problem(template.file(),
                    "Hersteller " + template.node().get("make").asString() + " gehört in den Ordner " + makeSlug
                            + "/, nicht " + parts[1] + "/."));
        }
        String expectedId = parts[1] + "-" + parts[2].replaceFirst("\\.yaml$", "");
        if (!template.id().equals(expectedId)) {
            problems.add(new Problem(template.file(), "id muss " + expectedId + " sein (Ordner-Dateiname), ist " + template.id() + "."));
        }
    }

    private void checkYears(String file, JsonNode years, List<Problem> problems) {
        int from = years.get("from").asInt();
        if (years.has("to") && years.get("to").asInt() < from) {
            problems.add(new Problem(file, "years: 'to' liegt vor 'from'."));
        }
        if (from > today.getYear() + 1) {
            problems.add(new Problem(file, "years: 'from' liegt mehr als ein Jahr in der Zukunft."));
        }
    }

    private Map<String, JsonNode> checkSources(String file, JsonNode sources, List<Problem> problems) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode source : sources) {
            String id = source.get("id").asString();
            if (byId.putIfAbsent(id, source) != null) {
                problems.add(new Problem(file, "Quelle " + id + ": id kommt mehrfach vor."));
            }
            String retrieved = source.get("retrieved").asString();
            try {
                if (LocalDate.parse(retrieved).isAfter(today)) {
                    problems.add(new Problem(file, "Quelle " + id + ": Abrufdatum " + retrieved + " liegt in der Zukunft."));
                }
            } catch (DateTimeParseException e) {
                problems.add(new Problem(file, "Quelle " + id + ": Abrufdatum " + retrieved + " ist kein Datum (JJJJ-MM-TT)."));
            }
        }
        return byId;
    }

    /** A recurring task needs an interval; a one-time task a first due point and no interval. */
    private static void checkIntervals(String file, String where, JsonNode task, List<Problem> problems) {
        boolean interval = task.has("intervalKm") || task.has("intervalMonths");
        boolean firstDue = task.has("firstDueKm") || task.has("firstDueMonths");
        if (task.path("oneTime").asBoolean(false)) {
            if (interval) {
                problems.add(new Problem(file, where + ": einmalige Aufgabe (oneTime) darf kein Intervall haben."));
            }
            if (!firstDue) {
                problems.add(new Problem(file, where + ": einmalige Aufgabe (oneTime) braucht firstDueKm oder firstDueMonths."));
            }
        } else if (!interval) {
            problems.add(new Problem(file, where + ": braucht intervalKm oder intervalMonths (oder oneTime: true)."));
        }
    }

    /**
     * Every cited id exists. A value from a forum counts only if two independent sources say the same: then at least
     * two sources from different sites must be cited.
     */
    private static void checkCitations(String file, String where, JsonNode cited, Map<String, JsonNode> sources,
                                       Set<String> used, List<Problem> problems) {
        boolean forum = false;
        Set<String> sites = new HashSet<>();
        for (JsonNode ref : cited) {
            String id = ref.asString();
            JsonNode source = sources.get(id);
            if (source == null) {
                problems.add(new Problem(file, where + ": Quelle " + id + " ist unter 'sources' nicht angegeben."));
                continue;
            }
            used.add(id);
            forum |= "FORUM".equals(source.get("kind").asString());
            sites.add(site(source));
        }
        if (forum && sites.size() < 2) {
            problems.add(new Problem(file,
                    where + ": Forenquelle allein reicht nicht; es braucht eine zweite, unabhängige Quelle (andere Seite)."));
        }
    }

    private static void checkPlausible(String file, String where, String type, JsonNode task, JsonNode entry,
                                       List<Problem> problems) {
        JsonNode ranges = entry.path("plausible").path(type);
        if (ranges.isMissingNode() || task.has("plausibilityException")) {
            return;
        }
        for (String field : List.of("intervalKm", "intervalMonths")) {
            JsonNode range = ranges.get(field);
            if (range == null || !task.has(field)) {
                continue;
            }
            int value = task.get(field).asInt();
            int min = range.get(0).asInt();
            int max = range.get(1).asInt();
            if (value < min || value > max) {
                problems.add(new Problem(file, "%s: %s = %d liegt außerhalb der plausiblen Spanne %d–%d für %s. Wert prüfen oder plausibilityException mit Begründung angeben."
                        .formatted(where, field, value, min, max, type)));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Across templates
    // ---------------------------------------------------------------------------------------------------------------

    private static void checkUniqueIds(List<Template> templates, List<Problem> problems) {
        Map<String, String> seen = new HashMap<>();
        for (Template template : templates) {
            String other = seen.putIfAbsent(template.id(), template.file());
            if (other != null) {
                problems.add(new Problem(template.file(), "id " + template.id() + " gibt es schon in " + other + "."));
            }
        }
    }

    /** Year ranges of the same model (type, make, model normalised) must not overlap; open end means until today. */
    private void checkOverlappingYears(List<Template> templates, List<Problem> problems) {
        Map<String, List<Template>> byModel = new HashMap<>();
        for (Template template : templates) {
            String model = template.type() + "|" + normalise(template.node().get("make").asString()) + "|"
                    + normalise(template.node().get("model").asString());
            byModel.computeIfAbsent(model, key -> new ArrayList<>()).add(template);
        }
        for (List<Template> group : byModel.values()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    Template a = group.get(i);
                    Template b = group.get(j);
                    if (from(a) <= to(b) && from(b) <= to(a)) {
                        problems.add(new Problem(a.file(), "Baujahre überschneiden sich mit " + b.file() + "."));
                    }
                }
            }
        }
    }

    private static int from(Template template) {
        return template.node().get("years").get("from").asInt();
    }

    private int to(Template template) {
        JsonNode to = template.node().get("years").get("to");
        return to == null ? Math.max(today.getYear() + 1, from(template)) : to.asInt();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** Lower case, every run of other characters becomes one hyphen: "Royal Enfield" → "royal-enfield". */
    static String slug(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    /** For comparing models: lower case without spaces, hyphens and dots ("CB 650 R" = "cb650r"). */
    static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-.]+", "");
    }

    /** The site of a source: host of its URL, else its title (a printed manual). */
    private static String site(JsonNode source) {
        if (source.has("url")) {
            String host = URI.create(source.get("url").asString()).getHost();
            if (host != null) {
                return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            }
        }
        return source.get("title").asString();
    }
}
