#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
migration_dir="${project_dir}/backend/src/main/resources/db/migration"

mapfile_compat() {
  if command -v mapfile >/dev/null 2>&1; then
    mapfile "$@"
  else
    readarray "$@"
  fi
}

migrations=()
while IFS= read -r migration; do
  migrations+=("${migration}")
done < <(find "${migration_dir}" -maxdepth 1 -type f -name 'V*__*.sql' | sort -V)

[[ "${#migrations[@]}" -gt 0 ]] || {
  echo "No production migrations found" >&2
  exit 2
}

expected=1
for migration in "${migrations[@]}"; do
  filename="$(basename "${migration}")"
  version="${filename#V}"
  version="${version%%__*}"
  [[ "${version}" = "${expected}" ]] || {
    echo "Migration sequence gap: expected V${expected}, found ${filename}" >&2
    exit 3
  }
  expected=$((expected + 1))
done

if rg -n -i \
  '(^|[[:space:]])(DROP[[:space:]]+(TABLE|COLUMN)|TRUNCATE[[:space:]]|ALTER[[:space:]].*SET[[:space:]]+NOT[[:space:]]+NULL)' \
  "${migration_dir}"; then
  echo "A destructive or contract-phase migration requires an explicit rollback waiver" >&2
  exit 4
fi

docker compose --project-directory "${project_dir}" config --quiet
docker compose --project-directory "${project_dir}" \
  -f "${project_dir}/docker-compose.prod.yml" config --quiet \
  >/dev/null 2>&1 || {
    echo "Production Compose requires the documented environment file" >&2
  }

echo "Rollback compatibility check passed:"
echo "- contiguous expand-only migrations: V1..V$((expected - 1))"
echo "- no DROP/TRUNCATE/contract-phase NOT NULL operation"
echo "- development Compose configuration is valid"
