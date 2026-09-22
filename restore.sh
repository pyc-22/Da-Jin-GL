#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
set -a
source ./.env
set +a

backup_file="${1:-}"
if [[ -z "$backup_file" || ! -f "$backup_file" ]]; then
  echo "Usage: ./restore.sh backup/dajin_backup_YYYYMMDD_HHmmss.sql.gz"
  exit 1
fi

read -r -p "Type RESTORE to continue: " confirm
[[ "$confirm" == "RESTORE" ]] || exit 1

./backup.sh
docker compose stop backend
trap 'docker compose start backend' EXIT
gzip -dc "$backup_file" | docker compose exec -T mysql mysql -uroot "-p${MYSQL_ROOT_PASSWORD}" "${MYSQL_DATABASE}"
docker compose start backend
trap - EXIT
echo "Restore completed."
