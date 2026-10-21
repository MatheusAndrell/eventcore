package com.eventcore.access.web;

import com.eventcore.access.application.AuthenticatedClient;
import com.eventcore.access.domain.Scope;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

final class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedClient client;

    ApiKeyAuthentication(AuthenticatedClient client) {
        super(client.scopes().stream().map(ApiKeyAuthentication::authority).map(SimpleGrantedAuthority::new).toList());
        this.client = client;
        setAuthenticated(true);
    }

    static String authority(Scope scope) {
        return "SCOPE_" + scope.value();
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public AuthenticatedClient getPrincipal() {
        return client;
    }

    @Override
    public String getName() {
        return client.keyId();
    }
}
