package com.eventcore.architecture.fixture.billing.domain;

import com.eventcore.architecture.fixture.shared.Money;

public record Invoice(String id, Money amount) {
}
