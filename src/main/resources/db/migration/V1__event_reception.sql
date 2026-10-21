create table client (
    id uuid primary key,
    name text not null,
    status text not null,
    created_at timestamptz not null,
    constraint client_name_unique unique (name),
    constraint client_name_length check (char_length(name) between 1 and 128),
    constraint client_status_valid check (status in ('ACTIVE', 'DISABLED'))
);

create table event (
    id uuid primary key,
    client_id uuid not null references client (id),
    type text not null,
    data_version integer not null,
    source text not null,
    subject text,
    occurred_at timestamptz not null,
    received_at timestamptz not null,
    data jsonb not null,
    status text not null,
    constraint event_type_length check (char_length(type) between 1 and 128),
    constraint event_data_version_positive check (data_version > 0),
    constraint event_source_length check (char_length(source) between 1 and 256),
    constraint event_subject_length check (subject is null or char_length(subject) between 1 and 256),
    constraint event_data_object check (jsonb_typeof(data) = 'object'),
    constraint event_status_valid check (status in ('RECEIVED'))
);

create table idempotency_record (
    client_id uuid not null references client (id),
    idempotency_key text not null,
    request_fingerprint bytea not null,
    event_id uuid not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    primary key (client_id, idempotency_key),
    constraint idempotency_record_event_fk foreign key (event_id) references event (id)
        deferrable initially deferred,
    constraint idempotency_record_key_format check (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    constraint idempotency_record_fingerprint_length check (octet_length(request_fingerprint) = 32),
    constraint idempotency_record_expiry_after_creation check (expires_at > created_at)
);

create table outbox_message (
    id uuid primary key,
    aggregate_type text not null,
    aggregate_id uuid not null,
    exchange text not null,
    routing_key text not null,
    message_type text not null,
    message_version integer not null,
    payload jsonb not null,
    status text not null,
    available_at timestamptz not null,
    created_at timestamptz not null,
    constraint outbox_message_version_positive check (message_version > 0),
    constraint outbox_message_payload_object check (jsonb_typeof(payload) = 'object'),
    constraint outbox_message_status_valid check (status in ('PENDING'))
);
