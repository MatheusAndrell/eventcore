package com.eventcore.outbox.application;

import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnRole(Role.RELAY)
class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final Duration STOP_GRACE = Duration.ofSeconds(5);

    private final OutboxPublisher publisher;
    private final OutboxProperties properties;
    private final Object pauseMonitor = new Object();
    private volatile boolean running;
    private Thread worker;

    OutboxRelay(OutboxPublisher publisher, OutboxProperties properties) {
        this.publisher = publisher;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = Thread.ofPlatform().name("outbox-relay").start(this::run);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        synchronized (pauseMonitor) {
            pauseMonitor.notifyAll();
        }
        try {
            worker.join(properties.confirmTimeout().plus(STOP_GRACE));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            log.warn("outbox relay did not finish its batch before the stop deadline; leases will expire");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        Duration failureDelay = properties.initialRetryDelay();
        while (running) {
            try {
                BatchResult result = publisher.publishBatch();
                if (result.brokerUnavailable()) {
                    pause(failureDelay);
                    failureDelay = doubled(failureDelay);
                } else {
                    failureDelay = properties.initialRetryDelay();
                    if (result.claimed() < properties.batchSize()) {
                        pause(properties.pollInterval());
                    }
                }
            } catch (RuntimeException exception) {
                log.error("outbox relay iteration failed; retrying in {}", failureDelay, exception);
                pause(failureDelay);
                failureDelay = doubled(failureDelay);
            }
        }
    }

    private Duration doubled(Duration delay) {
        Duration doubled = delay.multipliedBy(2);
        return doubled.compareTo(properties.maxRetryDelay()) > 0 ? properties.maxRetryDelay() : doubled;
    }

    private void pause(Duration duration) {
        synchronized (pauseMonitor) {
            if (!running) {
                return;
            }
            try {
                pauseMonitor.wait(Math.max(1, duration.toMillis()));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }
}
