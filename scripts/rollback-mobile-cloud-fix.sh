#!/usr/bin/env bash
# Roll back application files/images only. Keep live business data and additive columns.
set -Eeuo pipefail
umask 077
test "$(id -u)" = 0 || { echo 'Run with sudo bash.'; exit 1; }
deployment=$(realpath "${1:?Supply existing server directory}")
backup=$(realpath "${2:?Supply backup directory printed by update script}")
case "$backup" in "$deployment"/backup/mobile-fix-*) ;; *) echo 'Unexpected backup directory'; exit 1;; esac
cd "$deployment"
test -s "$backup/backend.jar"
test -f "$backup/mobile/nginx.conf"
test -f "$backup/rollback-images.txt"
conf_dir=$(cat "$backup/nginx-conf-path.txt")
admin=$(docker compose ps -q admin-web)
active_conf=$(docker inspect "$admin" --format '{{range .Mounts}}{{if eq .Destination "/etc/nginx/conf.d"}}{{.Source}}{{end}}{{end}}')
test "$conf_dir" = "$active_conf"
override="$backup/rollback-compose.yml"
printf 'services:\n' > "$override"
count=0
while read -r service tag; do
  case "$service" in backend|mobile-web) ;; *) exit 1;; esac
  docker image inspect "$tag" >/dev/null
  printf '  %s:\n    image: %s\n' "$service" "$tag" >> "$override"
  count=$((count + 1))
done < "$backup/rollback-images.txt"
test "$count" = 2
cp -p "$backup/backend.jar" backend/backend-1.0.0-rc.1.jar
cp -a "$backup/mobile/." mobile/
cp -a "$backup/db/." db/
cp -p "$backup/nginx-conf/"*.conf "$conf_dir/"
compose=(docker compose -f docker-compose.yml)
if test -f docker-compose.override.yml; then compose+=(-f docker-compose.override.yml); fi
compose+=(-f "$override")
"${compose[@]}" up -d --no-deps --force-recreate --no-build --pull never backend mobile-web
ready=0
for attempt in $(seq 1 60); do
  if docker exec "$admin" wget -q -O - http://backend:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then ready=1; break; fi
  sleep 2
done
test "$ready" = 1
docker exec "$admin" nginx -t
docker exec "$admin" nginx -s reload
docker compose ps
echo "Application rollback complete. Live database and uploaded photos were preserved. Backup: $backup"
echo 'Do not start a normal compose up before reviewing application image tags; it may select the newer image again.'
