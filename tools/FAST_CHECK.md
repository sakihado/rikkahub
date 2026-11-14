# Fast local verification

Use local checks to catch common compile, resource, serialization and logic regressions quickly. Let Actions build APKs and run environment-sensitive tests. There is no measured guarantee that this catches a fixed percentage of bugs or always finishes within a particular time.

## Choose a scope

Run from the repository checkout:

```bash
# Daily AI/provider/message work: AI compile + all AI JVM tests, no App compile.
./tools/fast-check.sh ai

# Daily App/Compose work: App Kotlin/KSP compile + fast core App regression groups.
./tools/fast-check.sh app
# No arguments means the same app scope, not compile-only.
./tools/fast-check.sh

# XML, strings or Manifest changes: link resources and process required manifests.
# This does not assemble an APK.
./tools/fast-check.sh resources

# Frontend-only work: React Router type generation + TypeScript checking.
# Does not invoke Gradle, auto-fix lint or reformat files.
./tools/fast-check.sh web

# Once a batch of changes is ready / before pushing:
# AI tests + App compile + resource linking + core App tests in ONE Gradle invocation.
./tools/fast-check.sh pre-push

# Also changed web-ui? Typecheck it first, then run the same integration check.
./tools/fast-check.sh pre-push --with-web

# Inspect scope without claiming that any validation has run.
./tools/fast-check.sh pre-push --plan
```

Do not run the App integration pass after every tiny provider edit. Use `ai` during the inner loop, then `pre-push` once the change is ready. Public models/interfaces, dependencies and cross-module changes need the integration pass even if module tests succeed. Native, device-permission, packaging, minification and external-API behavior can require additional targeted checks.

The script intentionally does not guess the affected scope from Git paths. Such guessing can miss public API consumers, staged/new files or already-committed changes. The person/agent editing the code chooses the scope explicitly.

## Targeted tests and custom tasks

```bash
./tools/fast-check.sh ai --tests '*GoogleSignatureRecoveryTest'
./tools/fast-check.sh app --tests '*ConversationSignatureRecoveryTest'
# Quote both spaces and wildcards in a method-level selector.
./tools/fast-check.sh ai --tests '*single-reply recovery*'

# Existing explicit-task invocations remain supported.
# Unlike the old wrapper, this does NOT silently prepend App compilation.
./tools/fast-check.sh :ai:testDebugUnitTest --tests '*GoogleRequestMessageTest'
./tools/fast-check.sh -- :app:compileGithubDebugKotlin
```

For `app` / `pre-push`, an explicit `--tests` replaces the default App filters. In `pre-push` it applies to the App test task, not the preceding AI task. The default groups are listed in `tools/app-core-tests.txt`, shared with CI. Filtering execution does not avoid compiling the module's test sources.

Use explicit task mode for custom task lists; named scopes only accept additional Gradle options. All arguments are passed as arrays without shell re-splitting or glob expansion. Do not use local Release/AAB/R8 or full-repository Lint/formatting as routine fast checks.

## Process and reporting behavior

- One wrapper invocation per checkout at a time, guarded by `flock`. A second invocation fails clearly instead of stopping another build. The lock cannot prevent someone launching Gradle directly outside the wrapper.
- No unconditional `gradlew --stop`. Keep `--no-daemon`; the wrapper rejects `--daemon`. This is not a promise that interrupted PRoot processes can never remain. If a task is stuck, inspect/stop that specific task before manual cleanup.
- Existing Gradle build/configuration caches and workspace JVM/worker limits are retained. No automatic `clean`, `--rerun-tasks`, or memory increases.
- CPU affinity defaults to attempting `4-7` on ARM64, only if allowed. On other machines or a failed probe, no pinning is applied. `FAST_CHECK_CPUS=none` disables it; an explicit CPU list overrides the default. CPU numbering does not universally identify performance cores.
- Logs and a small `result.txt` are stored in the checkout's Git metadata, normally `.git/fast-check-logs/`. They are not added to source control.
- Command and log-write failures propagate nonzero status. An explicit Gradle `BUILD FAILED` marker also fails the check even if a host/transport anomaly reports exit 0. Inspect logs/JUnit reports rather than trusting an unrelated old report or a notification alone.
- `--plan` and Gradle `--dry-run` are identified as non-executing checks, not test passes. Successful cached/UP-TO-DATE tasks are legitimate incremental results; do not force reruns merely to generate output.
- No provider API calls, APK installation, rewriting of handwritten source, daemon cleanup or dependency installation are performed automatically. Normal build/type-generation outputs are still produced. Gradle/pnpm may need already-configured dependencies; use Gradle's `--offline` when appropriate.

Run longer checks via the workspace background command tool so the UI stays responsive. Avoid starting a second Gradle invocation while the first one is still active.

## Formatting and frontend lint

The wrapper checks staged and unstaged whitespace with `git diff --check`. This is not Kotlin formatting, static analysis or full lint. Both `spotlessCheck` and `spotlessApply` use the configured `origin/master` ratchet, so either can cover many historical branch changes. `spotlessApply` is not a cheap substitute limited to this edit. Format only explicitly selected changed files when needed.

`web-ui` has a `lint` script containing `--fix`; it is deliberately not called by quick verification. If a read-only frontend lint check is needed, run `pnpm --dir web-ui exec oxlint app` explicitly.

## What CI checks

The branch APK workflow runs the wrapper's Python regression tests, frontend typecheck, all AI and workspace JVM tests, the shared core App groups, and additional host/phone tests. APK building/upload depends on the test job passing. JUnit reports are retained even on failure. This workflow does not claim full Android Lint, emulator/UI coverage, every App test, or live provider API coverage.

Local PRoot process-tree/signal behavior differs from the CI host; the documented host-process cancellation test can fail locally. Keep those environment-sensitive tests out of the default local core group, but do not silently waive them in CI.

## Test the wrapper itself

```bash
python3 -m unittest discover -s tools/tests -p 'test_fast_check.py' -v
```

These tests use temporary Git checkouts and fake Gradle/pnpm commands. They do not compile the app, stop daemons, use API credentials or execute provider calls.
