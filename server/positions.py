"""The in-memory store of the latest position per device.

This is deliberately *not* in the database: a position is transient and is forgotten a couple
of minutes after the device stops broadcasting.
"""

from __future__ import annotations

import time
from threading import Lock

# A reported position is dropped this many seconds after it was received, so a device that
# stops broadcasting quietly disappears from every map.
POSITION_TTL_SECONDS = 120

_lock = Lock()

# device id -> {"lat": float, "lon": float, "time": float, "label": str, "stored": float}
_positions: dict[str, dict[str, float | str]] = {}


def _prune(now: float) -> None:
    """Drop every stored position older than :data:`POSITION_TTL_SECONDS`.

    :param now: current time in unix seconds.
    """
    stale = [
        key for key, value in _positions.items()
        if now - float(value["stored"]) > POSITION_TTL_SECONDS
    ]
    for key in stale:
        del _positions[key]


def store(device_id: str, lat: float, lon: float, recorded_at: float | None) -> None:
    """Record a device's latest position.

    :param device_id: the broadcasting device's id.
    :param lat: latitude in degrees.
    :param lon: longitude in degrees.
    :param recorded_at: when the fix was taken, unix seconds; defaults to now.
    """
    now = time.time()
    with _lock:
        _positions[device_id] = {
            "lat": lat,
            "lon": lon,
            "time": recorded_at if recorded_at is not None else now,
            "label": _positions.get(device_id, {}).get("label", ""),
            "stored": now,
        }


def set_label(device_id: str, label: str) -> None:
    """Attach the device's friendly name to its stored position, if it has one.

    :param device_id: the device id.
    :param label: the display name.
    """
    with _lock:
        entry = _positions.get(device_id)
        if entry is not None:
            entry["label"] = label


def forget(device_id: str) -> None:
    """Drop a device's stored position (used when it stops broadcasting).

    :param device_id: the device id.
    """
    with _lock:
        _positions.pop(device_id, None)


def latest(device_id: str) -> dict[str, float | str] | None:
    """Return a device's latest fresh position, or None.

    :param device_id: the device id.
    :return: ``{lat, lon, time, label}`` or None when unknown or stale.
    """
    now = time.time()
    with _lock:
        _prune(now)
        entry = _positions.get(device_id)
        if entry is None:
            return None
        return {"lat": entry["lat"], "lon": entry["lon"], "time": entry["time"], "label": entry["label"]}


def is_online(device_id: str) -> bool:
    """Whether a device currently has a fresh position.

    :param device_id: the device id.
    :return: True when a non-stale position is stored.
    """
    return latest(device_id) is not None


def tracked_count() -> int:
    """Number of devices with a fresh position, for the health probe.

    :return: the count after pruning.
    """
    now = time.time()
    with _lock:
        _prune(now)
        return len(_positions)
