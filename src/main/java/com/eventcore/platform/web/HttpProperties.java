package com.eventcore.platform.web;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("eventcore.http")
public record HttpProperties(@DefaultValue("256KB") @NotNull DataSize maxRequestBodySize) {

    static final DataSize MAX_ALLOWED_REQUEST_BODY_SIZE = DataSize.ofMegabytes(1);

    public HttpProperties {
        if (maxRequestBodySize != null && (maxRequestBodySize.toBytes() < 1
                || maxRequestBodySize.compareTo(MAX_ALLOWED_REQUEST_BODY_SIZE) > 0)) {
            throw new IllegalArgumentException(
                    "eventcore.http.max-request-body-size must be between 1B and " + MAX_ALLOWED_REQUEST_BODY_SIZE);
        }
    }
}
