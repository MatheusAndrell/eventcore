package com.eventcore.subscription.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

public record SubscribedEventTypes(SortedSet<String> values) {

    public static final int MAX_TYPES = 50;
    public static final int MAX_TYPE_LENGTH = 128;
    private static final String FIELD = "eventTypes";

    public SubscribedEventTypes {
        if (values == null || values.isEmpty() || values.size() > MAX_TYPES) {
            throw new InvalidSubscriptionException(FIELD, "must contain between 1 and " + MAX_TYPES + " event types");
        }
        for (String value : values) {
            if (value.isBlank() || value.length() > MAX_TYPE_LENGTH) {
                throw new InvalidSubscriptionException(FIELD,
                        "each event type must have between 1 and " + MAX_TYPE_LENGTH + " characters");
            }
        }
        values = Collections.unmodifiableSortedSet(new TreeSet<>(values));
    }

    public static SubscribedEventTypes of(Collection<String> values) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) {
            throw new InvalidSubscriptionException(FIELD, "must not contain null event types");
        }
        return new SubscribedEventTypes(new TreeSet<>(values));
    }
}
