package com.eventcore.catalog.domain;

public enum VersionStatus {
    ACTIVE,
    DEPRECATED,
    RETIRED;

    public boolean acceptsNewEvents() {
        return this != RETIRED;
    }
}
