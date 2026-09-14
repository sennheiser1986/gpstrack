"""The web-follow site: web users sign in, request to follow devices, and — once the device
owners approve in the app — watch everyone on one live map.

The only places a web user receives coordinates are ``GET /api/locations`` (all approved
follows at once, for the shared map) and the legacy ``GET /api/location/{device_id}``; both
hand out a device's position only under an ``approved`` follow.
"""

from __future__ import annotations

import os
import time

from fastapi import APIRouter, Depends, Form, HTTPException, Request
from fastapi.responses import FileResponse, RedirectResponse

import db
import positions
from auth import end_session, require_web_user, start_session
from passwords import hash_password, verify_password
from templating import templates

router = APIRouter()

#: Where the downloadable Android APK is dropped (next to the code by default). The routes
#: only appear to work when the file actually exists; copying a new APK there is the whole
#: release process.
APK_PATH = os.environ.get(
    "GPSTRACK_APK", os.path.join(os.path.dirname(os.path.abspath(__file__)), "apk", "gpstrack.apk"),
)


def apk_available() -> bool:
    """Whether a downloadable APK is present on this server.

    :return: True when the file at :data:`APK_PATH` exists.
    """
    return os.path.isfile(APK_PATH)


@router.get("/app.apk")
def download_apk(user=Depends(require_web_user)):
    """Serve the Android app for sideloading, to signed-in web users only — the APK bakes in
    this server's URL, and anyone holding it could register devices here.

    :param user: the signed-in web user (injected).
    :return: the APK file.
    :raises HTTPException: 404 when no APK has been uploaded to this server.
    """
    if not apk_available():
        raise HTTPException(status_code=404, detail="No app package is hosted on this server")
    return FileResponse(
        APK_PATH,
        media_type="application/vnd.android.package-archive",
        filename="gpstrack.apk",
    )


@router.get("/login")
def login_form(request: Request):
    """Show the web-user sign-in form."""
    return templates.TemplateResponse(request, "login.html", {"error": None})


@router.post("/login")
def login_submit(request: Request, username: str = Form(...), password: str = Form(...)):
    """Check a web user's credentials and open a session."""
    row = db.query_one("SELECT * FROM web_users WHERE username = ?", (username.strip(),))
    if row is None or row["disabled"] or not verify_password(password, row["password_hash"]):
        return templates.TemplateResponse(
            request, "login.html", {"error": "Wrong username or password."}, status_code=401,
        )
    target = "/change-password" if row["must_change_password"] else "/"
    response = RedirectResponse(target, status_code=303)
    start_session(response, request, "web", str(row["id"]))
    return response


@router.post("/logout")
def logout(request: Request):
    """End the web-user session."""
    response = RedirectResponse("/login", status_code=303)
    end_session(response, request)
    return response


@router.get("/change-password")
def change_password_form(request: Request, user=Depends(require_web_user)):
    """Show the change-password form (mandatory right after an admin-set password)."""
    return templates.TemplateResponse(
        request, "change_password.html", {"forced": bool(user["must_change_password"]), "error": None},
    )


@router.post("/change-password")
def change_password_submit(
    request: Request,
    current: str = Form(...),
    new_password: str = Form(...),
    user=Depends(require_web_user),
):
    """Change the signed-in web user's password."""
    if not verify_password(current, user["password_hash"]):
        return templates.TemplateResponse(
            request, "change_password.html",
            {"forced": bool(user["must_change_password"]), "error": "Current password is wrong."},
            status_code=400,
        )
    if len(new_password) < 8:
        return templates.TemplateResponse(
            request, "change_password.html",
            {"forced": bool(user["must_change_password"]), "error": "Use at least 8 characters."},
            status_code=400,
        )
    db.execute(
        "UPDATE web_users SET password_hash = ?, must_change_password = 0 WHERE id = ?",
        (hash_password(new_password), user["id"]),
    )
    return RedirectResponse("/", status_code=303)


@router.get("/")
def dashboard(request: Request, user=Depends(require_web_user)):
    """List known devices with this user's follow status for each."""
    if user["must_change_password"]:
        return RedirectResponse("/change-password", status_code=303)

    devices = db.query("SELECT * FROM devices ORDER BY last_seen DESC")
    follow_rows = db.query(
        "SELECT device_id, status FROM follows WHERE web_user_id = ?", (user["id"],),
    )
    follow_status = {r["device_id"]: r["status"] for r in follow_rows}

    items = [
        {
            "id": d["id"],
            "label": d["label"] or d["id"][:8],
            "short_id": d["id"][:8],
            "last_seen": d["last_seen"],
            "online": positions.is_online(d["id"]),
            "status": follow_status.get(d["id"]),
        }
        for d in devices
    ]
    return templates.TemplateResponse(
        request, "dashboard.html",
        {"user": user, "devices": items, "now": time.time(), "apk": apk_available()},
    )


@router.post("/follow")
def request_follow(device_id: str = Form(...), user=Depends(require_web_user)):
    """Create (or re-open) a pending follow request for a device."""
    now = time.time()
    existing = db.query_one(
        "SELECT id FROM follows WHERE web_user_id = ? AND device_id = ?", (user["id"], device_id),
    )
    if existing is None:
        db.execute(
            "INSERT INTO follows (web_user_id, device_id, status, requested_at) "
            "VALUES (?, ?, 'pending', ?)",
            (user["id"], device_id, now),
        )
    else:
        db.execute(
            "UPDATE follows SET status = 'pending', requested_at = ?, decided_at = NULL WHERE id = ?",
            (now, existing["id"]),
        )
    return RedirectResponse("/", status_code=303)


@router.post("/unfollow")
def cancel_follow(device_id: str = Form(...), user=Depends(require_web_user)):
    """Withdraw a follow request or stop following a device."""
    db.execute(
        "DELETE FROM follows WHERE web_user_id = ? AND device_id = ?", (user["id"], device_id),
    )
    return RedirectResponse("/", status_code=303)


def _approved_follow(user_id: int, device_id: str):
    """The approved follow row for a (user, device) pair, or None.

    :param user_id: the web user's id.
    :param device_id: the device id.
    :return: the row, or None.
    """
    return db.query_one(
        "SELECT 1 FROM follows WHERE web_user_id = ? AND device_id = ? AND status = 'approved'",
        (user_id, device_id),
    )


def _approved_devices(user_id: int):
    """Every device the user holds an approved follow for, with its stored label.

    :param user_id: the web user's id.
    :return: rows of (device id, label), ordered by label.
    """
    return db.query(
        "SELECT d.id, d.label FROM follows f JOIN devices d ON d.id = f.device_id "
        "WHERE f.web_user_id = ? AND f.status = 'approved' "
        "ORDER BY COALESCE(NULLIF(d.label, ''), d.id)",
        (user_id,),
    )


@router.get("/map")
def combined_map_page(request: Request, user=Depends(require_web_user)):
    """One live map with every device the user is approved to follow."""
    devices = [
        {"id": d["id"], "label": d["label"] or d["id"][:8]}
        for d in _approved_devices(user["id"])
    ]
    return templates.TemplateResponse(request, "map.html", {"devices": devices})


@router.get("/map/{device_id}")
def map_page(device_id: str, user=Depends(require_web_user)):
    """Old per-device map URL; everyone now shares one map."""
    return RedirectResponse("/map", status_code=303)


@router.get("/api/locations")
def api_locations(user=Depends(require_web_user)):
    """Latest positions of every device the user is approved to follow, for the map's poll.

    Devices without a fresh position are still listed (with null coordinates) so the map's
    legend can show them as offline instead of silently dropping them.

    :param user: the signed-in web user (injected).
    :return: ``{"devices": [{id, label, lat, lon, time, online}, ...]}``.
    """
    result = []
    for device in _approved_devices(user["id"]):
        entry = positions.latest(device["id"])
        result.append(
            {
                "id": device["id"],
                "label": device["label"] or device["id"][:8],
                "lat": entry["lat"] if entry else None,
                "lon": entry["lon"] if entry else None,
                "time": entry["time"] if entry else None,
                "online": entry is not None,
            },
        )
    return {"devices": result}


@router.get("/api/location/{device_id}")
def api_location(device_id: str, user=Depends(require_web_user)):
    """Return a followed device's latest position (JSON); kept for older bookmarks.

    :param device_id: the device id.
    :param user: the signed-in web user (injected).
    :return: ``{lat, lon, time, label}``.
    :raises HTTPException: 403 without an approved follow, 404 when there is no fresh position.
    """
    if _approved_follow(user["id"], device_id) is None:
        raise HTTPException(status_code=403, detail="Not approved to follow this device")
    entry = positions.latest(device_id)
    if entry is None:
        raise HTTPException(status_code=404, detail="No fresh position for that device")
    return entry
