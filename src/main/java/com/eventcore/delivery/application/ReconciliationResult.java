package com.eventcore.delivery.application;

public record ReconciliationResult(int requeuedEvents, int requeuedDeliveries, int exhaustedDeliveries) {

    public boolean changedAnything() {
        return requeuedEvents > 0 || requeuedDeliveries > 0 || exhaustedDeliveries > 0;
    }
}
