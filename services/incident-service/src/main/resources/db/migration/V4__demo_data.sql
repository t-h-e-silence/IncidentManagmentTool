-- Demo data, applied like every other migration. Fixed ids, so Swagger and Postman can use them directly:
--   incidents 40000000-...-00N    comments 50000000-...-00N
-- Users, teams and categories are organization-service's demo data (same ids); audit-service has the matching
-- history. These rows publish no events, so they send no emails.

insert into incidents.incident (id, title, description, category_id, category_name, team_id, reporter_id, severity,
                                status, created_at, updated_at, acknowledged_at, resolved_at, resolved_by,
                                resolution_note, closed_at)
values
    -- Bob reported it; the Database team (Alice, Dan) has not acknowledged it yet.
    ('40000000-0000-0000-0000-000000000001', 'Checkout is slow', 'Pages take 10 s to load',
     '30000000-0000-0000-0000-000000000003', 'Database', '10000000-0000-0000-0000-000000000002',
     '20000000-0000-0000-0000-000000000003', 'SEV2', 'OPEN',
     '2026-10-02T09:00:00Z', '2026-10-02T09:05:00Z', null, null, null, null, null),
    -- Bob reported it; Erin (Network) is working on it.
    ('40000000-0000-0000-0000-000000000002', 'VPN drops every hour', 'Connection is lost every 60 minutes',
     '30000000-0000-0000-0000-000000000004', 'VPN', '10000000-0000-0000-0000-000000000003',
     '20000000-0000-0000-0000-000000000003', 'SEV3', 'IN_PROGRESS',
     '2026-10-01T08:00:00Z', '2026-10-01T08:30:00Z', '2026-10-01T08:30:00Z', null, null, null, null),
    -- Alice reported it; Carol (Platform) worked it through review to RESOLVED; it can be CLOSED or reopened.
    ('40000000-0000-0000-0000-000000000003', 'Payment page shows error 500', '',
     '30000000-0000-0000-0000-000000000001', 'Payments', '10000000-0000-0000-0000-000000000001',
     '20000000-0000-0000-0000-000000000002', 'SEV1', 'RESOLVED',
     '2026-09-30T14:00:00Z', '2026-09-30T15:00:00Z', '2026-09-30T14:10:00Z', '2026-09-30T15:00:00Z',
     '20000000-0000-0000-0000-000000000004', 'Rolled back release 4.2', null)
on conflict (id) do nothing;

insert into incidents.comment (id, incident_id, author_id, text, created_at)
values ('50000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
        '20000000-0000-0000-0000-000000000003', 'Also affects the mobile app', '2026-10-02T09:05:00Z')
on conflict (id) do nothing;
