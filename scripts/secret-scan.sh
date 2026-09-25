#!/usr/bin/env bash
set -euo pipefail

# Secret scan for core-banking-mcp
#
# Detects, one alternative per class (all matched case-insensitively via `grep -iE`):
#   - Personal/corporate mail domains: gmail, hotmail, outlook, yahoo, icloud, xebia
#   - GitHub tokens: ghp_/gho_/ghu_/ghs_/ghr_* or github_pat_*
#   - API keys: sk-*
#   - AWS access keys (AKIA*) and aws_secret_access_key=... assignments
#   - Private key headers: BEGIN ... PRIVATE KEY
#   - Bearer tokens: bearer <token>
#   - Credentials embedded in URLs: scheme://user:pass@host
#   - password/passwd/pwd/secret/token/api_key/apikey assignments, quoted or unquoted,
#     env- or YAML-style (key=value or key: value)
#
# A placeholder allowlist is applied after matching so known-safe placeholders
# (change-me, other-pass, env.getOrElse(key, <bare-identifier>) reads, a Scala
# `: String` type annotation, <angle-bracket-placeholder>, example.com, your-*-here,
# ${VAR} / ${VAR:-default}) never get reported as findings. The env.getOrElse allowlist entry
# requires the default argument to be a bare identifier (not a quoted literal), so a hardcoded
# secret passed as that default — e.g. env.getOrElse("POSTGRES_PASSWORD", "a-real-password") —
# is still reported.
#
# Usage:
#   scripts/secret-scan.sh --self-test    # Verify every pattern class fires, and placeholders don't
#   scripts/secret-scan.sh                 # Scan staged changes in git index
#   scripts/secret-scan.sh --head          # Scan HEAD commit

cd "$(git rev-parse --show-toplevel)"

PATTERN='[A-Za-z0-9._%+-]+@(gmail|hotmail|outlook|yahoo|icloud|xebia)\.com'
PATTERN+='|(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}'
PATTERN+='|sk-[A-Za-z0-9_-]{20,}'
PATTERN+='|AKIA[0-9A-Z]{16}'
PATTERN+='|aws_secret_access_key[[:space:]]*[=:][[:space:]]*[A-Za-z0-9/+]{30,}'
PATTERN+='|BEGIN [A-Z ]*PRIVATE KEY'
PATTERN+='|bearer[[:space:]]+[A-Za-z0-9._-]{20,}'
PATTERN+='|://[^/:@[:space:]]+:[^@[:space:]]+@'
PATTERN+='|(password|passwd|pwd|secret|token|api_key|apikey)[A-Za-z0-9_]*[[:space:]]*[=:][[:space:]]*["'"'"']?[^"'"'"'[:space:]]{8,}'

ALLOWLIST='(change-me|changeme|other-pass|env\.getOrElse\([^)]*,[[:space:]]*[A-Za-z_][A-Za-z0-9_]*\)|:[[:space:]]*String[,)]|<[A-Za-z_-]+>|example\.com|your-[a-z-]+-here|\$\{[A-Za-z_]+(:-[^}]*)?\})'

# Exclusions shared by index and head scans: the scanner script itself (it contains the
# patterns as literal text) and .claude/ (agent prompts legitimately mention "token", etc).
EXCLUDE_PATHSPECS=(':!scripts/secret-scan.sh' ':!.claude/**')

# Removes allowlisted (known-safe placeholder) lines from stdin.
filter_allowlist() {
    grep -viE "$ALLOWLIST" || true
}

# Self-test mode: plant one sample per pattern class and verify it fires; verify placeholder
# negatives do not fire once the allowlist is applied. Uses the same PATTERN/filter_allowlist
# as the real scan.
if [[ "${1:-}" == "--self-test" ]]; then
    declare -a FAILURES=()

    declare -a POSITIVE_NAMES=(
        "personal-email"
        "github-token"
        "api-key"
        "aws-access-key"
        "aws-secret-assignment"
        "private-key-header"
        "bearer-token"
        "url-credential"
        "unquoted-password-assignment"
        "quoted-password-assignment"
    )
    declare -a POSITIVE_SAMPLES=(
        "contact me at someone@gmail.com"
        "x=ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123"
        "x=sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123"
        "x=AKIAABCDEFGHIJKLMNOP"
        "aws_secret_access_key = abcdefghijklmnopqrstuvwxyzABCD1234"
        "-----BEGIN RSA PRIVATE KEY-----"
        "Authorization: bearer abcdefghijklmnopqrstuvwx"
        "https://user:s3cr3t@example.org/path"
        "POSTGRES_PASSWORD=Sup3rS3cretPassw0rd"
        'password = "Sup3rS3cretPassw0rd"'
    )

    for i in "${!POSITIVE_NAMES[@]}"; do
        name="${POSITIVE_NAMES[$i]}"
        sample="${POSITIVE_SAMPLES[$i]}"
        if echo "$sample" | grep -qiE "$PATTERN"; then
            echo "self-test: $name fires (OK)"
        else
            echo "self-test FAILED: $name did not fire on: $sample" >&2
            FAILURES+=("$name")
        fi
    done

    declare -a NEGATIVE_SAMPLES=(
        "POSTGRES_PASSWORD=change-me"
        'POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-change-me}'
        "DATABASE_URL=jdbc:postgresql://localhost:5432/corebanking"
        'token: ${GITHUB_TOKEN}'
        'password = env.getOrElse("POSTGRES_PASSWORD", defaultPassword)'
        'password = "other-pass"'
    )

    for sample in "${NEGATIVE_SAMPLES[@]}"; do
        SURVIVING=$(echo "$sample" | grep -iE "$PATTERN" 2>/dev/null | filter_allowlist || true)
        if [[ -z "$SURVIVING" ]]; then
            echo "self-test: negative '$sample' does not fire (OK)"
        else
            echo "self-test FAILED: negative unexpectedly fired: $sample" >&2
            FAILURES+=("negative: $sample")
        fi
    done

    # A hardcoded secret disguised as an env.getOrElse(...) default must still be reported: the
    # env.getOrElse allowlist entry only exempts a *bare-identifier* default, never a quoted
    # literal, so this must survive both PATTERN and the allowlist filter.
    declare -a ALLOWLIST_SURVIVES_SAMPLES=(
        'password = env.getOrElse("POSTGRES_PASSWORD", "hunter2SuperSecret")'
    )

    for sample in "${ALLOWLIST_SURVIVES_SAMPLES[@]}"; do
        SURVIVING=$(echo "$sample" | grep -iE "$PATTERN" 2>/dev/null | filter_allowlist || true)
        if [[ -n "$SURVIVING" ]]; then
            echo "self-test: disguised secret '$sample' still fires (OK)"
        else
            echo "self-test FAILED: disguised secret was allowlisted away: $sample" >&2
            FAILURES+=("allowlist-survives: $sample")
        fi
    done

    if [[ ${#FAILURES[@]} -gt 0 ]]; then
        echo "self-test: FAILED (${#FAILURES[*]} failure(s))" >&2
        exit 2
    fi

    echo "self-test: all pattern classes and placeholder negatives OK"
    exit 0
fi

# Head mode: scan the HEAD commit
if [[ "${1:-}" == "--head" ]]; then
    # Check if HEAD exists (handle empty repo gracefully)
    if ! git rev-parse HEAD >/dev/null 2>&1; then
        echo "secret-scan: clean (no HEAD yet)"
        exit 0
    fi

    FILE_COUNT=$(git ls-tree -r --name-only HEAD | grep -v -e '^scripts/secret-scan\.sh$' -e '^\.claude/' | wc -l | tr -d ' ')
    if [[ "$FILE_COUNT" -eq 0 ]]; then
        echo "secret scan: NOTHING EXAMINED (0 files)" >&2
        exit 3
    fi

    RAW_FINDINGS=$(git grep -nIiE "$PATTERN" HEAD -- . "${EXCLUDE_PATHSPECS[@]}" 2>/dev/null || true)
    FINDINGS=$(echo "$RAW_FINDINGS" | filter_allowlist)
    if [[ -n "$FINDINGS" ]]; then
        echo "$FINDINGS"
        echo "secret-scan: FINDINGS above, do not commit" >&2
        exit 1
    else
        echo "secret-scan: clean ($FILE_COUNT files examined)"
        exit 0
    fi
fi

# Default mode: scan staged changes (git index)
FILE_COUNT=$(git ls-files --cached | wc -l | tr -d ' ')
if [[ "$FILE_COUNT" -eq 0 ]]; then
    echo "secret scan: NOTHING EXAMINED (0 files)" >&2
    exit 3
fi

RAW_FINDINGS=$(git grep --cached -nIiE "$PATTERN" -- . "${EXCLUDE_PATHSPECS[@]}" 2>/dev/null || true)
FINDINGS=$(echo "$RAW_FINDINGS" | filter_allowlist)
if [[ -n "$FINDINGS" ]]; then
    echo "$FINDINGS"
    echo "secret-scan: FINDINGS above, do not commit" >&2
    exit 1
fi

# No findings
echo "secret-scan: clean ($FILE_COUNT files examined)"
exit 0
