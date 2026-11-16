#!/bin/sh
# Dump this box's Postgres to BLEEP_BACKUP_DIR. Run from cron on the VM.
# Envelopes are sealed and short-lived. The dump is still the mailbox store. Keep it on this machine.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ ! -f .env ]; then
  echo "missing $ROOT/.env" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
. ./.env
set +a

dest="${BLEEP_BACKUP_DIR:-/var/backups/bleep}"
mkdir -p "$dest"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="$dest/bleep-${BLEEP_ENV:-box}-$stamp.sql.gz"

docker compose exec -T postgres \
  pg_dump -U "${POSTGRES_USER:-bleep}" --no-owner "${POSTGRES_DB:-bleep}" \
  | gzip -c > "$out"

find "$dest" -type f -name 'bleep-*.sql.gz' -mtime +14 -delete
echo "wrote $out"
