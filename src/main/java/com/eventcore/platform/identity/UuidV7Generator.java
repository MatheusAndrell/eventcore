package com.eventcore.platform.identity;

import java.time.Clock;
import java.util.UUID;
import java.util.random.RandomGenerator;

public final class UuidV7Generator {

    private static final long MAX_COUNTER = 0xFFFL;
    private static final long COUNTER_SEED_MASK = 0x7FFL;
    private static final long VERSION_BITS = 0x7L << 12;
    private static final long VARIANT_BITS = 0x8000000000000000L;
    private static final long RANDOM_B_MASK = 0x3FFFFFFFFFFFFFFFL;

    private final Clock clock;
    private final RandomGenerator random;
    private long lastMillis = -1;
    private long counter;

    public UuidV7Generator(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
    }

    public synchronized UUID next() {
        advance(clock.millis());
        long mostSignificantBits = (lastMillis << 16) | VERSION_BITS | counter;
        long leastSignificantBits = VARIANT_BITS | (random.nextLong() & RANDOM_B_MASK);
        return new UUID(mostSignificantBits, leastSignificantBits);
    }

    private void advance(long nowMillis) {
        if (nowMillis > lastMillis) {
            lastMillis = nowMillis;
            counter = seedCounter();
            return;
        }
        counter++;
        if (counter > MAX_COUNTER) {
            lastMillis++;
            counter = seedCounter();
        }
    }

    private long seedCounter() {
        return random.nextLong() & COUNTER_SEED_MASK;
    }
}
