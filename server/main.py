"""GPS Track sharing server.

Two jobs:

* **Relay** (unchanged): app instances ``POST /sync`` their position and read back the
  positions of the peer ids they are watching. Positions live only in memory (see
  ``positions.py``) with a short TTL.
* **Web following**: an admin (``/admin``) creates named web users; a web user signs in
  (``/login``), sees the known devices and *requests* to follow one; the device owner
  approves or denies the request **in the app** — the decision rides back in the ``/sync``
  response/request. An approved web user gets a live map at ``/map/<device_id>``.

Persistent data (admins, web users, devices, follow requests, sessions) is in SQLite
(``db.py``). Run::

    pip install -r requirements.txt
    uvicorn main:app --host 0.0.0.0 --port 8080

Bootstrap the admin account with ``./reset-admin-password.sh``.
"""

from __future__ import annotations

import contextlib
import hashlib
import logging
import secrets
import time

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

import db
import positions
from admin_routes import router as admin_router
from auth import RedirectException, redirect_exception_handler
from web_routes import router as web_router

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("gpstrack_share")


@contextlib.asynccontextmanager
async def lifespan(_app: FastAPI):
    """Create the database schema before serving requests."""
    db.init_db()
    db.prune_sessions()
    yield


app = FastAPI(title="GPS Track sharing server", version="2.0", lifespan=lifespan)
app.add_exception_handler(RedirectException, redirect_exception_handler)
app.include_router(admin_router)
app.include_router(web_router)


class DeviceLoginRequest(BaseModel):
    """The body of ``POST /device-login``: a web-user credential plus the device claiming it."""

    username: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=200)
    device_id: str = Field(min_length=1, max_length=128)
    label: str = Field(default="", max_length=100)


def _token_hash(token: str) -> str:
    """Hash a device token for storage; tokens are high-entropy, so a fast hash is fine.

    :param token: the bearer token.
    :return: hex SHA-256.
    """
    return hashlib.sha256(token.encode()).hexdigest()


@app.post("/device-login")
def device_login(request: DeviceLoginRequest) -> dict:
    """Sign a device in with a web-user account and mint its sync token.

    The device row is created (or re-claimed) here — a device that never signed in does not
    exist to this server, so nothing anonymous can pollute the database. Signing in again
    rotates the token; the old one stops working.

    :param request: the parsed body.
    :return: ``{"token": <bearer token for /sync>}``.
    :raises HTTPException: 401 on a wrong credential or disabled account.
    """
    from passwords import verify_password  # local import: avoids a cycle at module load

    user = db.query_one(
        "SELECT * FROM web_users WHERE username = ?", (request.username.strip(),),
    )
    if user is None or user["disabled"] or not verify_password(request.password, user["password_hash"]):
        logger.info("device-login REFUSED user=%s device=%s", request.username, request.device_id[:12])
        raise HTTPException(status_code=401, detail="Wrong username or password")

    token = secrets.token_urlsafe(32)
    now = time.time()
    db.execute(
        """
        INSERT INTO devices (id, label, first_seen, last_seen, owner_user_id, token_hash)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
            label = excluded.label,
            last_seen = excluded.last_seen,
            owner_user_id = excluded.owner_user_id,
            token_hash = excluded.token_hash
        """,
        (request.device_id, request.label, now, now, user["id"], _token_hash(token)),
    )
    logger.info("device-login OK user=%s device=%s", request.username, request.device_id[:12])
    return {"token": token, "username": user["username"]}


def _require_device_token(device_id: str, authorization: str | None) -> None:
    """Reject a sync that does not carry the device's current token.

    :param device_id: the device id claimed in the body.
    :param authorization: the ``Authorization`` header value.
    :raises HTTPException: 401 when the token is missing, unknown or stale.
    """
    token = None
    if authorization and authorization.startswith("Bearer "):
        token = authorization.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(status_code=401, detail="Sign in required")
    row = db.query_one("SELECT token_hash FROM devices WHERE id = ?", (device_id,))
    if row is None or not row["token_hash"] or row["token_hash"] != _token_hash(token):
        raise HTTPException(status_code=401, detail="Sign in required")


class SyncRequest(BaseModel):
    """The body of ``POST /sync``.

    ``follow_decisions`` maps a follow-request id (as a string) to ``"approved"`` or
    ``"denied"`` — the app puts a device owner's choices here.
    """

    id: str = Field(min_length=1, max_length=128)
    label: str = Field(default="", max_length=100)
    broadcasting: bool = False
    lat: float | None = None
    lon: float | None = None
    time: float | None = None
    watching: list[str] = Field(default_factory=list, max_length=200)
    follow_decisions: dict[str, str] = Field(default_factory=dict)


def _record_device(device_id: str, label: str, now: float) -> None:
    """Refresh a signed-in device's label and last-seen time. Rows are only ever created by
    ``/device-login``, so an unauthenticated id can never appear here.

    :param device_id: the device's sharing id.
    :param label: its current display name.
    :param now: current unix time.
    """
    db.execute(
        "UPDATE devices SET label = ?, last_seen = ? WHERE id = ?",
        (label, now, device_id),
    )


def _apply_follow_decisions(device_id: str, decisions: dict[str, str], now: float) -> None:
    """Apply a device owner's approve/deny choices to its own pending follow requests.

    :param device_id: the deciding device's id (a device can only decide its own follows).
    :param decisions: follow-request id (string) to ``"approved"``/``"denied"``.
    :param now: current unix time.
    """
    for raw_id, choice in decisions.items():
        if choice not in ("approved", "denied"):
            continue
        try:
            follow_id = int(raw_id)
        except (TypeError, ValueError):
            continue
        db.execute(
            "UPDATE follows SET status = ?, decided_at = ? WHERE id = ? AND device_id = ?",
            (choice, now, follow_id, device_id),
        )


def _pending_requests(device_id: str) -> list[dict]:
    """Follow requests awaiting this device owner's decision.

    :param device_id: the device's id.
    :return: ``[{id, user, requested_at}]``.
    """
    rows = db.query(
        """
        SELECT f.id AS id, u.username AS username, f.requested_at AS requested_at
        FROM follows f JOIN web_users u ON u.id = f.web_user_id
        WHERE f.device_id = ? AND f.status = 'pending' AND u.disabled = 0
        ORDER BY f.requested_at
        """,
        (device_id,),
    )
    return [{"id": r["id"], "user": r["username"], "requested_at": r["requested_at"]} for r in rows]


def _approved_followers(device_id: str) -> list[dict]:
    """Web users who currently have an approved follow of this device.

    :param device_id: the device's id.
    :return: ``[{id, user}]``.
    """
    rows = db.query(
        """
        SELECT f.id AS id, u.username AS username
        FROM follows f JOIN web_users u ON u.id = f.web_user_id
        WHERE f.device_id = ? AND f.status = 'approved' AND u.disabled = 0
        ORDER BY u.username
        """,
        (device_id,),
    )
    return [{"id": r["id"], "user": r["username"]} for r in rows]


@app.post("/sync")
def sync(request: SyncRequest, authorization: str | None = Header(default=None)) -> dict:
    """Relay endpoint, plus follow-request handling. Requires the device token minted by
    ``POST /device-login``.

    :param request: the parsed body.
    :param authorization: ``Bearer <device token>``.
    :return: ``{peers, follow_requests, followers}``.
    :raises HTTPException: 401 when the device is not signed in.
    """
    _require_device_token(request.id, authorization)
    now = time.time()
    logger.info(
        "sync id=%s broadcasting=%s pos=%s watching=%d decisions=%s",
        request.id,
        request.broadcasting,
        (request.lat, request.lon) if request.broadcasting else None,
        len(request.watching),
        request.follow_decisions or {},
    )

    _record_device(request.id, request.label, now)
    if request.follow_decisions:
        _apply_follow_decisions(request.id, request.follow_decisions, now)

    if request.broadcasting and request.lat is not None and request.lon is not None:
        positions.store(request.id, request.lat, request.lon, request.time)
        positions.set_label(request.id, request.label)
    elif not request.broadcasting:
        positions.forget(request.id)

    peers = {}
    for peer_id in request.watching:
        entry = positions.latest(peer_id)
        if entry is not None:
            peers[peer_id] = entry

    return {
        "peers": peers,
        "follow_requests": _pending_requests(request.id),
        "followers": _approved_followers(request.id),
    }


@app.get("/healthz")
def healthz() -> dict:
    """Liveness probe.

    :return: ``{"status": "ok", "tracked": <count>, "devices": <count>}``.
    """
    device_count = db.query_one("SELECT COUNT(*) AS n FROM devices")
    return {
        "status": "ok",
        "tracked": positions.tracked_count(),
        "devices": device_count["n"] if device_count else 0,
    }
