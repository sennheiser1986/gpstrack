#!/usr/bin/env python3
"""Command-line management for the sharing server's SQLite database.

Run through the venv python, e.g.::

    /opt/gpstrack-share/.venv/bin/python manage.py set-admin-password
    /opt/gpstrack-share/.venv/bin/python manage.py create-web-user alice

It uses the same ``GPSTRACK_SHARE_DB`` location as the server.
"""

from __future__ import annotations

import argparse
import getpass
import sys
import time

import db
from passwords import hash_password


def _prompt_new_password(what: str) -> str:
    """Prompt for a password twice and return it once they match and are long enough.

    :param what: label for the prompt, e.g. "admin".
    :return: the chosen password.
    """
    while True:
        first = getpass.getpass(f"New {what} password: ")
        if len(first) < 8:
            print("Too short — use at least 8 characters.", file=sys.stderr)
            continue
        if first != getpass.getpass("Repeat: "):
            print("Passwords did not match.", file=sys.stderr)
            continue
        return first


def set_admin_password(args: argparse.Namespace) -> None:
    """Create or update an admin account.

    :param args: parsed args with ``username``.
    """
    db.init_db()
    username = args.username.strip()
    password = _prompt_new_password("admin")
    db.execute(
        """
        INSERT INTO admins (username, password_hash, created_at) VALUES (?, ?, ?)
        ON CONFLICT(username) DO UPDATE SET password_hash = excluded.password_hash
        """,
        (username, hash_password(password), time.time()),
    )
    print(f"Admin '{username}' is set.")


def create_web_user(args: argparse.Namespace) -> None:
    """Create a web user with a prompted initial password (they must change it on first login).

    :param args: parsed args with ``username``.
    """
    db.init_db()
    username = args.username.strip()
    if db.query_one("SELECT 1 FROM web_users WHERE username = ?", (username,)):
        print(f"User '{username}' already exists.", file=sys.stderr)
        sys.exit(1)
    password = _prompt_new_password(f"'{username}'")
    db.execute(
        "INSERT INTO web_users (username, password_hash, must_change_password, created_at) "
        "VALUES (?, ?, 1, ?)",
        (username, hash_password(password), time.time()),
    )
    print(f"Web user '{username}' created (must change password on first login).")


def list_users(_args: argparse.Namespace) -> None:
    """Print all admins and web users."""
    db.init_db()
    print("Admins:")
    for row in db.query("SELECT username, created_at FROM admins ORDER BY username"):
        print(f"  {row['username']}")
    print("Web users:")
    for row in db.query("SELECT username, disabled, must_change_password FROM web_users ORDER BY username"):
        flags = []
        if row["disabled"]:
            flags.append("disabled")
        if row["must_change_password"]:
            flags.append("must-change-pw")
        print(f"  {row['username']}{'  (' + ', '.join(flags) + ')' if flags else ''}")


def list_devices(_args: argparse.Namespace) -> None:
    """Print every device that has ever synced."""
    db.init_db()
    for row in db.query("SELECT id, label, last_seen FROM devices ORDER BY last_seen DESC"):
        print(f"  {row['id']}  {row['label'] or '(no name)'}")


def main() -> None:
    """Parse arguments and dispatch to the chosen sub-command."""
    parser = argparse.ArgumentParser(description="GPS Track sharing server management")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("set-admin-password", help="create or reset an admin account")
    p.add_argument("username", nargs="?", default="admin")
    p.set_defaults(func=set_admin_password)

    p = sub.add_parser("create-web-user", help="create a web user")
    p.add_argument("username")
    p.set_defaults(func=create_web_user)

    sub.add_parser("list-users", help="list admins and web users").set_defaults(func=list_users)
    sub.add_parser("list-devices", help="list known devices").set_defaults(func=list_devices)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
