#!/usr/bin/env bash
# Pull the latest code and restart: sudo ./deploy/update.sh
set -euo pipefail
cd "$(dirname "$0")/.."
./deploy/backup.sh
git pull --ff-only
docker compose --env-file deploy/.env up -d --build
docker image prune -f >/dev/null
echo "Updated."
