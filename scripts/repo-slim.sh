#!/usr/bin/env bash
# Repository slimming script for redis-platform / CacheCloud
#
# Usage:
#   ./scripts/repo-slim.sh assess          # Show size breakdown & safe-to-remove list
#   ./scripts/repo-slim.sh untrack         # Stop tracking build artifacts (keeps local files)
#   ./scripts/repo-slim.sh gc              # Prune unreachable git objects (safe, no history rewrite)
#   ./scripts/repo-slim.sh rewrite-history # Rewrite git history to drop large blobs (DESTRUCTIVE)
#   ./scripts/repo-slim.sh all             # untrack + gc + rewrite-history
#
# After rewrite-history, you MUST force-push and have all collaborators re-clone:
#   git push --force-with-lease origin master
#   git push --force-with-lease gitlab master   # if applicable

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

FILTER_REPO="${ROOT}/scripts/git-filter-repo"

# Paths safe to remove from git entirely (build output & local dev deps, not source code)
PATHS_TO_PURGE=(
  "cachecloud-web/.local-setup"
  "cachecloud-web/target"
  "cachecloud-custom/target"
  "cachecloud-client-runner/target"
  "修改文件"
)

# Glob patterns for binary blobs that should never live in git
BLOB_GLOBS=(
  "*.war"
  "*.tar.gz"
  "*.tgz"
  "*.zip"
)

assess() {
  echo "=== Working tree size ==="
  du -sh "$ROOT" 2>/dev/null || true
  du -sh "$ROOT/.git" "$ROOT/cachecloud-web/.local-setup" "$ROOT/cachecloud-web/src" 2>/dev/null || true
  echo

  echo "=== Git object store ==="
  git count-objects -vH
  echo

  echo "=== Largest blobs in history (>10 MB) ==="
  echo "  SAFE TO PURGE — installation media, build output, local SDK downloads:"
  git rev-list --objects --all \
    | git cat-file --batch-check='%(objecttype) %(objectname) %(objectsize) %(rest)' \
    | awk '/^blob/ && $3 > 10485760 {printf "  %8.1f MB  %s\n", $3/1024/1024, $4}' \
    | sort -rn
  echo

  echo "=== Currently tracked files in purge paths ==="
  for p in "${PATHS_TO_PURGE[@]}"; do
    count=$(git ls-files "$p" 2>/dev/null | wc -l | tr -d ' ')
    echo "  $p : $count files"
  done
  echo

  echo "=== Estimated recoverable space ==="
  echo "  ~2.5+ GB from history (mysql/jdk/war/go tarballs + .local-setup + target/)"
  echo "  ~809 MB from working tree (.local-setup, kept locally after untrack)"
  echo "  Actual .git shrink requires rewrite-history + gc"
}

untrack() {
  echo "=== Removing build artifacts from git index (files stay on disk) ==="

  for p in "${PATHS_TO_PURGE[@]}"; do
    if git ls-files --error-unmatch "$p" &>/dev/null || git ls-files "$p" | grep -q .; then
      echo "  git rm -r --cached --ignore-unmatch $p"
      git rm -r --cached --ignore-unmatch "$p" 2>/dev/null || true
    fi
  done

  if [[ -f .gitignore ]]; then
    git add .gitignore
  fi

  echo
  echo "Done. Review with: git status"
  echo "Commit when ready: git commit -m 'chore: stop tracking build artifacts and local setup cache'"
}

gc_only() {
  echo "=== Running git gc (prune unreachable objects) ==="
  git reflog expire --expire=now --all
  git gc --prune=now --aggressive
  echo
  git count-objects -vH
}

rewrite_history() {
  if [[ ! -x "$FILTER_REPO" ]]; then
    echo "ERROR: git-filter-repo not found at $FILTER_REPO"
    echo "Download it:"
    echo "  curl -fsSL https://raw.githubusercontent.com/newren/git-filter-repo/main/git-filter-repo \\"
    echo "    -o scripts/git-filter-repo && chmod +x scripts/git-filter-repo"
    exit 1
  fi

  echo "=== WARNING: This rewrites ALL branch history ==="
  echo "Paths to purge:"
  printf '  - %s\n' "${PATHS_TO_PURGE[@]}"
  echo
  echo "Make sure you have a backup: git clone --mirror . ../redis-platform-backup.git"
  if [[ "${REPO_SLIM_YES:-}" != "1" ]]; then
    echo "Press Ctrl+C within 5 seconds to abort (or set REPO_SLIM_YES=1 to skip)..."
    sleep 5
  fi

  args=()
  for p in "${PATHS_TO_PURGE[@]}"; do
    args+=(--path "$p" --invert-paths)
  done
  for g in "${BLOB_GLOBS[@]}"; do
    args+=(--path-glob "$g" --invert-paths)
  done

  "$FILTER_REPO" --force "${args[@]}"

  gc_only

  echo
  echo "=== History rewrite complete ==="
  du -sh .git
  echo "Next: force-push all remotes and ask teammates to re-clone."
}

case "${1:-assess}" in
  assess) assess ;;
  untrack) untrack ;;
  gc) gc_only ;;
  rewrite-history) rewrite_history ;;
  all)
    untrack
    rewrite_history
    ;;
  *)
    echo "Usage: $0 {assess|untrack|gc|rewrite-history|all}"
    exit 1
    ;;
esac
