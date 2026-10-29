package com.eventcore.operations.web;

import com.eventcore.operations.application.BrokerUnavailableException;
import com.eventcore.operations.application.ParkedMessages;
import com.eventcore.operations.application.ReplayResult;
import com.eventcore.platform.roles.ConditionalOnRole;
import com.eventcore.platform.roles.Role;
import com.eventcore.platform.web.Problems;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnRole(Role.API)
@RequestMapping(path = "/admin/parking", produces = MediaType.APPLICATION_JSON_VALUE)
class ParkingController {

    private final ParkedMessages parkedMessages;

    ParkingController(ParkedMessages parkedMessages) {
        this.parkedMessages = parkedMessages;
    }

    @GetMapping
    ParkingResponse parking() {
        return new ParkingResponse(withBroker(parkedMessages::count));
    }

    @PostMapping(path = "/replay", consumes = MediaType.APPLICATION_JSON_VALUE)
    ReplayResponse replay(@Valid @RequestBody ReplayRequest request) {
        ReplayResult result = withBroker(() -> parkedMessages.replay(request.limit()));
        return new ReplayResponse(result.replayed(), result.remaining(), result.stoppedBecause().orElse(null));
    }

    private static <T> T withBroker(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (BrokerUnavailableException exception) {
            throw Problems.exception(HttpStatus.SERVICE_UNAVAILABLE, "broker-unavailable", "Broker unavailable",
                    exception.getMessage());
        }
    }

    record ReplayRequest(@NotNull @Min(1) @Max(1000) Integer limit) {
    }

    record ParkingResponse(long messages) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReplayResponse(int replayed, long remaining, String stoppedBecause) {
    }
}
