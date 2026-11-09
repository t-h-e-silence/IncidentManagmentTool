-- One database and one user per service (ADR D5). A user can connect only to its own database.
-- Runs once, when the Postgres volume is empty. Passwords are for local development only.

create user organization with password 'organization';
create database organization owner organization;

create user incidents with password 'incidents';
create database incidents owner incidents;

create user audit with password 'audit';
create database audit owner audit;

create user notifications with password 'notifications';
create database notifications owner notifications;

create user escalations with password 'escalations';
create database escalations owner escalations;

-- By default every user may connect to every database; keep each service in its own.
revoke connect on database organization, incidents, audit, notifications, escalations from public;
grant connect on database organization to organization;
grant connect on database incidents to incidents;
grant connect on database audit to audit;
grant connect on database notifications to notifications;
grant connect on database escalations to escalations;
