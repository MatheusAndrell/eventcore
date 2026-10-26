alter table api_key drop constraint api_key_scopes_valid;
alter table api_key add constraint api_key_scopes_valid check (
    cardinality(scopes) > 0
    and scopes <@ array['events:write', 'events:read', 'subscriptions:manage', 'admin']::text[]);

create table subscription (
    id uuid primary key,
    client_id uuid not null references client (id),
    endpoint_url text not null,
    event_types text[] not null,
    status text not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint subscription_endpoint_url_https check (
        endpoint_url like 'https://%' and char_length(endpoint_url) <= 2048),
    constraint subscription_event_types_size check (cardinality(event_types) between 1 and 50),
    constraint subscription_status_valid check (status in ('ACTIVE', 'PAUSED', 'DELETED')),
    constraint subscription_deletion_consistent check ((status = 'DELETED') = (deleted_at is not null)),
    constraint subscription_updated_after_creation check (updated_at >= created_at)
);

create index subscription_client_idx on subscription (client_id, id) where status <> 'DELETED';

create table subscription_secret (
    id uuid primary key,
    subscription_id uuid not null references subscription (id),
    key_id text not null,
    nonce bytea not null,
    ciphertext bytea not null,
    created_at timestamptz not null,
    expires_at timestamptz,
    constraint subscription_secret_key_id_format check (key_id ~ '^[a-z0-9]{1,16}$'),
    constraint subscription_secret_nonce_length check (octet_length(nonce) = 12),
    constraint subscription_secret_ciphertext_length check (octet_length(ciphertext) = 48),
    constraint subscription_secret_expiry_after_creation check (expires_at is null or expires_at >= created_at)
);

create unique index subscription_secret_current_idx on subscription_secret (subscription_id)
    where expires_at is null;
create index subscription_secret_subscription_idx on subscription_secret (subscription_id);
