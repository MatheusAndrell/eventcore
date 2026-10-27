create table delivery (
    id uuid primary key,
    event_id uuid not null references event (id),
    subscription_id uuid not null references subscription (id),
    client_id uuid not null references client (id),
    status text not null,
    attempts integer not null,
    claim_token uuid,
    claimed_until timestamptz,
    last_error text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz,
    constraint delivery_event_subscription_unique unique (event_id, subscription_id),
    constraint delivery_status_valid check (status in ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    constraint delivery_attempts_non_negative check (attempts >= 0),
    constraint delivery_claim_consistent check ((claim_token is null) = (claimed_until is null)),
    constraint delivery_completion_consistent check (
        (status = 'PENDING') = (completed_at is null) and (status = 'PENDING' or claim_token is null)),
    constraint delivery_last_error_length check (last_error is null or char_length(last_error) <= 512)
);

create index delivery_client_idx on delivery (client_id, created_at desc, id desc);
create index delivery_subscription_idx on delivery (subscription_id);

create table delivery_attempt (
    id uuid primary key,
    delivery_id uuid not null references delivery (id),
    attempt_number integer not null,
    started_at timestamptz not null,
    duration_ms bigint not null,
    succeeded boolean not null,
    http_status integer,
    error text,
    constraint delivery_attempt_number_unique unique (delivery_id, attempt_number),
    constraint delivery_attempt_number_positive check (attempt_number > 0),
    constraint delivery_attempt_duration_non_negative check (duration_ms >= 0),
    constraint delivery_attempt_http_status_range check (http_status is null or http_status between 100 and 599),
    constraint delivery_attempt_outcome_consistent check (
        not succeeded or (http_status between 200 and 299 and error is null)),
    constraint delivery_attempt_error_length check (error is null or char_length(error) <= 512)
);
