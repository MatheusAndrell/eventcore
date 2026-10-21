package com.eventcore.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventcore.support.MutableClock;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00.123Z");

    @Test
    void producesVersion7WithRfc9562Variant() {
        UUID id = generator(Clock.fixed(NOW, ZoneOffset.UTC)).next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void encodesTheUnixTimestampInMilliseconds() {
        UUID id = generator(Clock.fixed(NOW, ZoneOffset.UTC)).next();

        assertThat(timestampOf(id)).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void increasesMonotonicallyWithinTheSameMillisecond() {
        UuidV7Generator generator = generator(Clock.fixed(NOW, ZoneOffset.UTC));

        List<UUID> ids = generate(generator, 1_000);

        assertStrictlyIncreasing(ids);
        assertThat(ids).allSatisfy(id -> assertThat(timestampOf(id)).isEqualTo(NOW.toEpochMilli()));
    }

    @Test
    void advancesTheTimestampWhenTheCounterIsExhausted() {
        UuidV7Generator generator = generator(Clock.fixed(NOW, ZoneOffset.UTC));

        List<UUID> ids = generate(generator, 10_000);

        assertStrictlyIncreasing(ids);
        assertThat(new HashSet<>(ids)).hasSize(ids.size());
        assertThat(timestampOf(ids.getLast())).isGreaterThan(NOW.toEpochMilli());
    }

    @Test
    void remainsMonotonicWhenTheClockMovesBackwards() {
        MutableClock clock = new MutableClock(NOW);
        UuidV7Generator generator = generator(clock);
        UUID beforeRegression = generator.next();

        clock.set(NOW.minusSeconds(5));
        UUID afterRegression = generator.next();

        assertThat(Long.compareUnsigned(
                afterRegression.getMostSignificantBits(), beforeRegression.getMostSignificantBits())).isPositive();
    }

    @Test
    void producesUniqueIdsThatIncreaseWithinEachThread() throws Exception {
        UuidV7Generator generator = new UuidV7Generator(Clock.systemUTC(), new SecureRandom());
        int threads = 8;
        int idsPerThread = 10_000;
        List<Future<List<UUID>>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            Callable<List<UUID>> task = () -> generate(generator, idsPerThread);
            for (int thread = 0; thread < threads; thread++) {
                results.add(executor.submit(task));
            }
            Set<UUID> all = new HashSet<>();
            for (Future<List<UUID>> result : results) {
                List<UUID> ids = result.get();
                assertStrictlyIncreasing(ids);
                all.addAll(ids);
            }
            assertThat(all).hasSize(threads * idsPerThread);
        }
    }

    private static UuidV7Generator generator(Clock clock) {
        return new UuidV7Generator(clock, new SplittableRandom(42));
    }

    private static List<UUID> generate(UuidV7Generator generator, int count) {
        List<UUID> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ids.add(generator.next());
        }
        return ids;
    }

    private static long timestampOf(UUID id) {
        return id.getMostSignificantBits() >>> 16;
    }

    private static void assertStrictlyIncreasing(List<UUID> ids) {
        for (int index = 1; index < ids.size(); index++) {
            assertThat(Long.compareUnsigned(
                    ids.get(index).getMostSignificantBits(), ids.get(index - 1).getMostSignificantBits()))
                    .as("id %d must sort after id %d", index, index - 1)
                    .isPositive();
        }
    }
}
