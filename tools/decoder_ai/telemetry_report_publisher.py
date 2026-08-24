#!/usr/bin/env python3
"""Race-safe publisher for generated ``telemetry-data`` reports.

The analyzer works from one immutable input revision. Before publishing, this
module verifies that the generated aggregate counts match that checkout, fetches
the current remote branch and refuses to publish if a newer commit touched
``fahrdaten/`` or ``diagnostics/``. The single generated report commit may be
rebased only over the exact app-provider review path. Pushes are never forced
and a bounded retry handles a writer winning between fetch and push.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence

from gatt_read_scan import load_rows


INPUT_PATHS = ("fahrdaten", "diagnostics")
SAFE_REMOTE_REBASE_PATH = "decoder-ai/app_provider_review_latest.json"
PROFILE_REPORT = Path("decoder-ai/decoder_profile.json")
LIBBLE_REPORT = Path("decoder-ai/libble_comparison.json")
POWER_REPORT = Path("decoder-ai/power_crosscheck.json")
GATT_REPORT = Path("decoder-ai/gatt_read_comparison.json")
MAX_ALLOWED_ATTEMPTS = 10


class PublishError(RuntimeError):
    """A safety invariant prevented report publication."""


@dataclass(frozen=True)
class FreshnessCounts:
    manifest_rides: int
    raw_rides: int
    live_rides: int
    diagnostic_bundles: int
    diagnostic_source_copies: int


def _run_git(
    repo: Path,
    *args: str,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["git", *args],
        cwd=repo,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if check and result.returncode != 0:
        command = "git " + " ".join(args)
        raise PublishError(f"{command} failed ({result.returncode}):\n{result.stdout.strip()}")
    return result


def _git_output(repo: Path, *args: str) -> str:
    return _run_git(repo, *args).stdout.strip()


def _load_object(repo: Path, relative: Path) -> dict[str, Any]:
    path = repo / relative
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PublishError(f"freshness report is unreadable: {relative}: {exc}") from exc
    if not isinstance(value, dict):
        raise PublishError(f"freshness report must contain a JSON object: {relative}")
    return value


def _integer_field(payload: dict[str, Any], field: str, report: Path) -> int:
    value = payload.get(field)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise PublishError(f"freshness field {report}:{field} must be a non-negative integer")
    return value


def _ride_directories(repo: Path) -> list[Path]:
    root = repo / "fahrdaten"
    if not root.is_dir():
        return []
    return sorted(path for path in root.rglob("Messfahrt_*") if path.is_dir())


def expected_freshness_counts(repo: Path) -> FreshnessCounts:
    rides = _ride_directories(repo)
    _, diagnostic_bundles, source_copies = load_rows(
        [repo / "diagnostics", repo / "fahrdaten"]
    )
    return FreshnessCounts(
        manifest_rides=sum((ride / "manifest.json").is_file() for ride in rides),
        raw_rides=sum((ride / "BLE_Rohdaten.csv").is_file() for ride in rides),
        live_rides=sum((ride / "Live_Telemetrie.csv").is_file() for ride in rides),
        diagnostic_bundles=len(diagnostic_bundles),
        diagnostic_source_copies=source_copies,
    )


def validate_report_freshness(repo: Path) -> FreshnessCounts:
    expected = expected_freshness_counts(repo)
    profile = _load_object(repo, PROFILE_REPORT)
    libble = _load_object(repo, LIBBLE_REPORT)
    power = _load_object(repo, POWER_REPORT)
    gatt = _load_object(repo, GATT_REPORT)

    libble_rides = libble.get("rides")
    if not isinstance(libble_rides, list):
        raise PublishError(f"freshness field {LIBBLE_REPORT}:rides must be a list")

    observed = FreshnessCounts(
        manifest_rides=_integer_field(profile, "rideCount", PROFILE_REPORT),
        raw_rides=len(libble_rides),
        live_rides=_integer_field(power, "rideCount", POWER_REPORT),
        diagnostic_bundles=_integer_field(gatt, "diagnosticBundles", GATT_REPORT),
        diagnostic_source_copies=_integer_field(gatt, "sourceBundleCopies", GATT_REPORT),
    )
    if observed != expected:
        raise PublishError(
            "FRESHNESS_CHECK_FAILED: generated counts do not match the checked-out inputs; "
            f"expected={expected}, observed={observed}"
        )
    print(
        "FRESHNESS_OK: "
        f"rides(manifest/raw/live)={expected.manifest_rides}/{expected.raw_rides}/{expected.live_rides}, "
        f"diagnostics(logical/source)={expected.diagnostic_bundles}/{expected.diagnostic_source_copies}"
    )
    return expected


def _is_ancestor(repo: Path, ancestor: str, descendant: str) -> bool:
    return _run_git(
        repo,
        "merge-base",
        "--is-ancestor",
        ancestor,
        descendant,
        check=False,
    ).returncode == 0


def _input_advance(repo: Path, input_revision: str, remote_revision: str) -> str | None:
    result = _run_git(
        repo,
        "rev-list",
        "--max-count=1",
        f"{input_revision}..{remote_revision}",
        "--",
        *INPUT_PATHS,
    )
    return result.stdout.strip() or None


def _changed_paths(repo: Path, old_revision: str, new_revision: str) -> list[str]:
    output = _git_output(repo, "diff", "--name-only", old_revision, new_revision)
    return [line for line in output.splitlines() if line]


def _intervening_changed_paths(repo: Path, old_revision: str, new_revision: str) -> list[str]:
    output = _git_output(
        repo,
        "log",
        "--format=",
        "--name-only",
        "--no-renames",
        "-m",
        f"{old_revision}..{new_revision}",
    )
    return sorted({line for line in output.splitlines() if line})


def _validate_safe_remote_rebase(
    repo: Path,
    local_base: str,
    remote_revision: str,
) -> list[str]:
    if not _is_ancestor(repo, local_base, remote_revision):
        raise PublishError(
            "REMOTE_REBASE_BLOCKED: remote history no longer descends from the "
            "last verified rebase base"
        )
    touched_paths = _intervening_changed_paths(repo, local_base, remote_revision)
    unexpected_paths = [
        path for path in touched_paths if path != SAFE_REMOTE_REBASE_PATH
    ]
    if not touched_paths or unexpected_paths:
        raise PublishError(
            "REMOTE_REBASE_BLOCKED: only the exact app-provider review path may be "
            f"rebased ({SAFE_REMOTE_REBASE_PATH}); touched_paths={touched_paths}"
        )
    return touched_paths


def _normalized_allowed_untracked_paths(values: Sequence[str]) -> set[str]:
    normalized: set[str] = set()
    for raw in values:
        value = raw.strip().replace("\\", "/").rstrip("/")
        parts = value.split("/")
        if not value or value == "." or value.startswith("/") or ".." in parts:
            raise PublishError(f"allowed untracked path must be a narrow relative path: {raw!r}")
        normalized.add(value)
    return normalized


def _unexpected_worktree_status(repo: Path, allowed_untracked_paths: Sequence[str]) -> list[str]:
    allowed = _normalized_allowed_untracked_paths(allowed_untracked_paths)
    status = _git_output(repo, "status", "--porcelain=v1", "--untracked-files=normal")
    unexpected: list[str] = []
    for line in status.splitlines():
        if line.startswith("?? ") and line[3:].strip().rstrip("/") in allowed:
            continue
        unexpected.append(line)
    return unexpected


def _validate_local_state(
    repo: Path,
    input_revision: str,
    allowed_untracked_paths: Sequence[str],
) -> None:
    _run_git(repo, "cat-file", "-e", f"{input_revision}^{{commit}}")
    head = _git_output(repo, "rev-parse", "HEAD")
    if not _is_ancestor(repo, input_revision, head):
        raise PublishError("input revision is not an ancestor of the generated report commit")
    local_commits = int(_git_output(repo, "rev-list", "--count", f"{input_revision}..{head}"))
    if local_commits != 1:
        raise PublishError(
            "publisher requires exactly one generated report commit above the input revision; "
            f"found {local_commits}"
        )
    local_paths = _changed_paths(repo, input_revision, head)
    unsafe = [
        path
        for path in local_paths
        if path == "fahrdaten"
        or path.startswith("fahrdaten/")
        or path == "diagnostics"
        or path.startswith("diagnostics/")
    ]
    if unsafe:
        raise PublishError(f"generated report commit unexpectedly changes telemetry inputs: {unsafe}")
    unexpected_status = _unexpected_worktree_status(repo, allowed_untracked_paths)
    if unexpected_status:
        raise PublishError(
            "publisher requires a clean worktree after the generated report commit; "
            f"unexpected status={unexpected_status}"
        )


def _fetch_remote_head(repo: Path, remote: str, branch: str) -> str:
    _run_git(repo, "fetch", "--no-tags", remote, f"refs/heads/{branch}")
    return _git_output(repo, "rev-parse", "FETCH_HEAD")


def _is_retryable_push_race(output: str) -> bool:
    normalized = output.lower()
    return (
        "non-fast-forward" in normalized
        or "fetch first" in normalized
        or "stale info" in normalized
    )


def publish(
    repo: Path,
    *,
    input_revision: str,
    remote: str,
    branch: str,
    max_attempts: int,
    allowed_untracked_paths: Sequence[str] = (),
) -> str:
    repo = repo.resolve()
    if not 1 <= max_attempts <= MAX_ALLOWED_ATTEMPTS:
        raise PublishError(
            f"max-attempts must be between 1 and {MAX_ALLOWED_ATTEMPTS}, got {max_attempts}"
        )
    _run_git(repo, "check-ref-format", f"refs/heads/{branch}")
    _run_git(repo, "remote", "get-url", remote)
    _validate_local_state(repo, input_revision, allowed_untracked_paths)

    local_base = input_revision
    for attempt in range(1, max_attempts + 1):
        remote_head = _fetch_remote_head(repo, remote, branch)
        if not _is_ancestor(repo, input_revision, remote_head):
            raise PublishError(
                "remote telemetry-data history no longer descends from the analyzed input revision"
            )

        input_commit = _input_advance(repo, input_revision, remote_head)
        if input_commit:
            print(
                "SUPERSEDED: remote fahrdaten/ or diagnostics/ advanced after "
                f"{input_revision}; newer trigger owns recomputation (first input commit {input_commit})."
            )
            return "superseded"

        head = _git_output(repo, "rev-parse", "HEAD")
        if not _is_ancestor(repo, remote_head, head):
            changed = _validate_safe_remote_rebase(repo, local_base, remote_head)
            print(
                f"REMOTE_SAFE_PROVIDER_ADVANCE: rebasing generated report over {remote_head}; "
                f"paths={changed}"
            )
            rebase = _run_git(
                repo,
                "rebase",
                "--onto",
                remote_head,
                local_base,
                check=False,
            )
            if rebase.returncode != 0:
                _run_git(repo, "rebase", "--abort", check=False)
                raise PublishError(
                    "safe rebase over non-input writer failed; no push attempted:\n"
                    + rebase.stdout.strip()
                )
            local_base = remote_head

        validate_report_freshness(repo)

        push = _run_git(
            repo,
            "push",
            "--porcelain",
            remote,
            f"HEAD:refs/heads/{branch}",
            check=False,
        )
        if push.returncode == 0:
            published_head = _git_output(repo, "rev-parse", "HEAD")
            print(f"PUBLISHED: {published_head} on attempt {attempt}/{max_attempts} (no force).")
            return "published"
        if attempt == max_attempts or not _is_retryable_push_race(push.stdout):
            raise PublishError(
                f"git push failed after {attempt}/{max_attempts} attempt(s):\n{push.stdout.strip()}"
            )
        print(f"PUSH_RACE: attempt {attempt}/{max_attempts} lost; refetching before retry.")

    raise AssertionError("bounded publish loop exited unexpectedly")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--input-revision", required=True)
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--branch", default="telemetry-data")
    parser.add_argument("--max-attempts", type=int, default=3)
    parser.add_argument("--allow-untracked-path", action="append", default=[])
    args = parser.parse_args()
    try:
        publish(
            args.repo,
            input_revision=args.input_revision,
            remote=args.remote,
            branch=args.branch,
            max_attempts=args.max_attempts,
            allowed_untracked_paths=args.allow_untracked_path,
        )
    except PublishError as exc:
        print(f"PUBLISH_BLOCKED: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
