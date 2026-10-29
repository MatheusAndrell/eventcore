alter table event drop constraint event_status_valid;
alter table event add column dispatched_at timestamptz, add column dispatch_requeued_at timestamptz;
alter table event
    add constraint event_status_valid check (status in ('RECEIVED', 'DISPATCHED')),
    add constraint event_dispatch_consistent check ((status = 'DISPATCHED') = (dispatched_at is not null));

create index event_undispatched_idx on event (received_at, id) where status = 'RECEIVED';

alter table delivery add column next_attempt_at timestamptz, add column max_attempts integer;
update delivery set next_attempt_at = created_at where status = 'PENDING';
update delivery set max_attempts = greatest(attempts, 1);
alter table delivery alter column max_attempts set not null;
alter table delivery
    add constraint delivery_schedule_consistent check ((status = 'PENDING') = (next_attempt_at is not null)),
    add constraint delivery_attempts_within_limit check (max_attempts > 0 and attempts <= max_attempts);

create index delivery_pending_idx on delivery (next_attempt_at, id) where status = 'PENDING';
