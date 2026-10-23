#!/usr/bin/env bash
# Loads the demo data (users, teams, categories) into the local database. This is the only way to seed it.
# The tables must exist: start the app once (plain `mvn spring-boot:run`) so Flyway creates them.
# Re-runnable: existing rows are left unchanged.
#
#   ./src/main/resources/db/scripts/seed.sh
set -euo pipefail

# This script lives in src/main/resources/db/scripts; work from the project root (docker-compose.yml).
cd "$(dirname "$0")/../../../../.."

docker compose exec -T postgres psql -U incidents -d incidents -v ON_ERROR_STOP=1 -q \
  < src/main/resources/db/scripts/seed.sql

echo "Seed data loaded."
