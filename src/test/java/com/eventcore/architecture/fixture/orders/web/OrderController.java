package com.eventcore.architecture.fixture.orders.web;

import com.eventcore.architecture.fixture.orders.infrastructure.OrderTable;
import org.springframework.jdbc.core.simple.JdbcClient;

public record OrderController(OrderTable table, JdbcClient jdbc) {
}
