#!/usr/bin/env bash
# Look at the local database (docker-compose postgres) from the shell. No local psql or Spring profile needed.
#
#   ./src/main/resources/db/scripts/db.sh                        interactive psql
#   ./src/main/resources/db/scripts/db.sh tables                 list all tables with row counts
#   ./src/main/resources/db/scripts/db.sh incidents.incident     show rows of one table (first 50; pass a number to change)
#   ./src/main/resources/db/scripts/db.sh "select ... ;"         run any SQL
set -euo pipefail

# This script lives in src/main/resources/db/scripts; work from the project root (docker-compose.yml).
cd "$(dirname "$0")/../../../../.."

SCHEMAS="'organization','incidents','audit','notifications','escalations'"

psql() {
  docker compose exec -T postgres psql -U incidents -d incidents -P pager=off "$@"
}

case "${1:-}" in
  "")
    exec docker compose exec postgres env PGOPTIONS="-c search_path=organization,incidents,audit,notifications,escalations" \
      psql -U incidents -d incidents
    ;;
  tables)
    psql -c "select table_schema as schema, table_name as table,
               (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name),
                                                    false, true, '')))[1]::text::int as rows
             from information_schema.tables
             where table_schema in ($SCHEMAS) and table_name <> 'flyway_schema_history'
             order by 1, 2"
    ;;
  *.*)
    if [[ "$1" =~ ^[a-z_]+\.[a-z_]+$ ]]; then
      psql -x -c "select * from $1 limit ${2:-50}"
    else
      psql -c "$1"
    fi
    ;;
  *)
    psql -c "$1"
    ;;
esac
