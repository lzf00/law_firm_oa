#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
backup_dir="${1:-}"
key_file="${BACKUP_ENCRYPTION_KEY_FILE:-}"
retention_days="${BACKUP_RETENTION_DAYS:-35}"
encrypted_root="${BACKUP_ENCRYPTED_ROOT:-${project_dir}/backups/encrypted}"

if [[ -z "${backup_dir}" || -z "${key_file}" ]]; then
  echo "Usage: BACKUP_ENCRYPTION_KEY_FILE=/secure/key scripts/encrypt-and-retain-backup.sh /absolute/backup/dir" >&2
  exit 2
fi
case "${backup_dir}" in
  "${project_dir}/backups/"*) ;;
  *) echo "Backup source must be inside ${project_dir}/backups" >&2; exit 2 ;;
esac
[[ -f "${key_file}" && ! -L "${key_file}" ]] || {
  echo "Encryption key must be a regular non-symlink file" >&2
  exit 3
}
key_mode="$(stat -f '%Lp' "${key_file}" 2>/dev/null || stat -c '%a' "${key_file}")"
[[ "${key_mode}" = "600" || "${key_mode}" = "400" ]] || {
  echo "Encryption key permissions must be 400 or 600" >&2
  exit 3
}
[[ "${retention_days}" =~ ^[0-9]+$ ]] \
  && (( retention_days >= 7 && retention_days <= 3650 )) || {
    echo "BACKUP_RETENTION_DAYS must be an integer from 7 to 3650" >&2
    exit 3
  }
command -v gpg >/dev/null

mkdir -p "${encrypted_root}"
archive_name="law-oa-$(basename "${backup_dir}").tar"
plain_archive="$(mktemp "${encrypted_root}/.${archive_name}.XXXXXX")"
encrypted_archive="${encrypted_root}/${archive_name}.gpg"
cleanup_plain() {
  rm -f "${plain_archive}"
}
trap cleanup_plain EXIT

tar -C "$(dirname "${backup_dir}")" -cf "${plain_archive}" "$(basename "${backup_dir}")"
gpg --batch --yes --pinentry-mode loopback \
  --passphrase-file "${key_file}" \
  --symmetric --cipher-algo AES256 --s2k-digest-algo SHA512 \
  --output "${encrypted_archive}" "${plain_archive}"
shasum -a 256 "${encrypted_archive}" > "${encrypted_archive}.sha256"

verification_archive="$(mktemp "${encrypted_root}/.verify.XXXXXX")"
gpg --batch --yes --pinentry-mode loopback \
  --passphrase-file "${key_file}" \
  --decrypt --output "${verification_archive}" "${encrypted_archive}"
tar -tf "${verification_archive}" >/dev/null
rm -f "${verification_archive}"

find "${encrypted_root}" -maxdepth 1 -type f \
  \( -name 'law-oa-*.tar.gpg' -o -name 'law-oa-*.tar.gpg.sha256' \) \
  -mtime "+${retention_days}" -delete

echo "Encrypted backup created and verified: ${encrypted_archive}"
