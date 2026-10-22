alter table outbox_message
    add column publish_attempts integer not null default 0,
    add column claim_token uuid,
    add column claimed_until timestamptz,
    add column last_error text,
    add column published_at timestamptz;

alter table outbox_message drop constraint outbox_message_status_valid;

alter table outbox_message
    add constraint outbox_message_status_valid check (status in ('PENDING', 'PUBLISHED')),
    add constraint outbox_message_publish_attempts_non_negative check (publish_attempts >= 0),
    add constraint outbox_message_claim_consistent check ((claim_token is null) = (claimed_until is null)),
    add constraint outbox_message_published_consistent check (
        (status = 'PUBLISHED') = (published_at is not null)
        and (status = 'PENDING' or claim_token is null)),
    add constraint outbox_message_last_error_length check (last_error is null or char_length(last_error) <= 512);

create index outbox_message_pending_idx on outbox_message (available_at, id) where status = 'PENDING';
