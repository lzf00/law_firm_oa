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

(
  cd "${backup_dir}"
  shasum -a 256 database.dump object-storage.tar > SHA256SUMS
)

echo "Backup created: ${backup_dir}"
