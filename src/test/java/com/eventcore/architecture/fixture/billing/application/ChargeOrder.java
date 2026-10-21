package com.eventcore.architecture.fixture.billing.application;

import com.eventcore.architecture.fixture.orders.infrastructure.OrderTable;
import com.eventcore.architecture.fixture.shared.Money;

public record ChargeOrder(OrderTable table, Money amount) {
}
