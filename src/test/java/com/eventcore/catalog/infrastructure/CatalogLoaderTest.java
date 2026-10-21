package com.eventcore.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventcore.catalog.application.CatalogCheck;
import com.eventcore.catalog.application.EventCatalog;
import com.eventcore.catalog.domain.VersionStatus;
import com.eventcore.platform.json.StrictJsonReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;

class CatalogLoaderTest {

    private static final String SINGLE_VERSION_MANIFEST = """
            {"eventTypes": {"sample.created": {"1": "ACTIVE"}}}
            """;

    private static final String NAME_SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "additionalProperties": false,
              "required": ["name"],
              "properties": {"name": {"$ref": "#/$defs/name"}},
              "$defs": {"name": {"type": "string", "minLength": 1}}
            }
            """;

    private final StrictJsonReader reader = new StrictJsonReader();
    private final CatalogLoader loader = new CatalogLoader(new DefaultResourceLoader(), reader);

    @TempDir
    Path directory;

    @Test
    void loadsVersionsWithTheirStatusAndValidatesData() throws IOException {
        EventCatalog catalog = load(Map.of(
                "catalog.json", """
                        {"eventTypes": {"sample.created": {"1": "ACTIVE", "2": "DEPRECATED", "3": "RETIRED"}}}
                        """,
                "sample.created/v1.schema.json", NAME_SCHEMA,
                "sample.created/v2.schema.json", NAME_SCHEMA,
                "sample.created/v3.schema.json", NAME_SCHEMA));

        assertThat(catalog.check("sample.created", 1, json("{\"name\":\"a\"}")))
                .isEqualTo(new CatalogCheck.Accepted(VersionStatus.ACTIVE));
        assertThat(catalog.check("sample.created", 2, json("{\"name\":\"a\"}")))
                .isEqualTo(new CatalogCheck.Accepted(VersionStatus.DEPRECATED));
        assertThat(catalog.check("sample.created", 3, json("{\"name\":\"a\"}")))
                .isInstanceOfSatisfying(CatalogCheck.UnsupportedVersion.class,
                        unsupported -> assertThat(unsupported.acceptedVersions()).containsExactly(1, 2));
        assertThat(catalog.check("sample.created", 4, json("{\"name\":\"a\"}")))
                .isInstanceOf(CatalogCheck.UnsupportedVersion.class);
        assertThat(catalog.check("sample.updated", 1, json("{\"name\":\"a\"}")))
                .isInstanceOf(CatalogCheck.UnknownType.class);
    }

    @Test
    void reportsSchemaViolationsWithTheirLocation() throws IOException {
        EventCatalog catalog = load(Map.of(
                "catalog.json", SINGLE_VERSION_MANIFEST,
                "sample.created/v1.schema.json", NAME_SCHEMA));

        CatalogCheck check = catalog.check("sample.created", 1, json("{\"name\":\"\",\"extra\":true}"));

        assertThat(check).isInstanceOfSatisfying(CatalogCheck.InvalidData.class, invalid -> assertThat(
                invalid.violations()).hasSize(2).anySatisfy(violation -> assertThat(violation).contains("name"))
                .anySatisfy(violation -> assertThat(violation).contains("extra")));
    }

    @Test
    void rejectsRemoteReferences() throws IOException {
        assertLoadFails(schemaWithReference("https://example.com/name.json"), "only local references are allowed");
    }

    @Test
    void rejectsRelativeFileReferences() throws IOException {
        assertLoadFails(schemaWithReference("name.schema.json"), "only local references are allowed");
    }

    @Test
    void rejectsSchemasThatViolateTheMetaSchema() throws IOException {
        assertLoadFails("""
                {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "strnig"}
                """, "not a valid JSON Schema");
    }

    @Test
    void rejectsOtherDialects() throws IOException {
        assertLoadFails("""
                {"$schema": "http://json-schema.org/draft-07/schema#", "type": "object"}
                """, "schema must be an object declaring");
    }

    @Test
    void rejectsMissingSchemaFiles() throws IOException {
        assertThatThrownBy(() -> load(Map.of("catalog.json", SINGLE_VERSION_MANIFEST)))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("v1.schema.json: not found");
    }

    @Test
    void rejectsInvalidEventTypeNames() throws IOException {
        assertThatThrownBy(() -> load(Map.of(
                "catalog.json", "{\"eventTypes\": {\"Sample.Created\": {\"1\": \"ACTIVE\"}}}",
                "Sample.Created/v1.schema.json", NAME_SCHEMA)))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("invalid event type name 'Sample.Created'");
    }

    @Test
    void rejectsUnknownStatuses() throws IOException {
        assertThatThrownBy(() -> load(Map.of(
                "catalog.json", "{\"eventTypes\": {\"sample.created\": {\"1\": \"LIVE\"}}}",
                "sample.created/v1.schema.json", NAME_SCHEMA)))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("status must be one of");
    }

    @Test
    void rejectsInvalidVersionKeys() throws IOException {
        assertThatThrownBy(() -> load(Map.of(
                "catalog.json", "{\"eventTypes\": {\"sample.created\": {\"01\": \"ACTIVE\"}}}")))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("invalid version '01'");
    }

    @Test
    void rejectsManifestsWithDuplicateKeys() throws IOException {
        assertThatThrownBy(() -> load(Map.of(
                "catalog.json", """
                        {"eventTypes": {"sample.created": {"1": "ACTIVE"}, "sample.created": {"2": "ACTIVE"}}}
                        """,
                "sample.created/v1.schema.json", NAME_SCHEMA)))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("invalid JSON");
    }

    @Test
    void rejectsManifestsWithUnexpectedMembers() throws IOException {
        assertThatThrownBy(() -> load(Map.of(
                "catalog.json", "{\"eventTypes\": {}, \"extra\": true}")))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining("single 'eventTypes' member");
    }

    private void assertLoadFails(String schema, String expectedMessage) throws IOException {
        assertThatThrownBy(() -> load(Map.of(
                "catalog.json", SINGLE_VERSION_MANIFEST,
                "sample.created/v1.schema.json", schema)))
                .isInstanceOf(CatalogLoadException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static String schemaWithReference(String reference) {
        return """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "properties": {"name": {"$ref": "%s"}}
                }
                """.formatted(reference);
    }

    private EventCatalog load(Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = directory.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
        }
        return loader.load(directory.toUri().toString());
    }

    private JsonNode json(String text) {
        return reader.read(text.getBytes(StandardCharsets.UTF_8));
    }
}
