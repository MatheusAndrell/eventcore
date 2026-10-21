package com.eventcore.catalog.application;

import com.eventcore.catalog.domain.VersionStatus;
import java.util.Objects;

public record CatalogVersion(int version, VersionStatus status, DataSchema schema) {

    public CatalogVersion {
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive: " + version);
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(schema, "schema");
    }
}
