"""Contract tests for quick verification; no actual Gradle, pnpm or API calls."""
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

MOCK_COMMAND = r'''#!/usr/bin/env python3
import json, os, sys
kind = os.path.basename(sys.argv[0])
with open(os.environ["CALLS_FILE"], "a") as f:
    f.write(json.dumps({"kind": kind, "args": sys.argv[1:], "cwd": os.getcwd()}) + "\n")
if kind == "gradlew":
    rc = int(os.environ.get("MOCK_GRADLE_RC", "0"))
    print("BUILD FAILED" if rc or os.environ.get("MOCK_FAILURE_MARKER") == "1" else "BUILD SUCCESSFUL")
else:
    rc = int(os.environ.get("MOCK_PNPM_RC", "0"))
    print("mock frontend check")
sys.exit(rc)
'''

MOCK_TASKSET = '''#!/usr/bin/env python3
import os, sys
args = sys.argv[3:]
if args == ["true"]:
    sys.exit(int(os.environ.get("MOCK_AFFINITY_INVALID", "0")))
os.execvpe(args[0], args, os.environ)
'''


class FastCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="fast check " )
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "tools").mkdir()
        (self.root / "bin").mkdir()
        (self.root / "web-ui").mkdir()
        self.script = self.root / "tools/fast-check.sh"
        shutil.copy2(ROOT / "tools/fast-check.sh", self.script)
        shutil.copy2(ROOT / "tools/app-core-tests.txt", self.root / "tools/app-core-tests.txt")
        for path, source in [(self.root / "gradlew", MOCK_COMMAND),
                             (self.root / "bin/pnpm", MOCK_COMMAND),
                             (self.root / "bin/taskset", MOCK_TASKSET)]:
            path.write_text(source)
            path.chmod(0o755)
        self.calls_file = self.root / "calls.jsonl"
        self.env = dict(os.environ, PATH=str(self.root / "bin") + os.pathsep + os.environ["PATH"],
                        CALLS_FILE=str(self.calls_file), FAST_CHECK_CPUS="none")
        for key in ["MOCK_GRADLE_RC", "MOCK_PNPM_RC", "MOCK_FAILURE_MARKER", "MOCK_AFFINITY_INVALID"]:
            self.env.pop(key, None)
        self.git("init", "-q")
        (self.root / "tracked.txt").write_text("initial\n")
        self.git("add", "tracked.txt")
        self.git("-c", "user.name=Test Fixture", "-c", "user.email=fixture@example.invalid",
                 "commit", "-qm", "fixture")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, env=self.env,
                              text=True, capture_output=True, check=True)

    def run_check(self, *args, **env):
        return subprocess.run(["bash", str(self.script), *args], cwd=self.root,
                              env={**self.env, **env}, text=True, capture_output=True, timeout=15)

    def calls(self):
        return [json.loads(line) for line in self.calls_file.read_text().splitlines()] if self.calls_file.exists() else []

    def gradle_args(self):
        calls = [x for x in self.calls() if x["kind"] == "gradlew"]
        self.assertEqual(1, len(calls), self.calls())
        return calls[0]["args"]

    def assert_success(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_default_app_runs_core_tests_without_ai_or_release(self):
        self.assert_success(self.run_check())
        args = self.gradle_args()
        self.assertIn(":app:compileGithubDebugKotlin", args)
        self.assertIn(":app:testGithubDebugUnitTest", args)
        self.assertIn("me.rerere.rikkahub.data.model.Conversation*Test", args)
        self.assertNotIn(":ai:testDebugUnitTest", args)
        self.assertNotIn("--stop", args)
        self.assertIn("--no-daemon", args)
        self.assertFalse(any("assemble" in x or "spotless" in x for x in args))

    def test_ai_scope_does_not_compile_app(self):
        self.assert_success(self.run_check("ai"))
        args = self.gradle_args()
        self.assertIn(":ai:testDebugUnitTest", args)
        self.assertFalse(any(x.startswith(":app:") for x in args))

    def test_resources_scope_links_resources_without_apk_or_unit_tests(self):
        self.assert_success(self.run_check("resources"))
        args = self.gradle_args()
        self.assertIn(":app:processGithubDebugResources", args)
        self.assertFalse(any("UnitTest" in x or "assemble" in x for x in args))

    def test_pre_push_uses_one_gradle_invocation_with_correct_app_filter_position(self):
        self.assert_success(self.run_check("pre-push"))
        args = self.gradle_args()
        for task in [":ai:testDebugUnitTest", ":app:compileGithubDebugKotlin",
                     ":app:processGithubDebugResources", ":app:testGithubDebugUnitTest"]:
            self.assertIn(task, args)
        self.assertGreater(args.index("--tests"), args.index(":app:testGithubDebugUnitTest"))
        self.assertNotIn(":workspace:testDebugUnitTest", args)

    def test_filter_spaces_and_wildcards_are_preserved_and_replace_core_filters(self):
        (self.root / "matching filename").write_text("")
        pattern = "*single-reply recovery*"
        self.assert_success(self.run_check("app", "--tests", pattern))
        args = self.gradle_args()
        self.assertEqual(pattern, args[args.index("--tests") + 1])
        self.assertEqual(1, args.count("--tests"))
        self.assertNotIn("me.rerere.rikkahub.data.model.Conversation*Test", args)

    def test_equals_filter_replaces_default_filters(self):
        self.assert_success(self.run_check("pre-push", "--tests=*specific test*"))
        args = self.gradle_args()
        self.assertIn("--tests=*specific test*", args)
        self.assertNotIn("--tests", args)

    def test_test_value_is_not_consumed_as_wrapper_option(self):
        self.assert_success(self.run_check("ai", "--tests", "--plan"))
        self.assertEqual("--plan", self.gradle_args()[2])

    def test_legacy_explicit_task_does_not_add_app_compile(self):
        self.assert_success(self.run_check(":ai:testDebugUnitTest", "--tests", "*method with spaces*"))
        args = self.gradle_args()
        self.assertEqual(":ai:testDebugUnitTest", args[0])
        self.assertIn("*method with spaces*", args)
        self.assertFalse(any(x.startswith(":app:") for x in args))

    def test_explicit_separator_and_multiple_custom_tasks(self):
        self.assert_success(self.run_check("--", ":ai:testDebugUnitTest", ":app:compileGithubDebugKotlin"))
        self.assertEqual([":ai:testDebugUnitTest", ":app:compileGithubDebugKotlin"], self.gradle_args()[:2])

    def test_initial_separator_forwards_gradle_help_instead_of_consuming_it(self):
        self.assert_success(self.run_check("--", ":ai:testDebugUnitTest", "--help"))
        self.assertIn("--help", self.gradle_args())

    def test_empty_core_list_fails_closed_but_explicit_filter_is_allowed(self):
        (self.root / "tools/app-core-tests.txt").write_text("# no filters\n")
        self.assertEqual(2, self.run_check("app").returncode)
        self.assertEqual([], self.calls())
        self.assert_success(self.run_check("app", "--tests", "*SpecificTest"))
        self.assertIn("*SpecificTest", self.gradle_args())

    def test_web_only_runs_non_mutating_typecheck(self):
        self.assert_success(self.run_check("web"))
        self.assertEqual(["pnpm"], [x["kind"] for x in self.calls()])
        self.assertEqual(["--dir", "web-ui", "run", "typecheck"], self.calls()[0]["args"])

    def test_web_precedes_gradle_in_combined_mode(self):
        self.assert_success(self.run_check("pre-push", "--with-web"))
        self.assertEqual(["pnpm", "gradlew"], [x["kind"] for x in self.calls()])

    def test_web_failure_stops_before_gradle(self):
        result = self.run_check("pre-push", "--with-web", MOCK_PNPM_RC="7")
        self.assertEqual(7, result.returncode)
        self.assertEqual(["pnpm"], [x["kind"] for x in self.calls()])

    def test_gradle_failure_exit_is_not_masked_by_tee(self):
        result = self.run_check("ai", MOCK_GRADLE_RC="17")
        self.assertEqual(17, result.returncode)
        reports = list((self.root / ".git/fast-check-logs").glob("*/result.txt"))
        self.assertEqual(1, len(reports))
        self.assertIn("status=failed", reports[0].read_text())

    def test_log_write_failure_is_not_reported_as_success(self):
        real_tee = shutil.which("tee")
        fake_tee = self.root / "bin/tee"
        fake_tee.write_text(r'''#!/usr/bin/env python3
import os, sys
if sys.argv[1].endswith("whitespace.log"):
    os.execv(os.environ["REAL_TEE"], [os.environ["REAL_TEE"], *sys.argv[1:]])
sys.stdin.buffer.read()
sys.exit(23)
''')
        fake_tee.chmod(0o755)
        result = self.run_check("ai", REAL_TEE=real_tee)
        self.assertEqual(23, result.returncode)
        self.assertIn(":ai:testDebugUnitTest", self.gradle_args())

    def test_false_success_with_failure_marker_is_rejected(self):
        result = self.run_check("ai", MOCK_FAILURE_MARKER="1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("despite exit 0", result.stderr)

    def test_plan_does_not_run_checks(self):
        result = self.run_check("pre-push", "--with-web", "--plan")
        self.assert_success(result)
        self.assertIn("PLAN ONLY", result.stdout)
        self.assertEqual([], self.calls())

    def test_gradle_dry_run_is_not_reported_as_test_pass(self):
        result = self.run_check("ai", "--dry-run")
        self.assert_success(result)
        self.assertIn("DRY RUN completed", result.stdout)
        self.assertNotIn("PASS:", result.stdout)

    def test_active_lock_prevents_another_build(self):
        with (self.root / ".git/fast-check.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.run_check("ai")
        self.assertEqual(75, result.returncode)
        self.assertEqual([], self.calls())

    def test_invalid_affinity_falls_back_without_failing_build(self):
        result = self.run_check("ai", FAST_CHECK_CPUS="4-7", MOCK_AFFINITY_INVALID="1")
        self.assert_success(result)
        self.assertIn("continuing without pinning", result.stderr)
        self.assertIn(":ai:testDebugUnitTest", self.gradle_args())

    def test_valid_affinity_preserves_gradle_arguments(self):
        self.assert_success(self.run_check("ai", "--tests", "*a b*", FAST_CHECK_CPUS="4-7"))
        self.assertIn("*a b*", self.gradle_args())

    def test_usage_errors_do_not_start_gradle(self):
        for args in [("unknown",), ("ai", "--daemon"), ("app", "--tests"),
                     ("resources", "--tests", "x"), ("web", "--offline"),
                     ("app", ":ai:testDebugUnitTest"), ("--",)]:
            with self.subTest(args=args):
                self.assertEqual(2, self.run_check(*args).returncode)
        self.assertEqual([], self.calls())

    def test_staged_whitespace_failure_stops_before_build(self):
        (self.root / "tracked.txt").write_text("bad trailing whitespace  \n")
        self.git("add", "tracked.txt")
        self.assertNotEqual(0, self.run_check("ai").returncode)
        self.assertEqual([], self.calls())


if __name__ == "__main__":
    unittest.main()
