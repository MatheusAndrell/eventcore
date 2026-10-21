package com.eventcore.catalog.application;

import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toUnmodifiableMap;

import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

public final class EventCatalog {

    private final Map<String, Map<Integer, CatalogVersion>> eventTypes;

    public EventCatalog(Map<String, Map<Integer, CatalogVersion>> eventTypes) {
        this.eventTypes = eventTypes.entrySet().stream()
                .collect(toUnmodifiableMap(Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
    }

    public CatalogCheck check(String type, int version, JsonNode data) {
        Map<Integer, CatalogVersion> versions = eventTypes.get(type);
        if (versions == null) {
            return new CatalogCheck.UnknownType();
        }
        CatalogVersion catalogVersion = versions.get(version);
        if (catalogVersion == null || !catalogVersion.status().acceptsNewEvents()) {
            return new CatalogCheck.UnsupportedVersion(acceptedVersions(versions));
        }
        List<String> violations = catalogVersion.schema().violations(data);
        if (!violations.isEmpty()) {
            return new CatalogCheck.InvalidData(violations);
        }
        return new CatalogCheck.Accepted(catalogVersion.status());
    }

    private static SortedSet<Integer> acceptedVersions(Map<Integer, CatalogVersion> versions) {
        return versions.values().stream()
                .filter(candidate -> candidate.status().acceptsNewEvents())
                .map(CatalogVersion::version)
                .collect(toCollection(TreeSet::new));
    }
}
