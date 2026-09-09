"""Cookie-session auth for the admin panel and the web-follow pages.

A session is an opaque 256-bit token stored in the ``sessions`` table and echoed to the browser
in an ``HttpOnly`` cookie. There are two kinds: ``admin`` (subject = admin username) and
``web`` (subject = ``web_users.id`` as text).
"""

from __future__ import annotations

import os
import secrets
import time

from fastapi import Request
from fastapi.responses import RedirectResponse, Response

import db

COOKIE_NAME = "session"
SESSION_TTL_SECONDS = 30 * 24 * 3600


def _secure_cookies(request: Request) -> bool:
    """Whether the session cookie should carry the ``Secure`` flag.

    :param request: the incoming request.
    :return: True behind TLS (either seen directly or via ``X-Forwarded-Proto``) or when
        ``GPSTRACK_SHARE_SECURE_COOKIES`` is set.
    """
    if os.environ.get("GPSTRACK_SHARE_SECURE_COOKIES", "") not in ("", "0", "false"):
        return True
    if request.headers.get("x-forwarded-proto", "").lower() == "https":
        return True
    return request.url.scheme == "https"


def start_session(response: Response, request: Request, kind: str, subject: str) -> None:
    """Create a session row and set the cookie on ``response``.

    :param response: the response the ``Set-Cookie`` header is added to.
    :param request: the incoming request, for the Secure decision.
    :param kind: ``"admin"`` or ``"web"``.
    :param subject: admin username, or web user id as text.
    """
    token = secrets.token_urlsafe(32)
    now = time.time()
    db.execute(
        "INSERT INTO sessions (token, kind, subject, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
        (token, kind, subject, now, now + SESSION_TTL_SECONDS),
    )
    response.set_cookie(
        COOKIE_NAME, token,
        max_age=SESSION_TTL_SECONDS,
        httponly=True,
        samesite="lax",
        secure=_secure_cookies(request),
        path="/",
    )


def end_session(response: Response, request: Request) -> None:
    """Delete the caller's session row and clear the cookie.

    :param response: the response the cookie is cleared on.
    :param request: the incoming request holding the cookie.
    """
    token = request.cookies.get(COOKIE_NAME)
    if token:
        db.execute("DELETE FROM sessions WHERE token = ?", (token,))
    response.delete_cookie(COOKIE_NAME, path="/")


def _session_row(request: Request, kind: str):
    """Look up a live session of the given kind for the request's cookie.

    :param request: the incoming request.
    :param kind: ``"admin"`` or ``"web"``.
    :return: the session row, or None.
    """
    token = request.cookies.get(COOKIE_NAME)
    if not token:
        return None
    row = db.query_one(
        "SELECT * FROM sessions WHERE token = ? AND kind = ? AND expires_at > ?",
        (token, kind, time.time()),
    )
    return row


def current_admin(request: Request) -> str | None:
    """The username of the signed-in admin, or None.

    :param request: the incoming request.
    :return: the admin username, or None.
    """
    row = _session_row(request, "admin")
    if row is None:
        return None
    if db.query_one("SELECT 1 FROM admins WHERE username = ?", (row["subject"],)) is None:
        return None
    return row["subject"]


def current_web_user(request: Request):
    """The signed-in web user row, or None (also None when the account is disabled).

    :param request: the incoming request.
    :return: the ``web_users`` row, or None.
    """
    row = _session_row(request, "web")
    if row is None:
        return None
    user = db.query_one("SELECT * FROM web_users WHERE id = ?", (int(row["subject"]),))
    if user is None or user["disabled"]:
        return None
    return user


def require_admin(request: Request):
    """FastAPI dependency: the signed-in admin username, or a redirect to the admin login.

    :param request: the incoming request.
    :return: the admin username.
    :raises RedirectException: when not signed in.
    """
    username = current_admin(request)
    if username is None:
        raise RedirectException("/admin/login")
    return username


def require_web_user(request: Request):
    """FastAPI dependency: the signed-in web user row, or a redirect to the login page.

    ``/api/*`` paths get a 401 instead of a redirect so fetch() callers see a clean error.

    :param request: the incoming request.
    :return: the ``web_users`` row.
    :raises RedirectException | HTTPException: when not signed in.
    """
    user = current_web_user(request)
    if user is None:
        if request.url.path.startswith("/api/"):
            from fastapi import HTTPException
            raise HTTPException(status_code=401, detail="Sign in required")
        raise RedirectException("/login")
    return user


class RedirectException(Exception):
    """Raised by the auth dependencies to send the browser to a login page."""

    def __init__(self, location: str) -> None:
        super().__init__(location)
        self.location = location


def redirect_exception_handler(_request: Request, exc: RedirectException) -> RedirectResponse:
    """Turn a :class:`RedirectException` into a 303 redirect.

    :param _request: unused.
    :param exc: the raised exception carrying the target location.
    :return: the redirect response.
    """
    return RedirectResponse(exc.location, status_code=303)
