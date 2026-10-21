package com.eventcore.architecture.fixture.orders.infrastructure;

import com.eventcore.architecture.fixture.orders.domain.Order;

public record OrderTable(Order lastSaved) {
}
