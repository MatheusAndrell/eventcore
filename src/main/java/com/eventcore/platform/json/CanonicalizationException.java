package com.eventcore.platform.json;

public class CanonicalizationException extends RuntimeException {

    public enum Reason {
        UNREPRESENTABLE_NUMBER,
        UNPAIRED_SURROGATE
    }

    private final Reason reason;
    private final String pointer;

    public CanonicalizationException(Reason reason, String pointer) {
        super(reason + " at " + (pointer.isEmpty() ? "/" : pointer));
        this.reason = reason;
        this.pointer = pointer;
    }

    public Reason reason() {
        return reason;
    }

    public String pointer() {
        return pointer;
    }
}
