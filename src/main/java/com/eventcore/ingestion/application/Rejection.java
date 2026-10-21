package com.eventcore.ingestion.application;

public enum Rejection {
    MALFORMED_ENVELOPE,
    INVALID_STRING,
    NUMBER_NOT_REPRESENTABLE,
    UNKNOWN_EVENT_TYPE,
    UNSUPPORTED_EVENT_VERSION,
    INVALID_EVENT_DATA,
    OCCURRED_IN_FUTURE
}
