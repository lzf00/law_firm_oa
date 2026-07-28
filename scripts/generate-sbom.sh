#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${1:-${project_dir}/release-evidence/sbom}"
backend_container="law-oa-sbom-backend-$$"
frontend_container="law-oa-sbom-frontend-$$"

case "${output_dir}" in
  "${project_dir}/release-evidence/"*) ;;
  *) echo "SBOM output must be inside ${project_dir}/release-evidence" >&2; exit 2 ;;
esac

cleanup() {
  docker rm -f "${backend_container}" "${frontend_container}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

mkdir -p "${output_dir}"
docker compose --project-directory "${project_dir}" build backend frontend
docker create --name "${backend_container}" law_firm_oa-backend:latest >/dev/null
docker create --name "${frontend_container}" law_firm_oa-frontend:latest >/dev/null
docker cp "${backend_container}:/opt/sbom/backend.cdx.json" "${output_dir}/backend.cdx.json"
docker cp "${frontend_container}:/opt/sbom/frontend.cdx.json" "${output_dir}/frontend.cdx.json"
shasum -a 256 "${output_dir}/backend.cdx.json" "${output_dir}/frontend.cdx.json" \
  > "${output_dir}/SHA256SUMS"

echo "SBOM evidence generated: ${output_dir}"
