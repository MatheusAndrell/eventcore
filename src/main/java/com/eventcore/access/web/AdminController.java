package com.eventcore.access.web;

import com.eventcore.access.application.ClientAdministration;
import com.eventcore.access.application.ClientNameTakenException;
import com.eventcore.access.domain.ClientName;
import com.eventcore.access.domain.InvalidClientNameException;
import com.eventcore.access.domain.Scope;
import com.eventcore.access.web.AdminRepresentations.ApiKeyResponse;
import com.eventcore.access.web.AdminRepresentations.ChangeClientStatusRequest;
import com.eventcore.access.web.AdminRepresentations.ClientResponse;
import com.eventcore.access.web.AdminRepresentations.CreateClientRequest;
import com.eventcore.access.web.AdminRepresentations.IssueApiKeyRequest;
import com.eventcore.access.web.AdminRepresentations.IssuedApiKeyResponse;
import com.eventcore.platform.identity.ClientId;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import com.eventcore.platform.web.Problems;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnRole(Role.API)
@RequestMapping("/admin/clients")
class AdminController {

    private final ClientAdministration administration;
    private final Clock clock;

    AdminController(ClientAdministration administration, Clock clock) {
        this.administration = administration;
        this.clock = clock;
    }

    @PostMapping
    ResponseEntity<ClientResponse> createClient(@Valid @RequestBody CreateClientRequest request) {
        ClientResponse client;
        try {
            client = ClientResponse.of(administration.createClient(clientName(request.name())));
        } catch (ClientNameTakenException exception) {
            throw Problems.exception(HttpStatus.CONFLICT, "client-name-taken", "Client name taken",
                    exception.getMessage());
        }
        return ResponseEntity.created(URI.create("/admin/clients/" + client.id())).body(client);
    }

    @GetMapping("/{clientId}")
    ResponseEntity<ClientResponse> client(@PathVariable UUID clientId) {
        return ResponseEntity.of(administration.findClient(new ClientId(clientId)).map(ClientResponse::of));
    }

    @PutMapping("/{clientId}/status")
    ResponseEntity<ClientResponse> changeStatus(
            @PathVariable UUID clientId, @Valid @RequestBody ChangeClientStatusRequest request) {
        return ResponseEntity.of(administration.changeStatus(new ClientId(clientId), request.status())
                .map(ClientResponse::of));
    }

    @PostMapping("/{clientId}/api-keys")
    ResponseEntity<IssuedApiKeyResponse> issueApiKey(
            @PathVariable UUID clientId, @Valid @RequestBody IssueApiKeyRequest request) {
        Optional<Instant> expiresAt = Optional.ofNullable(request.expiresAt());
        if (expiresAt.filter(instant -> !instant.isAfter(clock.instant())).isPresent()) {
            throw invalidRequest("expiresAt must be in the future");
        }
        return administration.issueApiKey(new ClientId(clientId), scopes(request.scopes()), expiresAt)
                .map(IssuedApiKeyResponse::of)
                .map(issued -> ResponseEntity
                        .created(URI.create("/admin/clients/" + clientId + "/api-keys/" + issued.id()))
                        .body(issued))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{clientId}/api-keys")
    ResponseEntity<List<ApiKeyResponse>> apiKeys(@PathVariable UUID clientId) {
        return ResponseEntity.of(administration.listApiKeys(new ClientId(clientId))
                .map(keys -> keys.stream().map(ApiKeyResponse::of).toList()));
    }

    @DeleteMapping("/{clientId}/api-keys/{apiKeyId}")
    ResponseEntity<Void> revokeApiKey(@PathVariable UUID clientId, @PathVariable UUID apiKeyId) {
        return administration.revokeApiKey(new ClientId(clientId), apiKeyId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private static ErrorResponseException invalidRequest(String detail) {
        return Problems.exception(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", detail);
    }

    private static ClientName clientName(String name) {
        try {
            return new ClientName(name);
        } catch (InvalidClientNameException exception) {
            throw invalidRequest(exception.getMessage());
        }
    }

    private static Set<Scope> scopes(List<String> values) {
        Set<Scope> scopes = EnumSet.noneOf(Scope.class);
        List<String> unknown = new ArrayList<>();
        for (String value : values) {
            Scope.fromValue(value).ifPresentOrElse(scopes::add, () -> unknown.add(value));
        }
        if (!unknown.isEmpty()) {
            throw Problems.exception(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", "unknown scopes",
                    unknown.stream().map(value -> "scopes: unknown scope '" + value + "'").toList());
        }
        return scopes;
    }
}
