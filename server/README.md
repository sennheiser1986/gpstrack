# GPS Track — sharing server

Two jobs:

* **Relay** for the app's "where is everyone" feature — the latest position per device, kept in
  memory only and forgotten two minutes after the device stops broadcasting.
* **Web following** — an admin creates named web users; a web user signs in in a browser, picks
  a known device and *requests* to follow it; the device owner allows or denies the request
  **in the app**; an allowed web user gets a live map page.

Persistent data (admins, web users, devices, follow requests, sessions) lives in a small SQLite
database. Positions never touch it.

## Run locally

```bash
cd server
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
GPSTRACK_SHARE_DB=./gpstrack-share.db .venv/bin/uvicorn main:app --host 0.0.0.0 --port 8080
# in another shell, once:
GPSTRACK_SHARE_DB=./gpstrack-share.db ./reset-admin-password.sh
```

Then open `http://localhost:8080/admin`, sign in, and create web users. Point the app at the
server from the **Share** tab → *Server setting*, or bake a default in with
`-PshareServerUrl=…`.

## Deploy to a VPS

```bash
sudo ./install.sh
sudo -u gpstrack /opt/gpstrack-share/reset-admin-password.sh    # bootstrap the admin
```

`install.sh` installs `python3-venv` if missing, creates the `gpstrack` user,
`/opt/gpstrack-share` (code) and `/var/lib/gpstrack-share` (the SQLite database, via
the systemd `StateDirectory`), builds the venv, and starts the `gpstrack-share` service on
`127.0.0.1:8080`. Re-run it to update.

Put **Apache** in front with TLS (the app only talks HTTPS, and the session cookies are marked
`Secure`):

```bash
sudo a2enmod proxy proxy_http headers
sudo cp gpstrack-share.apache.conf /etc/apache2/sites-available/gpstrack-share.conf
sudo $EDITOR /etc/apache2/sites-available/gpstrack-share.conf   # set ServerName
sudo a2ensite gpstrack-share && sudo systemctl reload apache2
sudo certbot --apache -d gpstrack.example.org
```

**Hosting the app for download:** drop the built APK at
`/opt/gpstrack-share/apk/gpstrack.apk` (override with the `GPSTRACK_APK` env var) and the
login page and dashboard automatically show a "Download the app" link serving it at
`/app.apk`. Build it with `./gradlew :app:assemblePrimaryRelease -PshareServerUrl=https://your-host`.

Remove with `sudo ./uninstall.sh` (`--purge` also drops the account and the database).

**Scale note:** keep it to one uvicorn process (the default). Positions are per-process memory.

## Management CLI

`manage.py` (run through the venv python, or the wrappers):

| Command | |
| --- | --- |
| `reset-admin-password.sh [username]` | create or reset an admin account (prompts) |
| `manage.py create-web-user <name>` | create a web user (prompts for the initial password) |
| `manage.py list-users` / `list-devices` | inspect the database |

## HTTP surface

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `POST` | `/sync` | device id in body | relay + device registration + follow-request handling |
| `GET` | `/healthz` | none | `{"status":"ok","tracked":N,"devices":M}` |
| `GET/POST` | `/admin/login`, `/admin`, `/admin/users…`, `/admin/follows/{id}/revoke` | admin cookie | admin panel |
| `GET/POST` | `/login`, `/`, `/follow`, `/unfollow`, `/change-password` | web cookie | web-follow site |
| `GET` | `/map/{device_id}` | web cookie + approved follow | live map page |
| `GET` | `/api/location/{device_id}` | web cookie + approved follow | JSON `{lat,lon,time,label}` for the map poll |

`POST /sync` gains `follow_decisions` (`{"<follow_id>": "approved"|"denied"}`) and its response
gains `follow_requests` (`[{id,user,requested_at}]`, pending) and `followers`
(`[{id,user}]`, approved).

## Privacy

* A device is identified only by its 43-character sharing code — a bearer secret.
* A web user receives coordinates **only** from `GET /api/location/{id}`, and only while they
  hold an `approved` follow. Deny, "Stop", admin *Revoke*, disabling the user, or the device
  ceasing to broadcast all cut it off.
* Passwords are scrypt-hashed. Sessions are random 256-bit tokens in an `HttpOnly` cookie.

## Vendoring Leaflet

`templates/map.html` loads Leaflet 1.9.4 and its CSS from `unpkg.com` (with SRI) and OSM tiles
from `tile.openstreetmap.org`. For an air-gapped deployment, download `leaflet.js` /
`leaflet.css` into a `static/` dir, `app.mount("/static", StaticFiles(directory="static"))` in
`main.py`, and point the `<link>`/`<script>` at `/static/…`.
