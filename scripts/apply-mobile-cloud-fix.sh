#!/usr/bin/env bash
# Usage: sudo bash apply-mobile-cloud-fix.sh /absolute/path/to/existing/server
# Run only during a maintenance window. Uses existing Compose project, secrets and SSL mounts.
set -Eeuo pipefail
umask 077
package=$(cd "$(dirname "$0")" && pwd)
test "$(id -u)" = 0 || { echo 'Run with sudo bash.'; exit 1; }
for command in docker python3 curl sha256sum realpath; do command -v "$command" >/dev/null; done
(cd "$package" && sha256sum -c SHA256SUMS.txt)
deployment=$(realpath "${1:?Supply the existing server directory containing docker-compose.yml}")
test -f "$deployment/docker-compose.yml"
test -f "$deployment/backend/backend-1.0.0-rc.1.jar"
test -d "$deployment/mobile/dist"
test -f "$package/payload/backend.jar"
for file in mobile.nginx.conf mobile.Dockerfile schema.sql 20260920_handover.sql h5/index.html; do test -f "$package/payload/$file"; done
cd "$deployment"
backup="$deployment/backup/mobile-fix-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$backup"
chmod 700 "$backup"
docker compose config --quiet
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF --databases "$MYSQL_DATABASE"' > "$backup/database.sql"
test -s "$backup/database.sql"
cp -p backend/backend-1.0.0-rc.1.jar "$backup/backend.jar"
cp -a mobile "$backup/mobile"
cp -a db "$backup/db"
cp -p docker-compose.yml "$backup/"
if test -f docker-compose.override.yml; then cp -p docker-compose.override.yml "$backup/"; fi
# Preserve prior images for manual rollback; never run compose down or delete volumes.
for service in backend mobile-web; do
  cid=$(docker compose ps -q "$service")
  test -n "$cid"
  image=$(docker inspect "$cid" --format '{{.Image}}')
  tag="dajin/rollback-$service:$(basename "$backup")"
  docker tag "$image" "$tag"
  printf '%s %s\n' "$service" "$tag" >> "$backup/rollback-images.txt"
done
admin=$(docker compose ps -q admin-web)
conf_dir=$(docker inspect "$admin" --format '{{range .Mounts}}{{if eq .Destination "/etc/nginx/conf.d"}}{{.Source}}{{end}}{{end}}')
test -n "$conf_dir" && test -d "$conf_dir"
cp -a "$conf_dir" "$backup/nginx-conf"
printf '%s\n' "$conf_dir" > "$backup/nginx-conf-path.txt"
on_error() {
  trap - ERR
  cp -p "$backup/nginx-conf/"*.conf "$conf_dir/"
  docker exec "$admin" nginx -t && docker exec "$admin" nginx -s reload || true
  echo "Update stopped. Nginx config restored. Backup: $backup"
  echo "Rollback command: sudo bash $package/rollback-mobile-cloud-fix.sh '$deployment' '$backup'"
  exit 1
}
trap on_error ERR
cp "$package/payload/backend.jar" backend/backend-1.0.0-rc.1.jar
cp "$package/payload/mobile.nginx.conf" mobile/nginx.conf
cp "$package/payload/mobile.Dockerfile" mobile/Dockerfile
# Preserve old hash-named static assets for sessions open during rollout.
cp -a "$package/payload/h5/." mobile/dist/
cp "$package/payload/schema.sql" db/schema.sql
docker compose build backend mobile-web
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE"' < "$package/payload/20260920_handover.sql"
python3 "$package/patch-nginx-mobile.py" "$conf_dir/"*.conf
docker exec "$admin" nginx -t
docker compose up -d --no-deps --force-recreate backend mobile-web
ready=0
for attempt in $(seq 1 60); do
  if docker exec "$admin" wget -q -O - http://backend:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then ready=1; break; fi
  sleep 2
done
test "$ready" = 1
# Compose "Started" does not guarantee that the Nginx master has written its PID.
# The recreated container already loads the new configuration; do not reload it.
mobile_ready=0
for attempt in $(seq 1 60); do
  if docker compose exec -T mobile-web sh -c 'test -s /var/run/nginx.pid && kill -0 "$(cat /var/run/nginx.pid)" && wget -q -O /dev/null http://127.0.0.1/' 2>/dev/null; then mobile_ready=1; break; fi
  sleep 2
done
test "$mobile_ready" = 1
docker compose exec -T mobile-web nginx -t
docker exec "$admin" nginx -t
docker exec "$admin" nginx -s reload
curl --fail --silent --show-error https://admin.xinchengjinjiang.com/actuator/health | python3 -c 'import sys,json; assert json.load(sys.stdin)["status"] == "UP"'
curl --fail --silent --show-error https://m.xinchengjinjiang.com/actuator/health | python3 -c 'import sys,json; assert json.load(sys.stdin)["status"] == "UP"'
trap - ERR
echo "Server patch applied. Backup: $backup"
echo 'Next: install the new APK and verify signed-in photo upload/read-back and cross-device sync.'
