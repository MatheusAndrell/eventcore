package com.eventcore.architecture.fixture.orders.domain;

import java.sql.Timestamp;

public record Order(String id, Timestamp placedAt) {
}
