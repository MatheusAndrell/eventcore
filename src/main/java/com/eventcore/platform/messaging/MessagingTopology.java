package com.eventcore.platform.messaging;

public final class MessagingTopology {

    public static final String EVENTS_EXCHANGE = "eventcore.events";
    public static final String EVENT_ACCEPTED_ROUTING_KEY = "event.accepted";
    public static final String EVENT_ACCEPTED_QUEUE = "eventcore.events.accepted";
    public static final String DEAD_LETTER_EXCHANGE = "eventcore.dlx";
    public static final String PARKING_QUEUE = "eventcore.parking";
    public static final int DELIVERY_LIMIT = 10;

    private MessagingTopology() {
    }
}
