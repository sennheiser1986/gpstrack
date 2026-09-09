#!/usr/bin/env bash
# Create or reset the sharing server's admin account.
#   sudo -u gpstrack ./reset-admin-password.sh [username]
# Prompts for the new password. Uses the same database the service uses
# (GPSTRACK_SHARE_DB, defaulting to the systemd StateDirectory).
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export GPSTRACK_SHARE_DB="${GPSTRACK_SHARE_DB:-/var/lib/gpstrack-share/gpstrack-share.db}"

PY="$DIR/.venv/bin/python"
[ -x "$PY" ] || PY="$(command -v python3)"

exec "$PY" "$DIR/manage.py" set-admin-password "$@"
