#!/usr/bin/env bash
# Writes the portal's current schema (the portal_* tables Hibernate created) to a Flyway migration, so the whole
# database can be owned by Flyway and Hibernate switched from `update` to `validate`.
#
# Why a script and not a hand-written migration: the schema must match what Hibernate expects column for column,
# and the authoritative source for that is the schema Hibernate itself produced.
#
#   1. Start the stack once so Hibernate creates the tables:   scripts/up.sh
#   2. Run this script:                                        scripts/export-portal-schema.sh
#   3. Review backend/src/main/resources/db/migration/V4__portal_schema.sql, then run with
#      PLATFORM_DDL_AUTO=validate (an existing database needs `flyway baseline` at V4 first).
set -euo pipefail
cd "$(dirname "$0")/.."
ENV_FILE=${PLATFORM_ENV_FILE:-$HOME/.config/insurai-platform/compose.env}
[[ -f $ENV_FILE ]] && { set -a; source "$ENV_FILE"; set +a; }
OUT=${OUT:-backend/src/main/resources/db/migration/V4__portal_schema.sql}
if [[ -e $OUT ]]; then echo "$OUT already exists; remove it first if you really want to regenerate it" >&2; exit 1; fi

tables=$(docker compose ${ENV_FILE:+--env-file "$ENV_FILE"} exec -T postgres \
  psql -U insurai -d insurai -At -c "select tablename from pg_tables where schemaname='public' and tablename like 'portal\_%' order by 1")
[[ -n $tables ]] || { echo "no portal_* tables found: has the backend started at least once?" >&2; exit 1; }
args=(); for t in $tables; do args+=(-t "public.$t"); done

{
  echo "-- Portal schema, exported from the tables Hibernate created (scripts/export-portal-schema.sh)."
  docker compose ${ENV_FILE:+--env-file "$ENV_FILE"} exec -T postgres \
    pg_dump -U insurai -d insurai --schema-only --no-owner --no-privileges "${args[@]}" \
    | grep -v '^\(SET\|SELECT pg_catalog\)' | grep -v '^--$'
} > "$OUT"
echo "wrote $OUT ($(grep -c 'CREATE TABLE' "$OUT") tables)"
