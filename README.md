# EventCore

EventCore receives business events over HTTP, validates them against a versioned catalog, stores them durably and
delivers them to subscribers as signed webhooks. It is a single Spring Boot application that runs in three roles
(`api`, `relay`, `worker`) backed by PostgreSQL and RabbitMQ.

## Guarantees

| Concern | Behaviour |
|---|---|
| Intake | An event is accepted only after it is stored together with its outbox message in one transaction. |
| Idempotency | `Idempotency-Key` is scoped to the client and bound to the RFC 8785 canonical form of the body. A repeated request returns the original receipt; the same key with a different body returns `409`. |
| Publication | A transactional outbox publishes to RabbitMQ with publisher confirms, mandatory routing and leases, so a crashed relay never loses or blocks messages. |
| Delivery | At least once. Receivers must deduplicate on `webhook-id`, which is the event id and stays the same across retries and redeliveries. |
| Ordering | Not guaranteed, neither between events nor between retries. |
| Failure | Failed deliveries are retried with capped exponential backoff and jitter, honouring `Retry-After`. Deliveries that run out of attempts end as `FAILED` and can be retried through the API. Messages that cannot be processed are parked in a dead-letter queue and can be replayed. |

## Architecture

```
client ──HTTP──▶ api ──tx──▶ PostgreSQL (event + outbox)
                                  │
                       relay ◀────┘ claims outbox rows, publishes with confirms
                         │
                         ▼
                     RabbitMQ  eventcore.events ──▶ eventcore.events.accepted ──▶ worker: dispatch
                                                ──▶ eventcore.deliveries      ──▶ worker: webhook delivery ──HTTPS──▶ receiver
                               eventcore.dlx    ──▶ eventcore.parking
```

- **api** serves the public API on port 8080. Its readiness depends only on PostgreSQL.
- **relay** moves outbox rows to RabbitMQ.
- **worker** dispatches accepted events to matching subscriptions, delivers webhooks, reconciles stalled work and
  applies data retention.

Every role exposes health and metrics on the management port 8081. Roles are selected with `EVENTCORE_ROLES` and can
be combined in one process.

The code is a modular monolith. Each context (`ingestion`, `catalog`, `access`, `subscription`, `delivery`, `outbox`,
`operations`) has `domain`, `application`, `infrastructure` and `web` packages, and ArchUnit tests enforce the
boundaries: domains are framework free, contexts only use each other's `application` package, and there are no cycles.

## Running locally

Requirements: Java 25, Docker.

1. Create `.env` from `.env.example` and fill in the secrets:

   ```sh
   cp .env.example .env
   openssl rand -base64 32   # API key pepper:            EVENTCORE_API_KEY_PEPPERS=p1:<value>, EVENTCORE_ACTIVE_API_KEY_PEPPER=p1
   openssl rand -base64 32   # webhook secret encryption: EVENTCORE_WEBHOOK_SECRET_KEYS=k1:<value>, EVENTCORE_ACTIVE_WEBHOOK_SECRET_KEY=k1
   ```

   `EVENTCORE_BOOTSTRAP_ADMIN_KEY` must have the API key format `ec_<16 base32 chars>_<43 base64url chars>`. It is
   stored on first start-up when no usable admin key exists.

2. Start the infrastructure and run the application from source:

   ```sh
   docker compose up -d
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```

   Or build the image and run every role in its own container:

   ```sh
   docker compose --profile app up -d --build
   ```

## Using the API

The OpenAPI document is served at `/v3/api-docs` to any authenticated caller. Requests authenticate with
`Authorization: Bearer <api key>`. Errors use RFC 9457 problem details with a `correlationId`.

| Method and path | Scope | Purpose |
|---|---|---|
| `POST /admin/clients` | `admin` | Create a client |
| `PUT /admin/clients/{id}/status` | `admin` | Enable or disable a client |
| `POST /admin/clients/{id}/api-keys` | `admin` | Issue an API key; the secret is shown once |
| `GET /admin/clients/{id}/api-keys`, `DELETE .../{keyId}` | `admin` | List or revoke keys |
| `GET /admin/parking`, `POST /admin/parking/replay` | `admin` | Inspect or replay parked messages |
| `POST /v1/events` | `events:write` | Submit an event; requires `Idempotency-Key` |
| `GET /v1/events`, `GET /v1/events/{id}` | `events:read` | Query events with cursor pagination |
| `GET /v1/events/{id}/deliveries`, `GET /v1/deliveries/{id}` | `events:read` | Delivery status and attempt history |
| `POST /v1/deliveries/{id}/retry` | `subscriptions:manage` | Retry a `FAILED` or `CANCELLED` delivery with a fresh attempt budget |
| `POST /v1/subscriptions` | `subscriptions:manage` | Create a subscription; the signing secret is shown once |
| `GET`, `PATCH`, `DELETE /v1/subscriptions/{id}` | `subscriptions:manage` | Read, change or delete a subscription |
| `POST /v1/subscriptions/{id}/rotate-secret` | `subscriptions:manage` | Issue a new secret; the previous one stays valid for 24 hours |

Submitting an event:

```sh
curl -X POST http://localhost:8080/v1/events \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: payment-p-1" \
  -d '{"type":"payment.captured","dataversion":"1","time":"2026-10-20T12:00:00Z",
       "source":"urn:acme:payments","data":{"paymentId":"p-1","orderId":"o-1","amount":"15.00","currency":"BRL"}}'
```

The event catalog lives in `src/main/resources/eventcore/catalog`. Each event type has versioned JSON Schemas with a
status of `ACTIVE`, `DEPRECATED` (still accepted) or `RETIRED` (rejected). `src/test/resources/catalog.lock` pins the
schema digests, so a published schema cannot change without the build failing.

## Receiving webhooks

Deliveries are `POST` requests with a canonical JSON body (`id`, `type`, `dataversion`, `source`, `subject`, `time`,
`data`) and the [Standard Webhooks](https://www.standardwebhooks.com/) headers:

- `webhook-id`: the event id, stable across retries.
- `webhook-timestamp`: Unix seconds of the attempt.
- `webhook-signature`: space-separated `v1,<base64 HMAC-SHA256>` values, one per valid secret.
- `eventcore-delivery-id`: the delivery id, to look up attempts through the API.

To verify a request, compute `HMAC-SHA256(base64decode(secret without "whsec_"), webhook-id + "." + webhook-timestamp +
"." + raw body)` and accept it if any signature matches. Reject timestamps that are too far from your clock, and
deduplicate on `webhook-id`. Any Standard Webhooks library can do this. A `2xx` response marks the delivery as
succeeded; redirects are not followed.

## Security

- **API keys** are stored as HMAC-SHA256 hashes with a rotatable pepper and compared in constant time. All failures
  return the same `401`, and each client sees only its own data.
- **Webhook signing secrets** are encrypted at rest with AES-256-GCM, bound to their row id and key id. The encryption
  keys can be rotated.
- **SSRF protection.** Webhook endpoints must be `https` on an allowed port (`443` by default) and must resolve only to
  public addresses. This blocks private, loopback, link-local and metadata ranges, reserved ranges, NAT64 and 6to4
  forms of internal addresses, and DNS rebinding. The check runs when a subscription is registered and again on every
  delivery: the HTTP client resolves names through the same policy, so it only connects to validated addresses. The
  client does not follow redirects, applies connect, read and total deadlines, and never reads the response body.
- **Request bodies** are limited to 256 KB and must be UTF-8 JSON.
- **Management port.** The management port (8081) exposes `health` and `prometheus` without authentication and must
  stay on an internal network.

## Configuration

| Variable | Purpose |
|---|---|
| `EVENTCORE_ROLES` | Comma-separated roles: `api`, `relay`, `worker` |
| `EVENTCORE_API_KEY_PEPPERS`, `EVENTCORE_ACTIVE_API_KEY_PEPPER` | `id:base64` peppers (32 bytes or more) and the one used for new keys |
| `EVENTCORE_BOOTSTRAP_ADMIN_KEY` | Optional admin key created on start-up |
| `EVENTCORE_WEBHOOK_SECRET_KEYS`, `EVENTCORE_ACTIVE_WEBHOOK_SECRET_KEY` | `id:base64` AES-256 keys (32 bytes) and the one used for new secrets; required by `api` and `worker` |
| `EVENTCORE_TRACING_SAMPLING_PROBABILITY` | Trace sampling, default `0.1` |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | OTLP endpoint for traces, for example `http://collector:4318/v1/traces` |
| `EVENTCORE_OTLP_METRICS_ENABLED`, `MANAGEMENT_OTLP_METRICS_EXPORT_URL` | Optional OTLP metrics export |
| `SPRING_DATASOURCE_*`, `SPRING_RABBITMQ_*` | Database and broker connection |

Further tuning is available under `eventcore.*`:

| Prefix | Settings |
|---|---|
| `eventcore.ingestion` | Idempotency retention and lock timeout, clock skew tolerance |
| `eventcore.outbox` | Batch size, poll interval, lease, confirm timeout, retry delays |
| `eventcore.delivery` | Timeouts, lease, connection pool, max attempts, retry delays, `ssl-bundle` for private CAs |
| `eventcore.reconciler` | Interval, staleness threshold, batch size |
| `eventcore.retention` | Event and published-message retention, batch size |
| `eventcore.subscriptions` | Per-client limit, secret rotation grace period |
| `eventcore.egress` | Allowed destination ports |

## Operations

- **Probes**: `/actuator/health/liveness` and `/actuator/health/readiness` on the management port.
- **Metrics** at `/actuator/prometheus`:
  - `eventcore_events_received_total{outcome,reason}`
  - `eventcore_outbox_published_total{outcome}`, `eventcore_outbox_pending`, `eventcore_outbox_oldest_pending_age_seconds`
  - `eventcore_deliveries_outcomes_total{outcome}`, `eventcore_deliveries_pending`, `eventcore_deliveries_due`
  - `eventcore_webhook_request_seconds{outcome}`
  - `eventcore_reconciler_repairs_total{kind}`
  - `eventcore_retention_deleted_total{kind}`
  - `eventcore_parking_messages`
- **Tracing**: W3C trace context flows from the HTTP request through the outbox and both queues to the webhook
  delivery span. Trace headers are never sent to webhook receivers.
- **Logs**: the `prod` profile writes ECS JSON logs. `X-Correlation-Id` is accepted or generated, returned to the
  caller and kept with the event, so consumer logs carry the same id.
- **Reconciliation** (worker, every minute):
  - re-dispatches events still undispatched after five minutes;
  - re-enqueues deliveries whose lease expired without a result;
  - fails deliveries that ran out of attempts without a recorded result.
- **Parked messages**: listeners retry infrastructure failures with backoff before parking a message. Messages that
  cannot be processed are parked immediately. `POST /admin/parking/replay` moves parked messages back to the queue
  they came from.
- **Retention** (worker, hourly):
  - expired idempotency records;
  - completed deliveries and dispatched events older than 30 days;
  - published outbox messages older than 7 days.

  Pending work is never removed.

## Development

```sh
./mvnw verify
```

`verify` runs the unit tests and the integration tests. The integration tests use Testcontainers (PostgreSQL,
RabbitMQ, Toxiproxy) and a local HTTPS receiver, so Docker must be running. They cover:

- idempotency races;
- broker and database outages;
- relay and worker crashes;
- redirects, slow and trickling receivers;
- SSRF attempts;
- secret rotation;
- retry exhaustion and manual retry;
- parking replay;
- trace propagation and retention.

Compiler warnings fail the build. The CI workflow runs `verify` and builds the container image.
