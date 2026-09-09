"""Password hashing for the sharing server, using only the standard library.

Hashes are stored as a single self-describing string:

    scrypt$<n>$<r>$<p>$<salt_b64>$<hash_b64>
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import os

# scrypt work factors. n must be a power of two; these are a reasonable interactive cost.
_N = 2 ** 15
_R = 8
_P = 1
_DK_LEN = 32
_SALT_LEN = 16


def hash_password(password: str) -> str:
    """Hash a plaintext password.

    :param password: the plaintext.
    :return: a ``scrypt$...`` string safe to store.
    """
    salt = os.urandom(_SALT_LEN)
    derived = hashlib.scrypt(
        password.encode("utf-8"), salt=salt, n=_N, r=_R, p=_P, dklen=_DK_LEN,
        maxmem=128 * _N * _R * 2,
    )
    return "scrypt${}${}${}${}${}".format(
        _N, _R, _P,
        base64.b64encode(salt).decode("ascii"),
        base64.b64encode(derived).decode("ascii"),
    )


def verify_password(password: str, stored: str) -> bool:
    """Check a plaintext password against a stored hash.

    :param password: the plaintext to check.
    :param stored: a string produced by :func:`hash_password`.
    :return: True when the password matches.
    """
    try:
        scheme, n_str, r_str, p_str, salt_b64, hash_b64 = stored.split("$")
        if scheme != "scrypt":
            return False
        n, r, p = int(n_str), int(r_str), int(p_str)
        salt = base64.b64decode(salt_b64)
        expected = base64.b64decode(hash_b64)
    except (ValueError, TypeError):
        return False

    derived = hashlib.scrypt(
        password.encode("utf-8"), salt=salt, n=n, r=r, p=p, dklen=len(expected),
        maxmem=128 * n * r * 2,
    )
    return hmac.compare_digest(derived, expected)
