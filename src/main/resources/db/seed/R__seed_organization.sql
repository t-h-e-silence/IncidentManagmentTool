-- Demo/test data, loaded only with the `seed` profile. Fixed ids, so they can be used as actor ids.
--   users      20000000-...-00N    teams      10000000-...-00N    categories 30000000-...-00N
-- Re-runnable: existing rows are left unchanged.

insert into organization.app_user (id, name, email, system_role)
values ('20000000-0000-0000-0000-000000000001', 'Ada Admin', 'ada.admin@example.com', 'ADMIN'),
       ('20000000-0000-0000-0000-000000000002', 'Alice Lead', 'alice@example.com', 'USER'),
       ('20000000-0000-0000-0000-000000000003', 'Bob Reporter', 'bob@example.com', 'USER'),
       ('20000000-0000-0000-0000-000000000004', 'Carol Platform', 'carol@example.com', 'USER'),
       ('20000000-0000-0000-0000-000000000005', 'Dan Dba', 'dan@example.com', 'USER'),
       ('20000000-0000-0000-0000-000000000006', 'Erin Network', 'erin@example.com', 'USER')
on conflict (id) do nothing;

insert into organization.team (id, name)
values ('10000000-0000-0000-0000-000000000001', 'Platform'),
       ('10000000-0000-0000-0000-000000000002', 'Database'),
       ('10000000-0000-0000-0000-000000000003', 'Network')
on conflict (id) do nothing;

-- Alice leads Database and is a responder in Network; Bob has no team, so he can only report.
insert into organization.team_membership (team_id, user_id, role)
values ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000004', 'TEAM_LEAD'),
       ('10000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002', 'TEAM_LEAD'),
       ('10000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000005', 'RESPONDER'),
       ('10000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000006', 'TEAM_LEAD'),
       ('10000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000002', 'RESPONDER')
on conflict (team_id, user_id) do nothing;

insert into organization.category (id, name, team_id)
values ('30000000-0000-0000-0000-000000000001', 'Payments', '10000000-0000-0000-0000-000000000001'),
       ('30000000-0000-0000-0000-000000000002', 'Web application', '10000000-0000-0000-0000-000000000001'),
       ('30000000-0000-0000-0000-000000000003', 'Database', '10000000-0000-0000-0000-000000000002'),
       ('30000000-0000-0000-0000-000000000004', 'VPN', '10000000-0000-0000-0000-000000000003'),
       ('30000000-0000-0000-0000-000000000005', 'Network', '10000000-0000-0000-0000-000000000003')
on conflict (id) do nothing;
