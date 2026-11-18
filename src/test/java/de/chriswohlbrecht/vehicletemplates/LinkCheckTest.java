package de.chriswohlbrecht.vehicletemplates;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Weekly check of all source links (needs the internet, hence tag "links", excluded from the normal build). Writes
 * {@code target/link-check.md}; the workflow turns dead links into an issue. Dead means 404/410 or a host that does
 * not exist; anything else unusual (403 from bot protection, 5xx, timeouts) is only listed as unclear.
 */
@Tag("links")
class LinkCheckTest {

    static final Path REPORT = Path.of("target/link-check.md");

    private static final Set<Integer> DEAD_STATUS = Set.of(404, 410);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private record Link(String file, String source, String url) {
    }

    private record Result(Link link, String outcome) {
    }

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(TIMEOUT)
            .build();

    @Test
    void sourceLinksAreReachable() throws IOException {
        List<Result> dead = new ArrayList<>();
        List<Result> unclear = new ArrayList<>();
        for (Link link : links(Path.of("catalog"))) {
            String outcome = check(link.url());
            if (outcome.startsWith("tot")) {
                dead.add(new Result(link, outcome));
            } else if (!outcome.equals("ok")) {
                unclear.add(new Result(link, outcome));
            }
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report(dead, unclear));

        assertThat(dead).withFailMessage(() -> "Tote Links:%n%s".formatted(report(dead, List.of()))).isEmpty();
    }

    private static List<Link> links(Path catalog) {
        ObjectMapper yaml = YAMLMapper.builder().build();
        List<Link> links = new ArrayList<>();
        try (Stream<Path> files = Files.walk(catalog)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".yaml")).sorted().toList()) {
                JsonNode node = yaml.readTree(file.toFile());
                for (JsonNode source : node.path("sources")) {
                    if (source.has("url")) {
                        links.add(new Link(catalog.relativize(file).toString(), source.get("id").asString(),
                                source.get("url").asString()));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return links;
    }

    /** HEAD first; servers that refuse HEAD get a GET. */
    private String check(String url) {
        try {
            int status = request(url, "HEAD");
            if (status == 403 || status == 405 || status == 501) {
                status = request(url, "GET");
            }
            if (status < 400) {
                return "ok";
            }
            return DEAD_STATUS.contains(status) ? "tot (HTTP " + status + ")" : "unklar (HTTP " + status + ")";
        } catch (UnknownHostException e) {
            return "tot (Host unbekannt)";
        } catch (ConnectException e) {
            return "unklar (keine Verbindung)";
        } catch (HttpTimeoutException e) {
            return "unklar (Zeitüberschreitung)";
        } catch (IOException e) {
            return "unklar (" + e.getClass().getSimpleName() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unklar (abgebrochen)";
        }
    }

    private int request(String url, String method) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(TIMEOUT)
                .header("User-Agent", "bike-maintainer-templates link check (+https://github.com/stargazer74/bike-maintainer-templates)")
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String report(List<Result> dead, List<Result> unclear) {
        StringBuilder report = new StringBuilder();
        append(report, "Tote Links", dead);
        append(report, "Unklar (bitte von Hand prüfen, blockiert nichts)", unclear);
        return report.toString();
    }

    private static void append(StringBuilder report, String title, List<Result> results) {
        if (results.isEmpty()) {
            return;
        }
        report.append("### ").append(title).append("\n\n| Datei | Quelle | Link | Ergebnis |\n|---|---|---|---|\n");
        for (Result result : results) {
            report.append("| `%s` | `%s` | %s | %s |%n".formatted(result.link().file(), result.link().source(),
                    result.link().url(), result.outcome()));
        }
        report.append('\n');
    }
}
