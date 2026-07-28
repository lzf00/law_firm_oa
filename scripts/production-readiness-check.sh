#!/usr/bin/env bash
set -euo pipefail

project_network="${OA_DOCKER_NETWORK:-law_firm_oa_default}"
backend_image="${OA_BACKEND_IMAGE:-law-firm-oa-backend:readiness}"
check_suffix="$$"
database_container="law-oa-readiness-db-${check_suffix}"
backend_container="law-oa-readiness-app-${check_suffix}"
invalid_container="law-oa-readiness-invalid-${check_suffix}"
database_name="law_oa_readiness"
database_user="law_oa_readiness"
database_password="readiness-database-secret"
invalid_log="$(mktemp -t law-oa-invalid-config.XXXXXX)"

cleanup() {
  docker rm -f \
    "${invalid_container}" \
    "${backend_container}" \
    "${database_container}" >/dev/null 2>&1 || true
  rm -f "${invalid_log}"
}
trap cleanup EXIT

docker network inspect "${project_network}" >/dev/null
docker image inspect "${backend_image}" >/dev/null

docker run -d \
  --name "${database_container}" \
  --network "${project_network}" \
  --network-alias "${database_container}" \
  -e POSTGRES_DB="${database_name}" \
  -e POSTGRES_USER="${database_user}" \
  -e POSTGRES_PASSWORD="${database_password}" \
  postgres:16-alpine >/dev/null

for _ in $(seq 1 30); do
  if docker exec "${database_container}" \
      pg_isready -U "${database_user}" -d "${database_name}" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "${database_container}" \
  pg_isready -U "${database_user}" -d "${database_name}" >/dev/null

common_environment=(
  -e SPRING_PROFILES_ACTIVE=prod
  -e DB_URL="jdbc:postgresql://${database_container}:5432/${database_name}"
  -e DB_USERNAME="${database_user}"
  -e DB_PASSWORD="${database_password}"
  -e REDIS_HOST=redis
  -e REDIS_PORT=6379
  -e STORAGE_ENDPOINT=http://minio:9000
  -e STORAGE_PUBLIC_ENDPOINT=https://files.launch-check.test
  -e STORAGE_ACCESS_KEY=law_oa_local
  -e STORAGE_SECRET_KEY=law_oa_local_password
  -e STORAGE_BUCKET=law-oa-private
  -e DINGTALK_CLIENT_ID=launch-check-client
  -e DINGTALK_CLIENT_SECRET=launch-check-secret
  -e DINGTALK_REDIRECT_URI=https://oa.launch-check.test/auth/dingtalk/callback
)

docker run -d \
  --name "${backend_container}" \
  --network "${project_network}" \
  --read-only \
  --tmpfs /tmp:size=256m,noexec,nosuid \
  --security-opt no-new-privileges:true \
  "${common_environment[@]}" \
  "${backend_image}" >/dev/null

ready="false"
for _ in $(seq 1 60); do
  if docker run --rm --network "${project_network}" curlimages/curl:8.16.0 \
      -fsS "http://${backend_container}:8080/actuator/health/readiness" >/dev/null 2>&1; then
    ready="true"
    break
  fi
  if ! docker inspect -f '{{.State.Running}}' "${backend_container}" | grep -q true; then
    docker logs "${backend_container}"
    exit 1
  fi
  sleep 1
done
test "${ready}" = "true"

migration_summary="$(docker exec "${database_container}" \
  psql -U "${database_user}" -d "${database_name}" -Atc \
  "SELECT count(*) || ':' || max(version) FROM flyway_schema_history WHERE success")"
test "${migration_summary}" = "9:9"

seed_counts="$(docker exec "${database_container}" \
  psql -U "${database_user}" -d "${database_name}" -Atc \
  "SELECT (SELECT count(*) FROM organizations) || ':' || (SELECT count(*) FROM users)")"
test "${seed_counts}" = "0:0"

dev_status="$(docker run --rm --network "${project_network}" curlimages/curl:8.16.0 \
  -sS -o /dev/null -w '%{http_code}' \
  -H 'X-Dev-User: admin' "http://${backend_container}:8080/api/me")"
case "${dev_status}" in
  401|403) ;;
  *)
    echo "Production unexpectedly accepted X-Dev-User; HTTP ${dev_status}" >&2
    exit 1
    ;;
esac

auth_config="$(docker run --rm --network "${project_network}" curlimages/curl:8.16.0 \
  -fsS "http://${backend_container}:8080/api/auth/config")"
printf '%s' "${auth_config}" | grep -q '"authMode":"dingtalk"'
printf '%s' "${auth_config}" | grep -q '"dingtalkConfigured":true'

set +e
docker run --name "${invalid_container}" \
  --network "${project_network}" \
  "${common_environment[@]}" \
  -e DINGTALK_CLIENT_ID=replace_with_dingtalk_app_key \
  "${backend_image}" >"${invalid_log}" 2>&1
invalid_status=$?
set -e
test "${invalid_status}" -ne 0
grep -q 'Production configuration invalid' "${invalid_log}"
if grep -q 'launch-check-secret' "${invalid_log}"; then
  echo "A secret value was written to the invalid-configuration log" >&2
  exit 1
fi

echo "Production readiness check passed:"
echo "- clean Flyway migrations: ${migration_summary}"
echo "- production seed counts (organizations:users): ${seed_counts}"
echo "- X-Dev-User rejected with HTTP ${dev_status}"
echo "- DingTalk production mode configured"
echo "- placeholder configuration failed closed without logging secrets"
