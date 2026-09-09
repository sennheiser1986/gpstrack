"""SQLite storage for the sharing server: admins, web users, known devices and follow requests.

Live positions are *not* stored here; they stay in the in-memory dict in ``main.py`` with its
short TTL. This database only holds the things that must survive a restart.

The database path comes from the ``GPSTRACK_SHARE_DB`` environment variable, defaulting to the
systemd ``StateDirectory`` location. One connection is shared for the process, guarded by a
lock, which is plenty for this workload.
"""

from __future__ import annotations

import os
import sqlite3
import threading
import time

DB_PATH = os.environ.get("GPSTRACK_SHARE_DB", "/var/lib/gpstrack-share/gpstrack-share.db")

_lock = threading.Lock()
_connection: sqlite3.Connection | None = None

_SCHEMA = """
CREATE TABLE IF NOT EXISTS admins (
    username      TEXT PRIMARY KEY,
    password_hash TEXT NOT NULL,
    created_at    REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS web_users (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    username             TEXT UNIQUE NOT NULL,
    password_hash        TEXT NOT NULL,
    must_change_password INTEGER NOT NULL DEFAULT 1,
    disabled             INTEGER NOT NULL DEFAULT 0,
    created_at           REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
    id         TEXT PRIMARY KEY,
    label      TEXT NOT NULL DEFAULT '',
    first_seen REAL NOT NULL,
    last_seen  REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS follows (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    web_user_id  INTEGER NOT NULL REFERENCES web_users(id) ON DELETE CASCADE,
    device_id    TEXT NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'denied')),
    requested_at REAL NOT NULL,
    decided_at   REAL,
    UNIQUE (web_user_id, device_id)
);

CREATE TABLE IF NOT EXISTS sessions (
    token      TEXT PRIMARY KEY,
    kind       TEXT NOT NULL CHECK (kind IN ('admin', 'web')),
    subject    TEXT NOT NULL,
    created_at REAL NOT NULL,
    expires_at REAL NOT NULL
);
"""


def connect() -> sqlite3.Connection:
    """Return the shared database connection, opening it on first use.

    :return: the process-wide :class:`sqlite3.Connection`.
    """
    global _connection
    if _connection is None:
        directory = os.path.dirname(DB_PATH)
        if directory:
            os.makedirs(directory, exist_ok=True)
        _connection = sqlite3.connect(DB_PATH, check_same_thread=False)
        _connection.row_factory = sqlite3.Row
        _connection.execute("PRAGMA foreign_keys = ON")
    return _connection


def init_db() -> None:
    """Create any missing tables. Safe to call on every startup."""
    with _lock:
        connection = connect()
        connection.executescript(_SCHEMA)
        connection.commit()


def query(sql: str, params: tuple = ()) -> list[sqlite3.Row]:
    """Run a read query.

    :param sql: the SQL statement.
    :param params: bound parameters.
    :return: all rows.
    """
    with _lock:
        return connect().execute(sql, params).fetchall()


def query_one(sql: str, params: tuple = ()) -> sqlite3.Row | None:
    """Run a read query expecting at most one row.

    :param sql: the SQL statement.
    :param params: bound parameters.
    :return: the first row, or None.
    """
    with _lock:
        return connect().execute(sql, params).fetchone()


def execute(sql: str, params: tuple = ()) -> int:
    """Run a write statement and commit.

    :param sql: the SQL statement.
    :param params: bound parameters.
    :return: ``lastrowid`` for inserts, otherwise the affected row count.
    """
    with _lock:
        connection = connect()
        cursor = connection.execute(sql, params)
        connection.commit()
        return cursor.lastrowid if cursor.lastrowid else cursor.rowcount


def prune_sessions() -> None:
    """Delete expired sessions."""
    execute("DELETE FROM sessions WHERE expires_at < ?", (time.time(),))
