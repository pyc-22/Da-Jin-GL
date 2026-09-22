#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
set -a
source ./.env
set +a

backup_dir="${BACKUP_DIR:-./backup}"
retention_days="${RETENTION_DAYS:-30}"
stamp="$(date +%Y%m%d_%H%M%S)"
mkdir -p "$backup_dir"

docker compose exec -T mysql mysqldump \
  -uroot "-p${MYSQL_ROOT_PASSWORD}" \
  --single-transaction --routines --events "${MYSQL_DATABASE}" \
  | gzip > "${backup_dir}/dajin_backup_${stamp}.sql.gz"

find "$backup_dir" -name 'dajin_backup_*.sql.gz' -type f -mtime "+${retention_days}" -delete
echo "Backup created: ${backup_dir}/dajin_backup_${stamp}.sql.gz"
