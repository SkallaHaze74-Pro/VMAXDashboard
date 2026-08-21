"""Single allow-list for RAW rows that may enter semantic/live analyzers."""

from __future__ import annotations

from collections.abc import Mapping

ACCEPTED_LIVE_ORIGIN = "NOTIFICATION"


def is_accepted_live_notification(row: Mapping[str, object]) -> bool:
    """Fail closed: missing, rejected, diagnostic and future origins are quarantined."""
    return str(row.get("origin") or "").strip().upper() == ACCEPTED_LIVE_ORIGIN
