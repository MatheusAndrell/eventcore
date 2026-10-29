package com.eventcore.operations.application;

import java.util.Objects;
import java.util.Optional;

public record ReplayResult(int replayed, long remaining, Optional<String> stoppedBecause) {

    public ReplayResult {
        Objects.requireNonNull(stoppedBecause, "stoppedBecause");
    }
}
