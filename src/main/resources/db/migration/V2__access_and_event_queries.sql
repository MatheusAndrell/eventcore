create table api_key (
    id uuid primary key,
    client_id uuid not null references client (id),
    key_id text not null,
    secret_hash bytea not null,
    pepper_id text not null,
    scopes text[] not null,
    status text not null,
    created_at timestamptz not null,
    expires_at timestamptz,
    revoked_at timestamptz,
    last_used_at timestamptz,
    constraint api_key_key_id_unique unique (key_id),
    constraint api_key_key_id_format check (key_id ~ '^[a-z2-7]{16}$'),
    constraint api_key_secret_hash_length check (octet_length(secret_hash) = 32),
    constraint api_key_pepper_id_format check (pepper_id ~ '^[a-z0-9]{1,16}$'),
    constraint api_key_scopes_valid check (
        cardinality(scopes) > 0 and scopes <@ array['events:write', 'events:read', 'admin']::text[]),
    constraint api_key_status_valid check (status in ('ACTIVE', 'REVOKED')),
    constraint api_key_revocation_consistent check ((status = 'REVOKED') = (revoked_at is not null)),
    constraint api_key_expiry_after_creation check (expires_at is null or expires_at > created_at)
);

create index api_key_client_idx on api_key (client_id);

alter table event add column correlation_id text not null;
alter table event add constraint event_correlation_id_format
    check (correlation_id ~ '^[A-Za-z0-9._:-]{1,64}$');

create index event_client_received_idx on event (client_id, received_at desc, id desc);
