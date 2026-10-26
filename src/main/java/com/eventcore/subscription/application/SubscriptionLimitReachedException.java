package com.eventcore.subscription.application;

public class SubscriptionLimitReachedException extends RuntimeException {

    SubscriptionLimitReachedException(int limit) {
        super("a client can have at most " + limit + " subscriptions");
    }
}
