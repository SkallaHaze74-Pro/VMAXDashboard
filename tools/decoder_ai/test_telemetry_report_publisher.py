from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("telemetry_report_publisher.py")
REAL_GIT = shutil.which("git")


@unittest.skipUnless(REAL_GIT, "git is required for publisher integration tests")
class TelemetryReportPublisherTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.remote = self.root / "remote.git"
        self.seed = self.root / "seed"
        self.analyzer = self.root / "analyzer"
        self.writer = self.root / "writer"

        self.git(self.root, "init", "--bare", "--initial-branch=telemetry-data", str(self.remote))
        self.git(self.root, "init", "--initial-branch=telemetry-data", str(self.seed))
        self.configure_identity(self.seed)
        self.write_fresh_fixture(self.seed)
        self.git(self.seed, "add", ".")
        self.git(self.seed, "commit", "-m", "seed telemetry")
        self.git(self.seed, "remote", "add", "origin", str(self.remote))
        self.git(self.seed, "push", "-u", "origin", "telemetry-data")
        self.base_revision = self.git(self.seed, "rev-parse", "HEAD").stdout.strip()

        self.git(self.root, "clone", "--branch", "telemetry-data", str(self.remote), str(self.analyzer))
        self.git(self.root, "clone", "--branch", "telemetry-data", str(self.remote), str(self.writer))
        self.configure_identity(self.analyzer)
        self.configure_identity(self.writer)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def git(
        self,
        cwd: Path,
        *args: str,
        check: bool = True,
        env: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [str(REAL_GIT), *args],
            cwd=cwd,
            env=env,
            check=check,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    def configure_identity(self, repo: Path) -> None:
        self.git(repo, "config", "user.name", "Publisher Test")
        self.git(repo, "config", "user.email", "publisher@example.invalid")

    def write_json(self, repo: Path, relative: str, payload: object) -> None:
        path = repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload) + "\n", encoding="utf-8")

    def write_fresh_fixture(self, repo: Path) -> None:
        ride = repo / "fahrdaten" / "2026-01-01" / "Messfahrt_1"
        ride.mkdir(parents=True)
        (ride / "manifest.json").write_text("{}\n", encoding="utf-8")
        (ride / "BLE_Rohdaten.csv").write_text("channel;hex\n", encoding="utf-8")
        (ride / "Live_Telemetrie.csv").write_text("source_channel\n", encoding="utf-8")
        self.write_json(repo, "decoder-ai/decoder_profile.json", {"rideCount": 1})
        self.write_json(repo, "decoder-ai/libble_comparison.json", {"rides": [{}]})
        self.write_json(repo, "decoder-ai/power_crosscheck.json", {"rideCount": 1})
        self.write_json(
            repo,
            "decoder-ai/gatt_read_comparison.json",
            {"diagnosticBundles": 0, "sourceBundleCopies": 0},
        )
        (repo / "decoder-ai" / "analysis_report.md").write_text("seed\n", encoding="utf-8")

    def commit_analyzer_report(self, text: str = "fresh aggregate\n") -> str:
        (self.analyzer / "decoder-ai" / "analysis_report.md").write_text(text, encoding="utf-8")
        self.git(self.analyzer, "add", "decoder-ai/analysis_report.md")
        self.git(self.analyzer, "commit", "-m", "generated reports")
        return self.git(self.analyzer, "rev-parse", "HEAD").stdout.strip()

    def run_publisher(
        self,
        *,
        repo: Path | None = None,
        env: dict[str, str] | None = None,
        max_attempts: int = 3,
        extra_args: tuple[str, ...] = (),
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--repo",
                str(repo or self.analyzer),
                "--input-revision",
                self.base_revision,
                "--remote",
                "origin",
                "--branch",
                "telemetry-data",
                "--max-attempts",
                str(max_attempts),
                *extra_args,
            ],
            cwd=repo or self.analyzer,
            env=env,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )

    def remote_head(self) -> str:
        return self.git(self.root, "--git-dir", str(self.remote), "rev-parse", "refs/heads/telemetry-data").stdout.strip()

    def remote_file(self, relative: str) -> str:
        return self.git(
            self.root,
            "--git-dir",
            str(self.remote),
            "show",
            f"refs/heads/telemetry-data:{relative}",
        ).stdout

    def race_writer_env(self, late_writer: Path) -> dict[str, str]:
        wrapper_dir = self.root / "git-wrapper"
        wrapper_dir.mkdir()
        state = self.root / "race-fired"
        wrapper = wrapper_dir / "git"
        wrapper.write_text(
            "#!/bin/sh\n"
            'if [ "$1" = "push" ] && [ ! -e "$RACE_STATE" ]; then\n'
            '  : > "$RACE_STATE"\n'
            '  "$REAL_GIT_BIN" -C "$LATE_WRITER_REPO" push origin HEAD:telemetry-data || exit $?\n'
            "fi\n"
            'exec "$REAL_GIT_BIN" "$@"\n',
            encoding="utf-8",
        )
        wrapper.chmod(0o755)
        env = os.environ.copy()
        env.update(
            {
                "PATH": f"{wrapper_dir}{os.pathsep}{env.get('PATH', '')}",
                "RACE_STATE": str(state),
                "REAL_GIT_BIN": str(REAL_GIT),
                "LATE_WRITER_REPO": str(late_writer),
            }
        )
        return env

    def test_allows_explicit_nested_analyzer_checkout_only(self) -> None:
        self.commit_analyzer_report("aggregate with nested analyzer\n")
        analyzer_logic = self.analyzer / ".decoder-main"
        self.git(self.analyzer, "init", "--initial-branch=main", str(analyzer_logic))
        self.configure_identity(analyzer_logic)
        (analyzer_logic / "analyzer.py").write_text("# current main logic\n", encoding="utf-8")
        self.git(analyzer_logic, "add", "analyzer.py")
        self.git(analyzer_logic, "commit", "-m", "analyzer logic")

        result = self.run_publisher(
            extra_args=("--allow-untracked-path", ".decoder-main/"),
        )

        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(
            self.remote_file("decoder-ai/analysis_report.md"),
            "aggregate with nested analyzer\n",
        )

    def test_nested_allowlist_does_not_allow_other_dirty_paths(self) -> None:
        self.commit_analyzer_report()
        (self.analyzer / ".decoder-main").mkdir()
        (self.analyzer / ".decoder-main" / "analyzer.py").write_text("# allowed\n", encoding="utf-8")
        (self.analyzer / "unexpected.tmp").write_text("must block\n", encoding="utf-8")

        result = self.run_publisher(
            extra_args=("--allow-untracked-path", ".decoder-main/"),
        )

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("unexpected.tmp", result.stdout)
        self.assertEqual(self.remote_head(), self.base_revision)

    def test_rebases_report_over_exact_app_provider_writer(self) -> None:
        report_commit = self.commit_analyzer_report()
        self.write_json(self.writer, "decoder-ai/app_provider_review_latest.json", {"model": "gemini"})
        self.git(self.writer, "add", "decoder-ai/app_provider_review_latest.json")
        self.git(self.writer, "commit", "-m", "app provider review")
        writer_commit = self.git(self.writer, "rev-parse", "HEAD").stdout.strip()
        self.git(self.writer, "push", "origin", "HEAD:telemetry-data")

        result = self.run_publisher()

        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("REMOTE_SAFE_PROVIDER_ADVANCE:", result.stdout)
        self.assertNotEqual(self.remote_head(), report_commit)
        self.assertEqual(self.remote_file("decoder-ai/analysis_report.md"), "fresh aggregate\n")
        self.assertEqual(
            self.remote_file("decoder-ai/app_provider_review_latest.json"),
            '{"model": "gemini"}\n',
        )
        self.assertTrue(
            self.git(
                self.root,
                "--git-dir",
                str(self.remote),
                "merge-base",
                "--is-ancestor",
                writer_commit,
                "refs/heads/telemetry-data",
                check=False,
            ).returncode
            == 0
        )

    def test_blocks_intervening_stale_core_report_before_rebase(self) -> None:
        self.commit_analyzer_report()
        self.write_json(self.writer, "decoder-ai/decoder_profile.json", {"rideCount": 0})
        self.git(self.writer, "add", "decoder-ai/decoder_profile.json")
        self.git(self.writer, "commit", "-m", "intervening stale generated report")
        writer_commit = self.git(self.writer, "rev-parse", "HEAD").stdout.strip()
        self.git(self.writer, "push", "origin", "HEAD:telemetry-data")

        result = self.run_publisher()

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("REMOTE_REBASE_BLOCKED", result.stdout)
        self.assertIn("decoder-ai/decoder_profile.json", result.stdout)
        self.assertEqual(self.remote_head(), writer_commit)
        self.assertEqual(self.remote_file("decoder-ai/analysis_report.md"), "seed\n")

    def test_blocks_same_count_core_report_mutation(self) -> None:
        self.commit_analyzer_report()
        self.write_json(
            self.writer,
            "decoder-ai/decoder_profile.json",
            {"rideCount": 1, "mutatedByOtherWriter": True},
        )
        self.git(self.writer, "add", "decoder-ai/decoder_profile.json")
        self.git(self.writer, "commit", "-m", "mutate core report without changing count")
        writer_commit = self.git(self.writer, "rev-parse", "HEAD").stdout.strip()
        self.git(self.writer, "push", "origin", "HEAD:telemetry-data")

        result = self.run_publisher()

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("REMOTE_REBASE_BLOCKED", result.stdout)
        self.assertIn("decoder-ai/decoder_profile.json", result.stdout)
        self.assertEqual(self.remote_head(), writer_commit)
        self.assertEqual(self.remote_file("decoder-ai/analysis_report.md"), "seed\n")

    def test_blocks_arbitrary_tool_workflow_and_report_paths(self) -> None:
        self.commit_analyzer_report()
        arbitrary_paths = {
            "tools/decoder_ai/other_writer.py": "# unrelated tool mutation\n",
            ".github/workflows/other-writer.yml": "name: unrelated workflow mutation\n",
            "decoder-ai/other_writer_report.md": "unrelated report mutation\n",
        }
        for relative, content in arbitrary_paths.items():
            path = self.writer / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        self.git(self.writer, "add", *arbitrary_paths)
        self.git(self.writer, "commit", "-m", "unrelated non-input writer")
        writer_commit = self.git(self.writer, "rev-parse", "HEAD").stdout.strip()
        self.git(self.writer, "push", "origin", "HEAD:telemetry-data")

        result = self.run_publisher()

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("REMOTE_REBASE_BLOCKED", result.stdout)
        for relative in arbitrary_paths:
            self.assertIn(relative, result.stdout)
        self.assertEqual(self.remote_head(), writer_commit)
        self.assertEqual(self.remote_file("decoder-ai/analysis_report.md"), "seed\n")

    def test_blocks_intervening_path_even_when_later_commit_reverts_it(self) -> None:
        self.commit_analyzer_report()
        transient = self.writer / "tools" / "decoder_ai" / "transient_writer.py"
        transient.parent.mkdir(parents=True, exist_ok=True)
        transient.write_text("# transient mutation\n", encoding="utf-8")
        self.git(self.writer, "add", "tools/decoder_ai/transient_writer.py")
        self.git(self.writer, "commit", "-m", "add transient unrelated path")
        transient.unlink()
        self.git(self.writer, "add", "tools/decoder_ai/transient_writer.py")
        self.git(self.writer, "commit", "-m", "remove transient unrelated path")
        writer_commit = self.git(self.writer, "rev-parse", "HEAD").stdout.strip()
        self.git(self.writer, "push", "origin", "HEAD:telemetry-data")

        result = self.run_publisher()

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("REMOTE_REBASE_BLOCKED", result.stdout)
        self.assertIn("tools/decoder_ai/transient_writer.py", result.stdout)
        self.assertEqual(self.remote_head(), writer_commit)
        self.assertEqual(self.remote_file("decoder-ai/analysis_report.md"), "seed\n")

    def test_supersedes_report_when_remote_inputs_advance(self) -> None:
        self.commit_analyzer_report()
        new_ride = self.writer / "fahrdaten" / "2026-01-02" / "Messfahrt_2"
        new_ride.mkdir(parents=True)
        (new_ride / "manifest.json").write_text("{}\n", encoding="utf-8")
        self.git(self.writer, "add", "fahrdaten")
        self.git(self.writer, "commit", "-m", "new ride input")
        writer_commit = self.git(self.writer, "rev-parse", "HEAD").stdout.strip()
        self.git(self.writer, "push", "origin", "HEAD:telemetry-data")

        result = self.run_publisher()

        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("SUPERSEDED:", result.stdout)
        self.assertEqual(self.remote_head(), writer_commit)
        self.assertEqual(self.remote_file("decoder-ai/analysis_report.md"), "seed\n")

    def test_remote_input_advance_wins_over_obsolete_local_freshness(self) -> None:
        self.write_json(self.analyzer, "decoder-ai/decoder_profile.json", {"rideCount": 0})
        (self.analyzer / "decoder-ai" / "analysis_report.md").write_text("obsolete\n", encoding="utf-8")
        self.git(self.analyzer, "add", "decoder-ai")
        self.git(self.analyzer, "commit", "-m", "obsolete generated reports")
        new_ride = self.writer / "fahrdaten" / "2026-01-02" / "Messfahrt_2"
        new_ride.mkdir(parents=True)
        (new_ride / "manifest.json").write_text("{}\n", encoding="utf-8")
        self.git(self.writer, "add", "fahrdaten")
        self.git(self.writer, "commit", "-m", "newer ride owns recomputation")
        writer_commit = self.git(self.writer, "rev-parse", "HEAD").stdout.strip()
        self.git(self.writer, "push", "origin", "HEAD:telemetry-data")

        result = self.run_publisher()

        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("SUPERSEDED:", result.stdout)
        self.assertNotIn("FRESHNESS_CHECK_FAILED", result.stdout)
        self.assertEqual(self.remote_head(), writer_commit)

    def test_blocks_stale_generated_ride_counts(self) -> None:
        self.write_json(self.analyzer, "decoder-ai/decoder_profile.json", {"rideCount": 0})
        (self.analyzer / "decoder-ai" / "analysis_report.md").write_text("stale aggregate\n", encoding="utf-8")
        self.git(self.analyzer, "add", "decoder-ai")
        self.git(self.analyzer, "commit", "-m", "stale generated reports")

        result = self.run_publisher()

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("FRESHNESS_CHECK_FAILED", result.stdout)
        self.assertEqual(self.remote_head(), self.base_revision)
        self.assertEqual(self.remote_file("decoder-ai/analysis_report.md"), "seed\n")

    def test_blocks_stale_generated_diagnostic_counts(self) -> None:
        diagnostic = self.analyzer / "diagnostics" / "2026-01-01" / "DeepRead_1"
        diagnostic.mkdir(parents=True)
        (diagnostic / "Gatt_READ_Diagnose.csv").write_text(
            "scan_id;timestamp_ms;record_kind;callback_received;service_uuid;"
            "characteristic_uuid;status;length;hex;payload_valid\n"
            "scan-1;1;GATT_READ_CALLBACK;true;180A;2A00;0;1;00;true\n",
            encoding="utf-8",
        )
        (diagnostic / "manifest.json").write_text('{"scan_id":"scan-1"}\n', encoding="utf-8")
        self.git(self.analyzer, "add", "diagnostics")
        self.git(self.analyzer, "commit", "-m", "diagnostic input")
        self.git(self.analyzer, "push", "origin", "HEAD:telemetry-data")
        self.base_revision = self.git(self.analyzer, "rev-parse", "HEAD").stdout.strip()
        (self.analyzer / "decoder-ai" / "analysis_report.md").write_text(
            "diagnostic aggregate not refreshed\n", encoding="utf-8"
        )
        self.git(self.analyzer, "add", "decoder-ai/analysis_report.md")
        self.git(self.analyzer, "commit", "-m", "stale diagnostic report")

        result = self.run_publisher()

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("FRESHNESS_CHECK_FAILED", result.stdout)
        self.assertIn("diagnostic_bundles=1", result.stdout)
        self.assertEqual(
            self.remote_file("decoder-ai/gatt_read_comparison.json"),
            '{"diagnosticBundles": 0, "sourceBundleCopies": 0}\n',
        )

    def test_retries_when_exact_app_provider_writer_wins_between_fetch_and_push(self) -> None:
        self.commit_analyzer_report("aggregate after retry\n")
        late_writer = self.root / "late-writer"
        self.git(self.root, "clone", "--branch", "telemetry-data", str(self.remote), str(late_writer))
        self.configure_identity(late_writer)
        self.write_json(late_writer, "decoder-ai/app_provider_review_latest.json", {"late": True})
        self.git(late_writer, "add", "decoder-ai/app_provider_review_latest.json")
        self.git(late_writer, "commit", "-m", "late app writer")

        env = self.race_writer_env(late_writer)

        result = self.run_publisher(env=env)

        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("PUSH_RACE: attempt 1/3 lost", result.stdout)
        self.assertIn("REMOTE_SAFE_PROVIDER_ADVANCE:", result.stdout)
        self.assertIn("PUBLISHED:", result.stdout)
        self.assertIn("attempt 2/3", result.stdout)
        self.assertEqual(self.remote_file("decoder-ai/analysis_report.md"), "aggregate after retry\n")
        self.assertEqual(
            self.remote_file("decoder-ai/app_provider_review_latest.json"),
            '{"late": true}\n',
        )

    def test_supersedes_when_input_writer_wins_between_fetch_and_push(self) -> None:
        self.commit_analyzer_report("must not publish\n")
        late_writer = self.root / "late-input-writer"
        self.git(self.root, "clone", "--branch", "telemetry-data", str(self.remote), str(late_writer))
        self.configure_identity(late_writer)
        new_ride = late_writer / "fahrdaten" / "2026-01-02" / "Messfahrt_2"
        new_ride.mkdir(parents=True)
        (new_ride / "manifest.json").write_text("{}\n", encoding="utf-8")
        self.git(late_writer, "add", "fahrdaten")
        self.git(late_writer, "commit", "-m", "late ride input")
        input_commit = self.git(late_writer, "rev-parse", "HEAD").stdout.strip()

        result = self.run_publisher(env=self.race_writer_env(late_writer))

        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("PUSH_RACE: attempt 1/3 lost", result.stdout)
        self.assertIn("SUPERSEDED:", result.stdout)
        self.assertEqual(self.remote_head(), input_commit)
        self.assertEqual(self.remote_file("decoder-ai/analysis_report.md"), "seed\n")


if __name__ == "__main__":
    unittest.main()
