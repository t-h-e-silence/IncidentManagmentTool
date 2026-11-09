#!/usr/bin/env sh
# Writes each running service's OpenAPI document to <service>/openapi.yaml, the copy kept next to the code.
# Run after changing an endpoint: docker compose up -d --build, then ./export-openapi.sh
set -e
cd "$(dirname "$0")"
for entry in organization-service:8081 incident-service:8082 audit-service:8083; do
  service=${entry%%:*}
  port=${entry##*:}
  curl -sf "http://localhost:$port/v3/api-docs.yaml" -o "$service/openapi.yaml"
  echo "$service/openapi.yaml"
done
