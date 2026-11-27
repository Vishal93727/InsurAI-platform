#!/usr/bin/env bash
# Starts the full stack (database, Redis, Kafka, object storage, Mailpit, backend, frontend) with secrets
# generated on first use and kept outside the repository (mode 600). Reruns reuse them, so databases in
# existing volumes keep accepting their credentials.
#
#   scripts/up.sh                  # start / update
#   MONITORING=1 scripts/up.sh     # also start Prometheus, Tempo and Grafana
#   scripts/up.sh --force-recreate # extra arguments go to `docker compose up`
set -euo pipefail
cd "$(dirname "$0")/.."
ENV_FILE=${PLATFORM_ENV_FILE:-$HOME/.config/insurai-platform/compose.env}
if [[ ! -f $ENV_FILE ]]; then
  mkdir -p "$(dirname "$ENV_FILE")"
  umask 077
  admin_password=$(openssl rand -hex 12)
  {
    echo "PLATFORM_DB_PASSWORD=$(openssl rand -hex 24)"
    echo "PLATFORM_S3_ACCESS_KEY=ip$(openssl rand -hex 8)"
    echo "PLATFORM_S3_SECRET_KEY=$(openssl rand -hex 24)"
    echo "PLATFORM_JWT_SECRET=$(openssl rand -hex 32)"
    echo "PLATFORM_PARTY_HMAC_KEY=$(openssl rand -hex 32)"
    echo "FRAUD_ADMIN_TOKEN=$(openssl rand -hex 24)"
    echo "PLATFORM_ADMIN_EMAIL=admin@insurai.local"
    echo "PLATFORM_ADMIN_PASSWORD=$admin_password"
  } > "$ENV_FILE"
  echo "wrote new secrets to $ENV_FILE"
  echo "portal admin login: admin@insurai.local / $admin_password"
fi
files=(-f docker-compose.yml)
[[ ${MONITORING:-0} == 1 ]] && files+=(-f docker-compose.monitoring.yml)
# Rebuild images on every run (layer caching keeps that cheap) unless the caller passes --no-build.
build=(--build)
for arg in "$@"; do [[ $arg == --no-build ]] && build=(); done
exec docker compose "${files[@]}" --env-file "$ENV_FILE" up -d ${build[@]+"${build[@]}"} --wait --wait-timeout "${WAIT:-300}" "$@"
