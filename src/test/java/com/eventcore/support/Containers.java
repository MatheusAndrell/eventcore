package com.eventcore.support;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class Containers {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.6-alpine");

    private Containers() {
    }

    public static PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }
}
