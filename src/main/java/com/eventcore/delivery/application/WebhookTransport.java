package com.eventcore.delivery.application;

public interface WebhookTransport {

    TransportResult send(WebhookRequest request);
}
