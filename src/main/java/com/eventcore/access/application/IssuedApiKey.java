package com.eventcore.access.application;

import com.eventcore.access.domain.ApiKey;
import com.eventcore.access.domain.ApiKeyToken;
import java.util.Objects;

public record IssuedApiKey(ApiKey apiKey, ApiKeyToken token) {

    public IssuedApiKey {
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(token, "token");
    }
}
