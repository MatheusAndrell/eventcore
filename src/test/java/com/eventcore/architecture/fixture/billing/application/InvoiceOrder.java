package com.eventcore.architecture.fixture.billing.application;

import com.eventcore.architecture.fixture.orders.application.PlaceOrder;

public record InvoiceOrder(PlaceOrder orders) {
}
