#!/usr/bin/env bash
# Install the GPS Track sharing relay as a systemd service.
# It listens on 127.0.0.1:8080 only; put Apache (or any reverse proxy) with TLS in front.
# Usage: sudo ./install.sh
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "Run as root: sudo ./install.sh" >&2
    exit 1
fi

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

APP_USER="gpstrack"
APP_DIR="/opt/gpstrack-share"
SERVICE="gpstrack-share"

# --- dependencies -----------------------------------------------------------------------------
# A venv is only useful with ensurepip available (python3-venv on Debian/Ubuntu also ships it).
if ! python3 -c 'import venv, ensurepip' 2>/dev/null; then
    if command -v apt-get >/dev/null; then
        echo "Installing python3-venv / python3-pip..."
        apt-get update -qq
        apt-get install -y python3-venv python3-pip
    else
        echo "python3 venv/ensurepip missing and apt-get not found - install them yourself." >&2
        exit 1
    fi
fi

# --- service account + files ----------------------------------------------------------------
if ! id "$APP_USER" >/dev/null 2>&1; then
    echo "Creating system user $APP_USER"
    useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin "$APP_USER"
fi

install -d -m 0755 -o "$APP_USER" -g "$APP_USER" "$APP_DIR"
for f in main.py db.py positions.py passwords.py auth.py templating.py admin_routes.py web_routes.py manage.py requirements.txt; do
    install -m 0644 -o "$APP_USER" -g "$APP_USER" "$SRC/$f" "$APP_DIR/$f"
done
install -m 0755 -o "$APP_USER" -g "$APP_USER" "$SRC/reset-admin-password.sh" "$APP_DIR/reset-admin-password.sh"
install -m 0644 -o "$APP_USER" -g "$APP_USER" "$SRC/README.md" "$APP_DIR/README.md" 2>/dev/null || true

# Writable data dir for the SQLite database (systemd StateDirectory adopts this on start).
install -d -m 0755 -o "$APP_USER" -g "$APP_USER" /var/lib/gpstrack-share

install -d -m 0755 -o "$APP_USER" -g "$APP_USER" "$APP_DIR/templates"
for t in "$SRC"/templates/*.html; do
    install -m 0644 -o "$APP_USER" -g "$APP_USER" "$t" "$APP_DIR/templates/$(basename "$t")"
done

# --- virtualenv -----------------------------------------------------------------------------
VENV="$APP_DIR/.venv"
VENV_PY="$VENV/bin/python"

# "Good" means the interpreter exists AND pip works inside it. A half-created venv from an
# earlier failed run (python but no pip) is rebuilt rather than skipped.
venv_ok() { [[ -x "$VENV_PY" ]] && sudo -u "$APP_USER" "$VENV_PY" -m pip --version >/dev/null 2>&1; }

if ! venv_ok; then
    echo "Creating virtualenv"
    rm -rf "$VENV"
    sudo -u "$APP_USER" python3 -m venv "$VENV"
    sudo -u "$APP_USER" "$VENV_PY" -m ensurepip --upgrade >/dev/null 2>&1 || true
fi
if ! venv_ok; then
    echo "virtualenv has no working pip - install python3-venv and python3-pip, then re-run." >&2
    exit 1
fi

echo "Installing Python dependencies"
sudo -u "$APP_USER" "$VENV_PY" -m pip install --quiet --upgrade pip
sudo -u "$APP_USER" "$VENV_PY" -m pip install --quiet -r "$APP_DIR/requirements.txt"

# --- systemd ------------------------------------------------------------------------------
install -m 0644 "$SRC/$SERVICE.service" "/etc/systemd/system/$SERVICE.service"
systemctl daemon-reload
systemctl enable "$SERVICE.service"
systemctl restart "$SERVICE.service"

sleep 1
if curl -fsS http://127.0.0.1:8080/healthz >/dev/null 2>&1; then
    echo "Health check OK (http://127.0.0.1:8080/healthz)"
else
    echo "WARNING: health check failed - see: journalctl -u $SERVICE -e" >&2
fi

cat <<EOF

Installed and running on 127.0.0.1:8080.

Create the admin account, then add web users in the admin panel:
  sudo -u $APP_USER $APP_DIR/reset-admin-password.sh
  # then open https://<your-domain>/admin

Put Apache in front with TLS:
  sudo a2enmod proxy proxy_http headers
  sudo cp $SRC/gpstrack-share.apache.conf /etc/apache2/sites-available/gpstrack-share.conf
  sudo \$EDITOR /etc/apache2/sites-available/gpstrack-share.conf   # set ServerName
  sudo a2ensite gpstrack-share
  sudo systemctl reload apache2
  sudo certbot --apache -d gpstrack.example.org                       # get the TLS vhost

Then point the app at https://gpstrack.example.org (Share tab -> Server setting),
or build with -PshareServerUrl=https://gpstrack.example.org.

Useful commands:
  systemctl status $SERVICE
  journalctl -u $SERVICE -f
  systemctl restart $SERVICE
EOF
