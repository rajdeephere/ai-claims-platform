package com.claimsai.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the committed contract (docs/openapi/&lt;name&gt;.v1.json) equal to what the code publishes (ADR-0008).
 * A normal build fails on any difference; {@code mvn verify -Dopenapi.update=true} rewrites the file, and
 * the change is then reviewed as a diff ("is this breaking?").
 */
public final class OpenApiContract {

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);   // stable key order = stable diffs

    private OpenApiContract() {
    }

    public static void assertMatchesCommittedContract(String liveJson, String name) throws IOException {
        String live = canonical(liveJson);
        // failsafe runs in backend/, the docs live one level up
        Path file = Path.of("..", "docs", "openapi", name + ".v1.json");

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, live, StandardCharsets.UTF_8);
            return;
        }
        assertThat(file)
                .as("No committed API contract. Generate it with: mvn verify -Dopenapi.update=true")
                .exists();
        String committed = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(live)
                .as("The API differs from %s. If intended, run 'mvn verify -Dopenapi.update=true' and review the "
                        + "diff: removed/renamed fields, new required fields or changed types are BREAKING (/api/v2).",
                        file.normalize())
                .isEqualTo(committed);
    }

    private static String canonical(String json) throws IOException {
        Object tree = CANONICAL.readValue(json, Object.class);
        return CANONICAL.writeValueAsString(tree).replace("\r\n", "\n") + "\n";
    }
}
