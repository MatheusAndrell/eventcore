alter table outbox_message add column trace_context jsonb;
alter table outbox_message add constraint outbox_message_trace_context_object
    check (trace_context is null or jsonb_typeof(trace_context) = 'object');

create index outbox_message_published_idx on outbox_message (published_at) where status = 'PUBLISHED';
create index event_dispatched_received_idx on event (received_at) where status = 'DISPATCHED';
create index delivery_completed_idx on delivery (completed_at) where status <> 'PENDING';
create index idempotency_record_expiry_idx on idempotency_record (expires_at);
