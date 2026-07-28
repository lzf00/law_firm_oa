#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
backup_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_dir="${1:-${project_dir}/backups/${backup_stamp}}"

case "${backup_dir}" in
  "${project_dir}/backups/"*) ;;
  *)
    echo "Backup target must be inside ${project_dir}/backups" >&2
    exit 2
    ;;
esac

mkdir -p "${backup_dir}"

(
  cd "${project_dir}"
  docker compose exec -T postgres \
    pg_dump -U law_oa -d law_oa --format=custom --no-owner --no-privileges \
    > "${backup_dir}/database.dump"
  docker compose cp \
    minio:/data/law-oa-private "${backup_dir}/object-storage-data"
)

tar -C "${backup_dir}" -cf "${backup_dir}/object-storage.tar" object-storage-data
rm -rf "${backup_dir}/object-storage-data"

git_revision="$(git -C "${project_dir}" rev-parse --verify HEAD)"
cat > "${backup_dir}/BACKUP_EVIDENCE.txt" <<EOF
created_at_utc=${backup_stamp}
git_revision=${git_revision}
database_format=postgresql_custom
object_storage_format=tar
encryption=none_local_validation_only
EOF

(
  cd "${backup_dir}"
  shasum -a 256 database.dump object-storage.tar BACKUP_EVIDENCE.txt > SHA256SUMS
)

if [[ -n "${BACKUP_METRICS_FILE:-}" ]]; then
  backup_size="$(du -sk "${backup_dir}" | awk '{print $1 * 1024}')"
  {
    echo "# HELP law_oa_backup_last_success_timestamp_seconds Last successful backup timestamp."
    echo "# TYPE law_oa_backup_last_success_timestamp_seconds gauge"
    echo "law_oa_backup_last_success_timestamp_seconds $(date -u +%s)"
    echo "# HELP law_oa_backup_last_size_bytes Size of the last successful backup."
    echo "# TYPE law_oa_backup_last_size_bytes gauge"
    echo "law_oa_backup_last_size_bytes ${backup_size}"
  } > "${BACKUP_METRICS_FILE}"
fi

echo "Backup created: ${backup_dir}"
