package com.eventcore.subscription.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

public record WebhookEndpoint(URI uri) {

    public static final int MAX_LENGTH = 2048;
    private static final String SCHEME = "https";
    private static final String FIELD = "url";

    public WebhookEndpoint {
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute() || !SCHEME.equals(uri.getScheme())) {
            throw invalid("must be an absolute https URL");
        }
        if (uri.getHost() == null) {
            throw invalid("must have a valid host name");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalid("must not contain user information");
        }
        if (uri.getRawFragment() != null) {
            throw invalid("must not contain a fragment");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65_535) {
            throw invalid("must use a port between 1 and 65535");
        }
        if (uri.toString().length() > MAX_LENGTH) {
            throw invalid("must have at most " + MAX_LENGTH + " characters");
        }
    }

    public static WebhookEndpoint parse(String value) {
        if (value == null || value.length() > MAX_LENGTH) {
            throw invalid("must have at most " + MAX_LENGTH + " characters");
        }
        try {
            return new WebhookEndpoint(new URI(withLowercaseScheme(value)));
        } catch (URISyntaxException exception) {
            throw invalid("must be a valid URL");
        }
    }

    @Override
    public String toString() {
        return uri.toString();
    }

    private static String withLowercaseScheme(String value) {
        int separator = value.indexOf(':');
        return separator < 0
                ? value
                : value.substring(0, separator).toLowerCase(Locale.ROOT) + value.substring(separator);
    }

    private static InvalidSubscriptionException invalid(String message) {
        return new InvalidSubscriptionException(FIELD, message);
    }
}
