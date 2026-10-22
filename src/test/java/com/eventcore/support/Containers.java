package com.eventcore.support;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

public final class Containers {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.6-alpine");
    private static final DockerImageName RABBITMQ_IMAGE = DockerImageName.parse("rabbitmq:4.3.6-management-alpine");
    private static final DockerImageName TOXIPROXY_IMAGE = DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.13.0");

    private Containers() {
    }

    public static PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }

    public static RabbitMQContainer rabbitmq() {
        return new RabbitMQContainer(RABBITMQ_IMAGE);
    }

    public static ToxiproxyContainer toxiproxy() {
        return new ToxiproxyContainer(TOXIPROXY_IMAGE);
    }
}
