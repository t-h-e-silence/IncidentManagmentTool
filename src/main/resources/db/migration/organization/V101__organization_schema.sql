-- organization: users, teams, memberships and category routing.
-- Users, teams and categories are referenced by other modules, so they are deactivated or archived, never deleted.

create table organization.app_user
(
    id             uuid primary key,
    name           varchar(200) not null,
    email          varchar(320) not null unique,
    system_role    varchar(20)  not null check (system_role in ('USER', 'ADMIN', 'SYSTEM')),
    active         boolean      not null default true,
    deactivated_at timestamptz,
    version        bigint       not null default 0,
    constraint app_user_email_lower check (email = lower(email))
);

create table organization.team
(
    id          uuid primary key,
    name        varchar(200) not null unique,
    archived_at timestamptz,
    version     bigint       not null default 0
);

create table organization.team_membership
(
    team_id uuid        not null references organization.team (id),
    user_id uuid        not null references organization.app_user (id),
    role    varchar(20) not null check (role in ('RESPONDER', 'TEAM_LEAD')),
    primary key (team_id, user_id)
);

create index team_membership_user_idx on organization.team_membership (user_id);

create table organization.category
(
    id      uuid primary key,
    name    varchar(100) not null unique,
    team_id uuid         not null references organization.team (id),
    active  boolean      not null default true,
    version bigint       not null default 0
);

-- The system user: author of automatic actions. Needed in every environment, cannot act through the controller.
insert into organization.app_user (id, name, email, system_role)
values ('00000000-0000-0000-0000-000000000001', 'System', 'system@ims.invalid', 'SYSTEM');
