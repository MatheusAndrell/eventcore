package com.eventcore.architecture.fixture.orders.application;

import com.eventcore.architecture.fixture.billing.application.InvoiceOrder;
import com.eventcore.architecture.fixture.orders.web.OrderController;

public record PlaceOrder(OrderController controller, InvoiceOrder invoicing) {
}
