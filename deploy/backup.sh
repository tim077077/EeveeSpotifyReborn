#!/usr/bin/env bash
# Back up the database, media and secrets: sudo ./deploy/backup.sh  ->  backups/slidemill-<date>.tar.gz
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p backups
STAMP=$(date +%Y%m%d-%H%M%S)
VOLUME=$(docker volume ls -q | grep -E '(^|_)slidemill-data$' | head -1)
[ -n "$VOLUME" ] || { echo "data volume not found" >&2; exit 1; }
docker run --rm -v "$VOLUME":/data:ro -v "$PWD/backups":/out alpine \
  tar czf "/out/slidemill-$STAMP.tar.gz" -C /data .
cp deploy/.env "backups/env-$STAMP"
chmod 600 "backups/env-$STAMP" "backups/slidemill-$STAMP.tar.gz"
echo "Saved backups/slidemill-$STAMP.tar.gz and backups/env-$STAMP (keep both; the env file holds the encryption key)."
