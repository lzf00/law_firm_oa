#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
backup_dir="${1:-}"

if [[ -z "${backup_dir}" ]]; then
  echo "Usage: scripts/verify-backup.sh /absolute/path/to/backups/<timestamp>" >&2
  exit 2
fi

case "${backup_dir}" in
  "${project_dir}/backups/"*) ;;
  *)
    echo "Backup source must be inside ${project_dir}/backups" >&2
    exit 2
    ;;
esac

for required_file in database.dump object-storage.tar SHA256SUMS; do
  [[ -s "${backup_dir}/${required_file}" ]] || {
    echo "Missing or empty backup artifact: ${required_file}" >&2
    exit 3
  }
done

(
  cd "${backup_dir}"
  shasum -a 256 -c SHA256SUMS
)
tar -tf "${backup_dir}/object-storage.tar" >/dev/null

restore_db="law_oa_restore_check_$(date -u +%Y%m%d%H%M%S)"
cleanup_restore_db() {
  (
    cd "${project_dir}"
    docker compose exec -T postgres \
      dropdb -U law_oa --if-exists "${restore_db}" >/dev/null
  )
}
trap cleanup_restore_db EXIT

(
  cd "${project_dir}"
  docker compose exec -T postgres createdb -U law_oa "${restore_db}"
  docker compose exec -T postgres \
    pg_restore -U law_oa -d "${restore_db}" --no-owner --no-privileges \
    < "${backup_dir}/database.dump"
  docker compose exec -T postgres psql -U law_oa -d "${restore_db}" \
    -v ON_ERROR_STOP=1 \
    -c "SELECT COUNT(*) AS flyway_rows FROM flyway_schema_history WHERE success;" \
    -c "SELECT COUNT(*) AS users FROM users;" \
    -c "SELECT COUNT(*) AS documents FROM documents;" \
    -c "SELECT COUNT(*) AS audit_logs FROM audit_logs;"
)

echo "Backup verification and isolated restore succeeded: ${backup_dir}"
