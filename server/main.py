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
import logging
import time

from fastapi import FastAPI
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
    """Upsert the ``devices`` row so the admin panel and web dashboard know this device.

    :param device_id: the device's sharing id.
    :param label: its current display name.
    :param now: current unix time.
    """
    db.execute(
        """
        INSERT INTO devices (id, label, first_seen, last_seen) VALUES (?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
            label = excluded.label,
            last_seen = excluded.last_seen
        """,
        (device_id, label, now, now),
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
def sync(request: SyncRequest) -> dict:
    """Relay endpoint, plus device registration and follow-request handling.

    :param request: the parsed body.
    :return: ``{peers, follow_requests, followers}``.
    """
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
