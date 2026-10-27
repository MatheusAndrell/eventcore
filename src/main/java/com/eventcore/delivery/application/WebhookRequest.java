package com.eventcore.delivery.application;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

public record WebhookRequest(URI endpoint, Map<String, String> headers, byte[] body) {

    public WebhookRequest {
        Objects.requireNonNull(endpoint, "endpoint");
        headers = Map.copyOf(headers);
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
