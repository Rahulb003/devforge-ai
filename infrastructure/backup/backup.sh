#!/usr/bin/env bash
# Backs up a running docker compose stack: every service database, and the git repositories.
#
#   infrastructure/backup/backup.sh [output-directory]
#
# Run from the repository root, against the stack docker-compose.yml started. Writes one pg_dump
# (custom format) per devforge_* database, the git repositories as two tar files, and a SHA256SUMS
# manifest that restore.sh verifies before touching anything.
#
# What this is and is not:
# - Each database is dumped consistently on its own, but the dumps are taken one after another, so
#   a change landing mid-backup can be in one service's dump and not another's. Services already
#   tolerate that shape - they share no foreign keys - but a quiet moment gives a cleaner backup.
# - Redis is not backed up: it holds rate-limit counters, which are meant to be lost.
# - Kafka is not backed up. After a restore, events published since the backup are not replayed.
set -euo pipefail

out="${1:-backups/$(date -u +%Y%m%dT%H%M%SZ)}"
user="${POSTGRES_USER:-devforge}"
mkdir -p "$out"

databases="$(docker compose exec -T postgres psql -U "$user" -d postgres -tAc \
  "select datname from pg_database where datname like 'devforge\_%' order by datname")"
[ -n "$databases" ] || { echo "no devforge databases found - is the stack running?" >&2; exit 1; }

for db in $databases; do
  docker compose exec -T postgres pg_dump -U "$user" --format=custom --no-owner "$db" > "$out/$db.dump"
  echo "dumped $db ($(wc -c < "$out/$db.dump") bytes)"
done

# Refs first, objects second. Git writes a commit's objects before it moves a ref to it, so every
# ref captured in the first archive points at objects that already existed - and are still there
# for the second. The other order can capture a ref whose objects were written after the object
# archive was taken: a repository that restores corrupt. A file vanishing mid-archive (a repack)
# fails the archive, and the backup with it, rather than producing a quietly incomplete one.
docker compose exec -T git-service sh -c '
  set -e
  cd /app/data/git-repositories
  find . -type f ! -path "*/objects/*" > /tmp/git-refs.list
  [ -s /tmp/git-refs.list ] || exit 0
  tar -cf - -T /tmp/git-refs.list' > "$out/git-refs.tar"
docker compose exec -T git-service sh -c '
  set -e
  cd /app/data/git-repositories
  find . -type f -path "*/objects/*" > /tmp/git-objects.list
  [ -s /tmp/git-objects.list ] || exit 0
  tar -cf - -T /tmp/git-objects.list' > "$out/git-objects.tar"
echo "archived git repositories ($(wc -c < "$out/git-refs.tar") + $(wc -c < "$out/git-objects.tar") bytes)"

(cd "$out" && sha256sum -- *.dump git-refs.tar git-objects.tar > SHA256SUMS)
echo "backup written to $out"
