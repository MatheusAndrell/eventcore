package com.eventcore.operations.application;

public interface ParkedMessages {

    long count();

    ReplayResult replay(int limit);
}
