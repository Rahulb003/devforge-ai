# Backup and restore

For the docker compose deployment. Kubernetes has no equivalent yet: its PostgreSQL and git
storage are cluster volumes, and backing those up belongs to the cluster's own tooling (volume
snapshots, or a managed database's point-in-time recovery).

## What holds state

| Store | Backed up | Why |
|---|---|---|
| PostgreSQL, one `devforge_*` database per service | yes, `pg_dump` per database | every service's records |
| `git-repositories` volume | yes, as two tar files | the repositories themselves - the rows only point at them |
| Redis | no | rate-limit counters, meant to expire |
| Kafka | no | events are already applied to the databases; see "What a restore loses" |

## Taking a backup

```bash
infrastructure/backup/backup.sh                     # into backups/<UTC timestamp>/
infrastructure/backup/backup.sh /srv/devforge-backups/nightly
```

The stack keeps running. The directory gets one `.dump` per database, `git-refs.tar`,
`git-objects.tar` and a `SHA256SUMS` manifest. Copy the whole directory off the host: a backup on
the same disk as the data does not survive the disk. The dumps contain every user record, password
hash and token hash, so store them as you would the database.

Refs are archived before objects, deliberately - see the comment in `backup.sh`. If a repository is
repacked while the backup runs, the archive fails and so does the script; run it again.

## Restoring

```bash
infrastructure/backup/restore.sh /srv/devforge-backups/nightly --replace-existing-data
```

The flag is required because a restore **replaces** the current data: each database in the backup is
dropped and recreated, and the git volume is emptied. The manifest is verified first, so an
altered or truncated backup is refused before anything is touched. The application services are
stopped during the restore and started again at the end; PostgreSQL, Kafka and Redis stay up.

## What a restore loses

- Everything written after the backup was taken.
- Events published after the backup are not replayed: Kafka keeps its offsets, so a notification
  or analytics counter for a change that the restore undid stays as it was.
- The dumps are taken one database after another, so a change made during the backup can be in one
  service's dump and not another's. Services share no foreign keys, so this shows as, say, a
  notification about a task that is not there - not as broken data.

## Verified

The compose CI job rehearses it on every push (`A backup restores the stack`): it creates a
repository with a commit and a task, backs up, deletes the repository and adds another task,
checks that an altered backup is refused, restores, and requires the file to be readable through
the API again and the later task to be gone. Restoring onto a **different** host, and backups
taken under real load, are `UNVERIFIED`.
