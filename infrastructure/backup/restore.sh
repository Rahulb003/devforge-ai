#!/usr/bin/env bash
# Restores a backup taken by backup.sh into the docker compose stack, replacing what is there.
#
#   infrastructure/backup/restore.sh <backup-directory> --replace-existing-data
#
# Destructive by design: every devforge database in the backup is dropped and recreated, and the
# git repositories volume is emptied and refilled. Hence the flag - there is no undo, so take a
# backup of the current state first if it might be wanted.
#
# The application services are stopped for the duration, so nothing writes between the drop and
# the restore, and started again afterwards; PostgreSQL, Kafka and Redis keep running.
set -euo pipefail

dir="${1:-}"
[ -n "$dir" ] && [ "${2:-}" = "--replace-existing-data" ] || {
  echo "usage: $0 <backup-directory> --replace-existing-data" >&2
  exit 2
}
user="${POSTGRES_USER:-devforge}"

# Verify before destroying anything: a truncated or altered backup is refused whole.
(cd "$dir" && sha256sum --check --quiet SHA256SUMS) || { echo "backup failed verification" >&2; exit 1; }

services="api-gateway auth-service project-service task-service git-service review-service
  documentation-service chat-service analytics-service notification-service ai-service"
# shellcheck disable=SC2086
docker compose stop $services

for dump in "$dir"/devforge_*.dump; do
  db="$(basename "$dump" .dump)"
  docker compose exec -T postgres dropdb -U "$user" --if-exists --force "$db"
  docker compose exec -T postgres createdb -U "$user" "$db"
  docker compose exec -T postgres pg_restore -U "$user" --no-owner --exit-on-error -d "$db" < "$dump"
  echo "restored $db"
done

# A one-off container of git-service's own image, so the files are written as the user that owns
# the volume. Objects before refs, the reverse of the backup, so no ref ever points at nothing.
git_storage() {
  docker compose run --rm -T --no-deps --entrypoint sh git-service -c "set -e; cd /app/data/git-repositories; $1"
}
git_storage 'find . -mindepth 1 -maxdepth 1 -exec rm -rf {} +' < /dev/null
# An empty archive is the backup of an empty volume: nothing to extract.
for archive in git-objects.tar git-refs.tar; do
  if [ -s "$dir/$archive" ]; then
    git_storage 'tar -xf -' < "$dir/$archive"
  fi
done
echo "restored git repositories"

# shellcheck disable=SC2086
docker compose up -d --wait --wait-timeout 600 $services
echo "restore complete"
