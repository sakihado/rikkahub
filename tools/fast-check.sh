#!/usr/bin/env bash
# Scoped, incremental verification. Never assembles Release or reformats source by default.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

usage() {
    cat <<'EOF'
Usage: tools/fast-check.sh [app|ai|resources|web|pre-push] [options]
       tools/fast-check.sh :module:task [Gradle options]
       tools/fast-check.sh -- :module:task [Gradle options]

Scopes:
  app        App Kotlin/KSP compilation + core app tests (default).
  ai         All AI module unit tests; no implicit App compilation.
  resources  App resource linking and required manifest processing; no APK.
  web        Frontend typecheck only; no Gradle and no auto-fixing lint.
  pre-push   AI tests + App compilation/resource linking + core app tests.

Options:
  --tests PATTERN   Replace default app filters, or filter the AI test task.
                   Quote patterns containing spaces or wildcard characters.
  --with-web       Run frontend typecheck before the selected Gradle checks.
  --plan           Show commands only; does NOT validate code or run tests.
  --help           Show this help.
  --               Stop parsing wrapper options and forward remaining arguments.

Other Gradle options are forwarded unchanged. Presets accept options, not extra
tasks; use the explicit task form for custom task lists. --daemon is not allowed.

FAST_CHECK_CPUS=auto  Try cores 4-7 on ARM64 if allowed; otherwise do not pin.
FAST_CHECK_CPUS=none  Disable affinity; a custom CPU list is also accepted.

Logs/results are stored under the checkout's Git metadata (not committed).
No daemon is stopped automatically. Only one invocation per checkout may run.
No percentage-of-bugs or fixed-duration guarantee is implied.
EOF
}

die() {
    printf 'fast-check: %s\n' "$1" >&2
    exit "${2:-2}"
}

mode=app
with_web=false
plan=false
gradle_args=()

if (($#)); then
    case "$1" in
        app|ai|resources|web|pre-push) mode=$1; shift ;;
        :*) mode=custom ;;
        --) mode=custom; shift; gradle_args=("$@"); set -- ;;
        --help|-h) usage; exit 0 ;;
        --*) ;; # Options without a scope use the default app scope.
        *) die "Unknown scope '$1'. Use --help for supported scopes." ;;
    esac
fi

while (($#)); do
    case "$1" in
        --with-web) with_web=true; shift ;;
        --plan) plan=true; shift ;;
        --help|-h) usage; exit 0 ;;
        --tests)
            (($# >= 2)) || die '--tests requires a pattern.'
            gradle_args+=("$1" "$2"); shift 2 ;;
        --) shift; gradle_args+=("$@"); break ;;
        *) gradle_args+=("$1"); shift ;;
    esac
done

has_test_filter=false
dry_run=false
expect_test_value=false
for arg in "${gradle_args[@]}"; do
    if $expect_test_value; then
        expect_test_value=false
        continue
    fi
    case "$arg" in
        --daemon|--daemon=*) die 'Use --no-daemon in this local workflow.' ;;
        --tests) has_test_filter=true; expect_test_value=true ;;
        --tests=*) has_test_filter=true ;;
        --dry-run|-m) dry_run=true ;;
        :*) [[ $mode == custom ]] || die 'Extra tasks require the explicit :task form.' ;;
    esac
done
$expect_test_value && die '--tests requires a pattern.'

if [[ $mode == web ]]; then
    ((${#gradle_args[@]} == 0)) || die 'The web scope does not accept Gradle arguments.'
    with_web=true
fi
if [[ $mode == resources ]] && $has_test_filter; then
    die 'Use app or ai for --tests; resources only checks Android resources.'
fi
if [[ $mode == custom ]] && ((${#gradle_args[@]} == 0)); then
    die 'The explicit task form requires at least one Gradle argument.'
fi

tasks=()
case "$mode" in
    ai) tasks=(:ai:testDebugUnitTest) ;;
    app) tasks=(:app:compileGithubDebugKotlin :app:testGithubDebugUnitTest) ;;
    resources) tasks=(:app:processGithubDebugResources) ;;
    pre-push)
        tasks=(:ai:testDebugUnitTest :app:compileGithubDebugKotlin
            :app:processGithubDebugResources :app:testGithubDebugUnitTest) ;;
esac

# Filters immediately follow the App test task, never the AI test task.
if [[ $mode == app || $mode == pre-push ]] && ! $has_test_filter; then
    core_count=0
    while IFS= read -r pattern || [[ -n $pattern ]]; do
        [[ -z $pattern || $pattern == \#* ]] && continue
        tasks+=(--tests "$pattern")
        core_count=$((core_count + 1))
    done < tools/app-core-tests.txt
    ((core_count > 0)) || die 'Core test list is empty; refusing to run unfiltered App tests.'
fi

affinity=()
cpus=${FAST_CHECK_CPUS:-auto}
if [[ $cpus == auto ]]; then
    case "$(uname -m)" in
        aarch64|arm64) cpus=4-7 ;;
        *) cpus=none ;;
    esac
fi
if [[ $cpus != none ]]; then
    if command -v taskset >/dev/null 2>&1 && taskset -c "$cpus" true >/dev/null 2>&1; then
        affinity=(taskset -c "$cpus")
    else
        printf 'fast-check: CPU affinity %s unavailable; continuing without pinning.\n' "$cpus" >&2
    fi
fi

gradle_command=("${affinity[@]}" ./gradlew "${tasks[@]}" "${gradle_args[@]}" --no-daemon --console=plain)
web_command=(pnpm --dir web-ui run typecheck)

print_command() {
    printf '  '
    printf '%q ' "$@"
    printf '\n'
}

printf '==> fast-check scope: %s\n' "$mode"
$with_web && print_command "${web_command[@]}"
[[ $mode == web ]] || print_command "${gradle_command[@]}"
if $plan; then
    printf 'PLAN ONLY: no compilation, tests or source checks were executed.\n'
    exit 0
fi

command -v git >/dev/null 2>&1 || die 'git is required.'
command -v flock >/dev/null 2>&1 || die 'flock is required (util-linux on Ubuntu).'
command -v tee >/dev/null 2>&1 || die 'tee is required.'
git rev-parse --git-dir >/dev/null 2>&1 || die 'Run from a Git checkout.'
if $with_web; then
    command -v pnpm >/dev/null 2>&1 || die 'pnpm is required for frontend typecheck.'
fi

# Keep the lock file: deleting it could let a second process lock a new inode.
lock_file=$(git rev-parse --git-path fast-check.lock)
exec 9>"$lock_file"
flock -n 9 || die 'Another fast-check is active in this checkout. No daemon was stopped.' 75

log_root=$(git rev-parse --git-path fast-check-logs)
mkdir -p "$log_root"
run_dir=$(mktemp -d "$log_root/$(date -u +%Y%m%dT%H%M%SZ)-$mode.XXXXXX")
start=$SECONDS
step=0
printf 'Logs: %s\n' "$run_dir"

check_whitespace() {
    git diff --check && git diff --cached --check
}

run_step() {
    local label=$1 kind=$2
    shift 2
    step=$((step + 1))
    local logfile="$run_dir/$step-$label.log"
    local -a statuses
    local rc
    printf '==> %s\n' "$label"
    set +e
    "$@" 2>&1 | tee "$logfile"
    statuses=("${PIPESTATUS[@]}")
    set -e
    rc=${statuses[0]}
    if ((rc == 0 && statuses[1] != 0)); then
        rc=${statuses[1]}
    fi
    # Exit status remains primary. Also reject an explicit Gradle failure marker
    # if a host/transport anomaly reports exit 0 (observed once in this PRoot).
    if ((rc == 0)) && [[ $kind == gradle ]] && grep -q '^BUILD FAILED' "$logfile"; then
        rc=1
        printf 'Gradle reported BUILD FAILED despite exit 0; refusing to mark this check passed.\n' >&2
    fi
    if ((rc != 0)); then
        printf 'status=failed\nscope=%s\nstep=%s\nexit_code=%s\n' "$mode" "$label" "$rc" > "$run_dir/result.txt"
        printf 'FAIL: %s (exit %s). Log: %s\n' "$label" "$rc" "$logfile" >&2
        return "$rc"
    fi
}

run_step whitespace plain check_whitespace
if $with_web; then
    run_step web-typecheck plain "${web_command[@]}"
fi
if [[ $mode != web ]]; then
    run_step gradle gradle "${gradle_command[@]}"
fi

if $dry_run; then
    result=dry-run
    printf 'DRY RUN completed; no Gradle compilation or tests were executed.\n'
else
    result=passed
    printf 'PASS: selected %s checks completed in %ss. This is not APK, device or live-API verification.\n' "$mode" "$((SECONDS - start))"
fi
printf 'status=%s\nscope=%s\nelapsed_seconds=%s\n' "$result" "$mode" "$((SECONDS - start))" > "$run_dir/result.txt"
printf 'Logs/result: %s\n' "$run_dir"
