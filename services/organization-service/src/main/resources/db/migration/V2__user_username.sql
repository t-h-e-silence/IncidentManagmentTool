-- organization: short, unique login name of a user (e.g. "bob"); the HTTP API accepts it instead of the user id.

alter table organization.app_user
    add column username varchar(50) unique,
    add constraint app_user_username_lower check (username = lower(username));

update organization.app_user
set username = 'system'
where id = '00000000-0000-0000-0000-000000000001';
