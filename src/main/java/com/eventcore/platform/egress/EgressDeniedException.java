package com.eventcore.platform.egress;

public class EgressDeniedException extends RuntimeException {

    EgressDeniedException(String reason) {
        super(reason);
    }

    EgressDeniedException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
