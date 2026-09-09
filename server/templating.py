"""Shared Jinja2 template environment for the admin panel and the web-follow pages."""

from __future__ import annotations

import datetime as _dt
import os
import time

from fastapi.templating import Jinja2Templates

_TEMPLATES_DIR = os.path.join(os.path.dirname(__file__), "templates")

templates = Jinja2Templates(directory=_TEMPLATES_DIR)


def _ago(epoch_seconds: float | None) -> str:
    """Render a coarse "time ago" string for a unix timestamp.

    :param epoch_seconds: the past time, or None/0.
    :return: e.g. "just now", "3 min ago", "2 h ago", "never".
    """
    if not epoch_seconds:
        return "never"
    seconds = max(0, int(time.time() - float(epoch_seconds)))
    if seconds < 15:
        return "just now"
    if seconds < 90:
        return "1 min ago"
    if seconds < 3600:
        return f"{seconds // 60} min ago"
    if seconds < 86400:
        return f"{seconds // 3600} h ago"
    return f"{seconds // 86400} d ago"


def _dt_str(epoch_seconds: float | None) -> str:
    """Render a unix timestamp as a local date-time, or an em dash when missing.

    :param epoch_seconds: the time, or None.
    :return: ``YYYY-MM-DD HH:MM`` or ``—``.
    """
    if not epoch_seconds:
        return "—"
    return _dt.datetime.fromtimestamp(float(epoch_seconds)).strftime("%Y-%m-%d %H:%M")


templates.env.filters["ago"] = _ago
templates.env.filters["dt"] = _dt_str
