package com.eventcore.catalog.infrastructure;

import com.eventcore.catalog.application.CatalogVersion;
import com.eventcore.catalog.application.EventCatalog;
import com.eventcore.catalog.domain.VersionStatus;
import com.eventcore.platform.json.InvalidJsonException;
import com.eventcore.platform.json.StrictJsonReader;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaException;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.JsonNode;

final class CatalogLoader {

    static final String MANIFEST = "catalog.json";
    static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

    private static final Pattern TYPE_NAME = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+");
    private static final int MAX_TYPE_LENGTH = 128;
    private static final Pattern VERSION = Pattern.compile("[1-9][0-9]{0,8}");
    private static final Set<String> REFERENCE_KEYWORDS = Set.of("$ref", "$dynamicRef");

    private final ResourceLoader resourceLoader;
    private final StrictJsonReader reader;
    private final SchemaRegistry registry;
    private final Schema metaSchema;

    CatalogLoader(ResourceLoader resourceLoader, StrictJsonReader reader) {
        this.resourceLoader = resourceLoader;
        this.reader = reader;
        this.registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false)));
        this.metaSchema = registry.getSchema(SchemaLocation.of(DIALECT));
    }

    EventCatalog load(String location) {
        String base = location.endsWith("/") ? location : location + "/";
        JsonNode eventTypes = manifestEventTypes(base + MANIFEST);
        Map<String, Map<Integer, CatalogVersion>> catalog = new HashMap<>();
        for (String type : eventTypes.propertyNames()) {
            requireValidTypeName(type);
            catalog.put(type, loadVersions(base, type, eventTypes.get(type)));
        }
        return new EventCatalog(catalog);
    }

    private JsonNode manifestEventTypes(String path) {
        JsonNode manifest = readJson(path);
        if (!manifest.isObject() || manifest.size() != 1 || !manifest.has("eventTypes")) {
            throw new CatalogLoadException(path + ": manifest must be an object with a single 'eventTypes' member");
        }
        JsonNode eventTypes = manifest.get("eventTypes");
        if (!eventTypes.isObject() || eventTypes.isEmpty()) {
            throw new CatalogLoadException(path + ": 'eventTypes' must be a non-empty object");
        }
        return eventTypes;
    }

    private static void requireValidTypeName(String type) {
        if (type.length() > MAX_TYPE_LENGTH || !TYPE_NAME.matcher(type).matches()) {
            throw new CatalogLoadException("invalid event type name '" + type + "'");
        }
    }

    private Map<Integer, CatalogVersion> loadVersions(String base, String type, JsonNode versions) {
        if (!versions.isObject() || versions.isEmpty()) {
            throw new CatalogLoadException(type + ": versions must be a non-empty object");
        }
        Map<Integer, CatalogVersion> loaded = new HashMap<>();
        for (String version : versions.propertyNames()) {
            if (!VERSION.matcher(version).matches()) {
                throw new CatalogLoadException(type + ": invalid version '" + version + "'");
            }
            int number = Integer.parseInt(version);
            VersionStatus status = status(type, version, versions.get(version));
            String schemaPath = base + type + "/v" + number + ".schema.json";
            loaded.put(number, new CatalogVersion(number, status, compile(schemaPath)));
        }
        return loaded;
    }

    private static VersionStatus status(String type, String version, JsonNode value) {
        if (value.isString()) {
            for (VersionStatus status : VersionStatus.values()) {
                if (status.name().equals(value.stringValue())) {
                    return status;
                }
            }
        }
        throw new CatalogLoadException(type + " v" + version + ": status must be one of "
                + Arrays.toString(VersionStatus.values()));
    }

    private NetworkntDataSchema compile(String path) {
        JsonNode schemaNode = readJson(path);
        if (!schemaNode.isObject() || !DIALECT.equals(schemaNode.path("$schema").stringValue(null))) {
            throw new CatalogLoadException(path + ": schema must be an object declaring \"$schema\": \"" + DIALECT + "\"");
        }
        requireOnlyLocalReferences(path, schemaNode);
        List<Error> metaSchemaErrors = metaSchema.validate(schemaNode);
        if (!metaSchemaErrors.isEmpty()) {
            throw new CatalogLoadException(path + ": not a valid JSON Schema: " + metaSchemaErrors);
        }
        try {
            Schema schema = registry.getSchema(schemaNode);
            schema.initializeValidators();
            return new NetworkntDataSchema(schema);
        } catch (SchemaException exception) {
            throw new CatalogLoadException(path + ": schema cannot be compiled", exception);
        }
    }

    private static void requireOnlyLocalReferences(String path, JsonNode node) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                JsonNode value = property.getValue();
                if (REFERENCE_KEYWORDS.contains(property.getKey())
                        && (!value.isString() || !value.stringValue().startsWith("#"))) {
                    throw new CatalogLoadException(path + ": only local references are allowed, found "
                            + property.getKey() + " " + value);
                }
                requireOnlyLocalReferences(path, value);
            }
        } else if (node.isArray()) {
            node.values().forEach(element -> requireOnlyLocalReferences(path, element));
        }
    }

    private JsonNode readJson(String path) {
        Resource resource = resourceLoader.getResource(path);
        if (!resource.exists()) {
            throw new CatalogLoadException(path + ": not found");
        }
        try (InputStream input = resource.getInputStream()) {
            return reader.read(input);
        } catch (IOException exception) {
            throw new CatalogLoadException(path + ": cannot be read", exception);
        } catch (InvalidJsonException exception) {
            throw new CatalogLoadException(path + ": invalid JSON: " + exception.getMessage(), exception);
        }
    }
}
