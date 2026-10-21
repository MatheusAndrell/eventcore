package com.eventcore.access.application;

import com.eventcore.access.domain.ClientName;

public class ClientNameTakenException extends RuntimeException {

    public ClientNameTakenException(ClientName name) {
        super("a client named '" + name + "' already exists");
    }
}
