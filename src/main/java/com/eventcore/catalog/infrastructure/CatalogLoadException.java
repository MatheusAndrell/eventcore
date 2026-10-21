package com.eventcore.catalog.infrastructure;

public class CatalogLoadException extends RuntimeException {

    CatalogLoadException(String message) {
        super(message);
    }

    CatalogLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
