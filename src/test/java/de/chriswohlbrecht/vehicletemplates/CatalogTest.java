package de.chriswohlbrecht.vehicletemplates;

import static org.assertj.core.api.Assertions.assertThat;

import de.chriswohlbrecht.vehicletemplates.CatalogValidator.Problem;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The real catalog in {@code catalog/} passes every check; on failure the message lists all problems. */
class CatalogTest {

    @Test
    void catalogIsValid() {
        List<Problem> problems = new CatalogValidator(Path.of("schema"), LocalDate.now()).validate(Path.of("catalog"));

        assertThat(problems)
                .withFailMessage(() -> "Der Katalog hat %d Problem(e):%n%s".formatted(problems.size(),
                        problems.stream().map(problem -> "  - " + problem).collect(Collectors.joining("\n"))))
                .isEmpty();
    }
}
