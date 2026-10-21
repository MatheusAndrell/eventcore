package com.eventcore.ingestion.application;

import com.eventcore.catalog.application.CatalogCheck;
import com.eventcore.catalog.application.EventCatalog;
import com.eventcore.ingestion.domain.Event;
import com.eventcore.ingestion.domain.EventId;
import com.eventcore.ingestion.domain.EventOccursInFutureException;
import com.eventcore.ingestion.domain.EventSubmission;
import com.eventcore.ingestion.domain.RequestFingerprint;
import com.eventcore.outbox.application.Outbox;
import com.eventcore.platform.identity.UuidV7Generator;
import com.eventcore.platform.json.CanonicalizationException;
import com.eventcore.platform.json.JsonCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;

@Service
public class ReceiveEvent {

    private static final byte[] FINGERPRINT_SCOPE = "receive-event\n".getBytes(StandardCharsets.UTF_8);

    private final EnvelopeReader envelopeReader;
    private final EventCatalog catalog;
    private final EventStore eventStore;
    private final Outbox outbox;
    private final JsonCanonicalizer canonicalizer;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final IngestionProperties properties;

    public ReceiveEvent(
            EnvelopeReader envelopeReader,
            EventCatalog catalog,
            EventStore eventStore,
            Outbox outbox,
            JsonCanonicalizer canonicalizer,
            UuidV7Generator ids,
            Clock clock,
            TransactionOperations transactions,
            IngestionProperties properties) {
        this.envelopeReader = envelopeReader;
        this.catalog = catalog;
        this.eventStore = eventStore;
        this.outbox = outbox;
        this.canonicalizer = canonicalizer;
        this.ids = ids;
        this.clock = clock;
        this.transactions = transactions;
        this.properties = properties;
    }

    public ReceiveEventResult receive(ReceiveEventCommand command) {
        try {
            RequestFingerprint fingerprint = fingerprint(command.body());
            Optional<ReceiveEventResult> previous = eventStore
                    .findIdempotencyRecord(command.clientId(), command.idempotencyKey())
                    .map(record -> resolveExisting(record, fingerprint));
            if (previous.isPresent()) {
                return previous.get();
            }
            return persist(command, fingerprint, admit(command));
        } catch (RejectedSubmission rejection) {
            return new ReceiveEventResult.Rejected(rejection.reason, rejection.violations);
        } catch (IdempotencyKeyLockedException exception) {
            return new ReceiveEventResult.ConcurrentRequestInProgress();
        }
    }

    private RequestFingerprint fingerprint(JsonNode body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(FINGERPRINT_SCOPE);
            return new RequestFingerprint(digest.digest(canonicalizer.canonicalize(body)));
        } catch (CanonicalizationException exception) {
            Rejection reason = switch (exception.reason()) {
                case UNREPRESENTABLE_NUMBER -> Rejection.NUMBER_NOT_REPRESENTABLE;
                case UNPAIRED_SURROGATE -> Rejection.INVALID_STRING;
            };
            throw new RejectedSubmission(reason, List.of(exception.getMessage()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private Event admit(ReceiveEventCommand command) {
        EventSubmission submission;
        try {
            submission = envelopeReader.read(command.body());
        } catch (MalformedEnvelopeException exception) {
            throw new RejectedSubmission(Rejection.MALFORMED_ENVELOPE, exception.violations());
        }
        requireCatalogAcceptance(submission, command.body().get("data"));
        Instant receivedAt = clock.instant();
        try {
            return Event.receive(new EventId(ids.next()), command.clientId(), submission, receivedAt,
                    properties.clockSkewTolerance());
        } catch (EventOccursInFutureException exception) {
            throw new RejectedSubmission(Rejection.OCCURRED_IN_FUTURE, List.of(exception.getMessage()));
        }
    }

    private void requireCatalogAcceptance(EventSubmission submission, JsonNode data) {
        CatalogCheck check = catalog.check(submission.type().value(), submission.dataVersion().value(), data);
        switch (check) {
            case CatalogCheck.Accepted _ -> {
            }
            case CatalogCheck.UnknownType _ -> throw new RejectedSubmission(Rejection.UNKNOWN_EVENT_TYPE,
                    List.of("type: '" + submission.type() + "' is not registered in the event catalog"));
            case CatalogCheck.UnsupportedVersion unsupported -> throw new RejectedSubmission(
                    Rejection.UNSUPPORTED_EVENT_VERSION,
                    List.of("dataversion: " + submission.dataVersion() + " is not accepted for '"
                            + submission.type() + "', accepted versions: " + unsupported.acceptedVersions()));
            case CatalogCheck.InvalidData invalid -> throw new RejectedSubmission(
                    Rejection.INVALID_EVENT_DATA, invalid.violations());
        }
    }

    private ReceiveEventResult persist(ReceiveEventCommand command, RequestFingerprint fingerprint, Event event) {
        IdempotencyRecord record = new IdempotencyRecord(command.clientId(), command.idempotencyKey(), fingerprint,
                event.id(), event.receivedAt(), event.receivedAt().plus(properties.idempotencyRetention()));
        return transactions.execute(status -> {
            if (!eventStore.registerIdempotencyKey(record, properties.idempotencyLockTimeout())) {
                return eventStore.findIdempotencyRecord(command.clientId(), command.idempotencyKey())
                        .map(existing -> resolveExisting(existing, fingerprint))
                        .orElseThrow(() -> new IllegalStateException(
                                "idempotency key conflicted but no record is visible for " + command.idempotencyKey()));
            }
            eventStore.insert(event);
            outbox.enqueue(EventAcceptedMessage.of(ids.next(), event));
            return new ReceiveEventResult.Accepted(EventReceipt.of(event));
        });
    }

    private ReceiveEventResult resolveExisting(IdempotencyRecord record, RequestFingerprint fingerprint) {
        if (!record.fingerprint().equals(fingerprint)) {
            return new ReceiveEventResult.IdempotencyKeyReused();
        }
        Event event = eventStore.findEvent(record.clientId(), record.eventId())
                .orElseThrow(() -> new IllegalStateException(
                        "idempotency record references missing event " + record.eventId()));
        return new ReceiveEventResult.Replayed(EventReceipt.of(event));
    }

    private static final class RejectedSubmission extends RuntimeException {

        private final Rejection reason;
        private final List<String> violations;

        private RejectedSubmission(Rejection reason, List<String> violations) {
            super(reason.name(), null, false, false);
            this.reason = reason;
            this.violations = List.copyOf(violations);
        }
    }
}
