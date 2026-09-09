"""The admin panel: sign in, create/disable web users, reset their passwords, browse devices
and follow requests, and revoke a follow if needed.

The admin account itself is bootstrapped from the command line (``reset-admin-password.sh``),
never from the web.
"""

from __future__ import annotations

import time

from fastapi import APIRouter, Depends, Form, Request
from fastapi.responses import RedirectResponse

import db
from auth import current_admin, end_session, require_admin, start_session
from passwords import hash_password, verify_password
from templating import templates

router = APIRouter(prefix="/admin")


@router.get("/login")
def login_form(request: Request):
    """Show the admin sign-in form, or a bootstrap notice when no admin exists yet."""
    if db.query_one("SELECT 1 FROM admins LIMIT 1") is None:
        return templates.TemplateResponse(request, "no_admin.html", {})
    if current_admin(request):
        return RedirectResponse("/admin", status_code=303)
    return templates.TemplateResponse(request, "admin_login.html", {"error": None})


@router.post("/login")
def login_submit(request: Request, username: str = Form(...), password: str = Form(...)):
    """Check admin credentials and open a session."""
    row = db.query_one("SELECT * FROM admins WHERE username = ?", (username.strip(),))
    if row is None or not verify_password(password, row["password_hash"]):
        return templates.TemplateResponse(
            request, "admin_login.html", {"error": "Wrong username or password."}, status_code=401,
        )
    response = RedirectResponse("/admin", status_code=303)
    start_session(response, request, "admin", row["username"])
    return response


@router.post("/logout")
def logout(request: Request):
    """End the admin session."""
    response = RedirectResponse("/admin/login", status_code=303)
    end_session(response, request)
    return response


@router.get("")
def dashboard(request: Request, admin: str = Depends(require_admin)):
    """The admin overview: web users, known devices, follow requests."""
    users = db.query("SELECT * FROM web_users ORDER BY username")
    devices = db.query("SELECT * FROM devices ORDER BY last_seen DESC")
    follows = db.query(
        """
        SELECT f.*, u.username AS username
        FROM follows f JOIN web_users u ON u.id = f.web_user_id
        ORDER BY f.requested_at DESC
        """,
    )
    device_labels = {d["id"]: d["label"] for d in devices}
    return templates.TemplateResponse(
        request,
        "admin.html",
        {
            "admin": admin,
            "users": users,
            "devices": devices,
            "follows": follows,
            "device_labels": device_labels,
            "now": time.time(),
        },
    )


@router.post("/users")
def create_user(
    request: Request,
    username: str = Form(...),
    password: str = Form(...),
    _admin: str = Depends(require_admin),
):
    """Create a web user with an admin-set initial password (must be changed on first login)."""
    name = username.strip()
    if not name or len(password) < 8:
        return RedirectResponse("/admin?error=bad_user", status_code=303)
    if db.query_one("SELECT 1 FROM web_users WHERE username = ?", (name,)) is not None:
        return RedirectResponse("/admin?error=exists", status_code=303)
    db.execute(
        "INSERT INTO web_users (username, password_hash, must_change_password, created_at) "
        "VALUES (?, ?, 1, ?)",
        (name, hash_password(password), time.time()),
    )
    return RedirectResponse("/admin", status_code=303)


@router.post("/users/{user_id}/reset-password")
def reset_user_password(
    user_id: int,
    password: str = Form(...),
    _admin: str = Depends(require_admin),
):
    """Set a new initial password for a web user; forces a change on their next login."""
    if len(password) >= 8:
        db.execute(
            "UPDATE web_users SET password_hash = ?, must_change_password = 1 WHERE id = ?",
            (hash_password(password), user_id),
        )
    return RedirectResponse("/admin", status_code=303)


@router.post("/users/{user_id}/disable")
def disable_user(user_id: int, _admin: str = Depends(require_admin)):
    """Disable a web user: they can no longer sign in and their follows stop resolving."""
    db.execute("UPDATE web_users SET disabled = 1 WHERE id = ?", (user_id,))
    db.execute("DELETE FROM sessions WHERE kind = 'web' AND subject = ?", (str(user_id),))
    return RedirectResponse("/admin", status_code=303)


@router.post("/users/{user_id}/enable")
def enable_user(user_id: int, _admin: str = Depends(require_admin)):
    """Re-enable a disabled web user."""
    db.execute("UPDATE web_users SET disabled = 0 WHERE id = ?", (user_id,))
    return RedirectResponse("/admin", status_code=303)


@router.post("/follows/{follow_id}/revoke")
def revoke_follow(follow_id: int, _admin: str = Depends(require_admin)):
    """Admin override: force a follow to ``denied`` regardless of the device owner's choice."""
    db.execute(
        "UPDATE follows SET status = 'denied', decided_at = ? WHERE id = ?",
        (time.time(), follow_id),
    )
    return RedirectResponse("/admin", status_code=303)
