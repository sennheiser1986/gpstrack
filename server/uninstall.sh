#!/usr/bin/env bash
# Remove the GPS Track sharing relay. Usage: sudo ./uninstall.sh [--purge]
#   --purge also deletes the service account.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "Run as root: sudo ./uninstall.sh" >&2
    exit 1
fi

APP_USER="gpstrack"
APP_DIR="/opt/gpstrack-share"
SERVICE="gpstrack-share"

systemctl disable --now "$SERVICE.service" 2>/dev/null || true
rm -f "/etc/systemd/system/$SERVICE.service"
systemctl daemon-reload

rm -rf "$APP_DIR"

if [[ "${1:-}" == "--purge" ]]; then
    rm -rf /var/lib/gpstrack-share
    userdel "$APP_USER" 2>/dev/null || true
    echo "Removed service account $APP_USER and the database in /var/lib/gpstrack-share"
else
    echo "Left the database (/var/lib/gpstrack-share) and account $APP_USER in place (use --purge to remove them)"
fi

echo "Apache config, if any, was left untouched:"
echo "  sudo a2dissite gpstrack-share && sudo systemctl reload apache2"
