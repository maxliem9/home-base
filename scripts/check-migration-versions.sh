#!/usr/bin/env bash
# Guard against DUPLICATE Flyway migration versions — the parallel-branch collision.
#
# THE TRAP (issue #505, the PR #497-vs-#502 breakage):
#   Two branches, cut from the same main, each add a migration with the SAME version:
#     V40__todo_updated_at.sql            (#502)
#     V40__shopping_list_own_categories.sql (#497)
#   Each PR alone is GREEN — its branch carries only one V40, so MigrationIntegrationTest
#   passes. The collision materialises only in the UNION of both branches on main, where
#   Flyway aborts with `Found more than one migration with version 40` and both the
#   migration test AND the app boot fail on main (see the memory note
#   flyway-migration-version-collision).
#
# WHAT THIS FLAGS:
#   A version number that is claimed by TWO OR MORE different migration files once the
#   PR branch is combined with its base (origin/main). Concretely it takes the union of
#     - the migration files in the working tree (on a CI pull_request build that tree is
#       already `refs/pull/N/merge` = the PR merged into main, so a cross-branch clash is
#       present right here), AND
#     - the migration files on origin/main (so a local pre-push run catches a version that
#       another PR merged AFTER this branch was cut — the case CI can miss when the second
#       PR is not rebuilt against the newer main).
#   and reports any Vn owned by more than one distinct filename. It intentionally does NOT
#   care about GAPS in the sequence (V9/V12/V13 are legitimately absent) — only collisions.
#
# WHY A SEPARATE GUARD (vs. just relying on MigrationIntegrationTest):
#   1. It runs with no toolchain (pure git + awk) in ~1s, before the JDK/Gradle/Postgres
#      setup, so the fix is obvious instead of a FlywayException buried in a DB test.
#   2. It runs LOCALLY without Postgres, turning the flyway-migration-version-collision
#      memory-note procedure into an executable pre-push check.
#
# LIMITATION (shared with the migration test, by nature): a collision is only visible once
#   CI runs against a main that already contains the other migration. It cannot see a
#   version that a still-open sibling PR will merge later. The real belt-and-suspenders for
#   that is GitHub's "Require branches to be up to date before merging" branch protection.
#
# USAGE:  bash scripts/check-migration-versions.sh            # compares against origin/main
#         BASE_REF=origin/release bash scripts/check-migration-versions.sh
#         bash scripts/check-migration-versions.sh origin/release
#   exit 0 = no duplicate versions, exit 1 = at least one collision (prints the offenders).
set -euo pipefail

cd "$(dirname "$0")/.."

MIG_DIR="backend/src/main/resources/db/migration"
BASE_REF="${1:-${BASE_REF:-origin/main}}"

if [[ ! -d "$MIG_DIR" ]]; then
  echo "✗ $MIG_DIR not found — run from the repo root." >&2
  exit 2
fi

# Best-effort refresh so a LOCAL run sees migrations that landed on main after this branch
# was cut (the collision this guard exists to catch). Tolerate no network / no remote: on a
# CI pull_request the working tree is already the PR-merged-into-main tree, so the collision
# is present even when the fetch and the base lookup below turn up nothing.
git fetch --quiet --no-tags origin main 2>/dev/null || true

base_commit="$(git rev-parse --verify --quiet "${BASE_REF}^{commit}" 2>/dev/null || true)"
base_label="$BASE_REF"
if [[ -z "$base_commit" ]]; then
  # origin/main may not be wired as a tracking ref on a shallow CI checkout; fall back to the
  # tip we just fetched.
  base_commit="$(git rev-parse --verify --quiet 'FETCH_HEAD^{commit}' 2>/dev/null || true)"
  if [[ -n "$base_commit" ]]; then
    base_label="origin/main (FETCH_HEAD)"
  fi
fi

# Build a combined stream of "<filename><TAB><source>" from the working tree and (if we could
# resolve it) the base, then let awk group by version and report versions owned by 2+ files.
# The version filter lives in awk, so no `grep` sits in the pipeline where a no-match exit
# would trip pipefail.
findings="$(
  {
    # -maxdepth is honoured by both GNU and BSD find (macOS); filenames here never contain
    # spaces/newlines. Strip the directory, tag the source.
    find "$MIG_DIR" -maxdepth 1 -type f -name 'V*.sql' \
      | sed 's#.*/##' \
      | awk -v src="working tree" '{ print $0 "\t" src }'
    if [[ -n "$base_commit" ]]; then
      git ls-tree -r --name-only "$base_commit" -- "$MIG_DIR" \
        | sed 's#.*/##' \
        | awk -v src="$base_label" '{ print $0 "\t" src }'
    fi
  } | awk -F'\t' '
      {
        fname = $1; src = $2
        # Only versioned migrations (V<version>__desc.sql). Repeatable (R__) / undo (U) files
        # have no unique version to collide, so they are ignored.
        if (fname !~ /^V[0-9]+([._][0-9]+)*__.+\.sql$/) next
        ver = fname; sub(/^V/, "", ver); sub(/__.*/, "", ver); gsub(/_/, ".", ver)  # normalise 1_1 == 1.1
        key = ver SUBSEP fname
        if (!(key in seen)) {
          seen[key] = 1
          distinct[ver]++
          flist[ver] = flist[ver] fname "\n"   # filenames never contain \n → safe separator
          srcs[key] = src
        } else if (index(srcs[key], src) == 0) {
          srcs[key] = srcs[key] ", " src       # same file on both sides → "working tree, origin/main"
        }
      }
      END {
        n = 0
        for (v in distinct) if (distinct[v] >= 2) vers[n++] = v
        for (a = 0; a < n; a++)                      # numeric ascending, small N
          for (b = a + 1; b < n; b++)
            if (vers[b] + 0 < vers[a] + 0) { t = vers[a]; vers[a] = vers[b]; vers[b] = t }
        for (a = 0; a < n; a++) {
          v = vers[a]
          printf "  V%s is claimed by %d different migration files:\n", v, distinct[v]
          m = split(flist[v], arr, "\n")
          for (j = 1; j <= m; j++) {
            f = arr[j]; if (f == "") continue
            printf "      - %s   (%s)\n", f, srcs[v SUBSEP f]
          }
        }
      }
  '
)"

if [[ -n "$findings" ]]; then
  echo "✗ Flyway migration guard: duplicate migration version(s) detected." >&2
  echo "  Flyway refuses to migrate when two files share a version (\"Found more than one" >&2
  echo "  migration with version N\") — it breaks the migration test AND the app boot on main." >&2
  echo "  Renumber your NEW migration to the next free Vn above what is on origin/main:" >&2
  echo "    git mv $MIG_DIR/Vn__<desc>.sql $MIG_DIR/V<free>__<desc>.sql" >&2
  echo "  and fix any Vn mention in code/comments. See the memory note" >&2
  echo "  flyway-migration-version-collision (issue #505)." >&2
  echo "" >&2
  printf '%s\n' "$findings" >&2
  exit 1
fi

if [[ -n "$base_commit" ]]; then
  echo "✓ Flyway migration guard: no duplicate versions (working tree ∪ ${base_label})."
else
  echo "✓ Flyway migration guard: no duplicate versions in the working tree." \
       "(Could not resolve ${BASE_REF}; base comparison skipped.)"
fi
exit 0
