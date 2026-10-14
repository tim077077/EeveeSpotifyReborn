#!/usr/bin/env bash
# One-command production install for a fresh Ubuntu/Debian server.
#
#   git clone <your repo> slidemill && cd slidemill && sudo ./deploy/install.sh
#
# Asks for your domain and email, generates the dashboard password and encryption key,
# installs Docker if missing, and starts Slidemill behind Caddy with automatic HTTPS.
# Safe to re-run: existing secrets in deploy/.env are kept.
set -euo pipefail

cd "$(dirname "$0")/.."
ENV_FILE=deploy/.env

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf '\n\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run with sudo: sudo ./deploy/install.sh"

# --- Docker ----------------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
  say "Installing Docker…"
  curl -fsSL https://get.docker.com | sh
fi
docker compose version >/dev/null 2>&1 || die "Docker Compose plugin missing (apt install docker-compose-plugin)"

# --- configuration -----------------------------------------------------------------------
get() { [ -f "$ENV_FILE" ] && grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- || true; }

DOMAIN=$(get DOMAIN)
ACME_EMAIL=$(get ACME_EMAIL)
if [ -z "$DOMAIN" ]; then
  read -rp "Domain for Slidemill (e.g. slides.yourname.com): " DOMAIN
fi
if [ -z "$ACME_EMAIL" ]; then
  read -rp "Email for HTTPS certificates and the privacy page: " ACME_EMAIL
fi
[[ "$DOMAIN" =~ ^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] || die "'$DOMAIN' doesn't look like a domain"
[[ "$ACME_EMAIL" == *@*.* ]] || die "'$ACME_EMAIL' doesn't look like an email"

API_KEY=$(get SLIDEMILL_API_KEY)
SECRET_KEY=$(get SLIDEMILL_SECRET_KEY)
[ -n "$API_KEY" ] || API_KEY=$(openssl rand -hex 24)
[ -n "$SECRET_KEY" ] || SECRET_KEY=$(openssl rand -base64 32 | tr '+/' '-_')

if [ ! -f "$ENV_FILE" ]; then
  cp .env.example "$ENV_FILE"
fi
set_var() {
  if grep -qE "^$1=" "$ENV_FILE"; then
    sed -i "s|^$1=.*|$1=$2|" "$ENV_FILE"
  else
    printf '%s=%s\n' "$1" "$2" >> "$ENV_FILE"
  fi
}
set_var DOMAIN "$DOMAIN"
set_var ACME_EMAIL "$ACME_EMAIL"
set_var SLIDEMILL_CONTACT_EMAIL "$ACME_EMAIL"
set_var SLIDEMILL_API_KEY "$API_KEY"
set_var SLIDEMILL_SECRET_KEY "$SECRET_KEY"
set_var PUBLIC_BASE_URL "https://$DOMAIN"
chmod 600 "$ENV_FILE"

# --- DNS check ---------------------------------------------------------------------------
SERVER_IP=$(curl -fsS4 https://api.ipify.org || true)
DNS_IP=$(getent ahostsv4 "$DOMAIN" | awk 'NR==1{print $1}' || true)
if [ -n "$SERVER_IP" ] && [ "$DNS_IP" != "$SERVER_IP" ]; then
  say "DNS check: $DOMAIN points to '${DNS_IP:-nothing}', this server is $SERVER_IP."
  echo "Create an A record: $DOMAIN -> $SERVER_IP at your domain registrar, wait a few minutes, then re-run."
  echo "(HTTPS certificates can't be issued until the domain points here.)"
  read -rp "Continue anyway? [y/N] " yn
  [[ "$yn" =~ ^[Yy]$ ]] || exit 1
fi

# --- firewall ----------------------------------------------------------------------------
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null; ufw allow 443/udp >/dev/null
fi

# --- start -------------------------------------------------------------------------------
say "Building and starting (first build takes a few minutes)…"
docker compose --env-file "$ENV_FILE" up -d --build

say "Waiting for https://$DOMAIN …"
for _ in $(seq 1 60); do
  if curl -fsS "https://$DOMAIN/healthz" >/dev/null 2>&1; then
    say "Slidemill is live."
    cat <<EOF

  Dashboard:        https://$DOMAIN
  Password:         $API_KEY      (paste it when the dashboard asks)
  Privacy policy:   https://$DOMAIN/privacy
  Terms of service: https://$DOMAIN/terms
  TikTok redirect:  https://$DOMAIN/auth/tiktok/callback

  Secrets are in $ENV_FILE (chmod 600). Back up that file and the data volume:
    sudo ./deploy/backup.sh
  Next: docs/LAUNCH_CHECKLIST.md
EOF
    exit 0
  fi
  sleep 5
done
die "the site didn't come up over HTTPS. Check: docker compose --env-file $ENV_FILE logs --tail 50"
