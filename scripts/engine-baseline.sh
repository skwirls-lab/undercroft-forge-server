#!/usr/bin/env bash
#
# Records and verifies a content fingerprint of the vendored Forge engine.
#
# The upgrade workflow replaces forge-engine/{forge-core,forge-game,forge-ai,forge-gui}
# with `rm -rf` + copy. If anyone ever patches the engine in place, that would destroy the
# patch without a word. This manifest turns that silent loss into a hard stop: the upgrade
# verifies the tree still matches before it overwrites anything, and refuses to run if it
# does not, naming the files that drifted.
#
# The hashes are git blob object IDs, so they are stable, and comparable against any Forge
# checkout with `git ls-tree` without downloading a single file.
#
#   scripts/engine-baseline.sh write    regenerate the manifest (after a deliberate change)
#   scripts/engine-baseline.sh verify   exit non-zero if the tree has drifted

set -euo pipefail

MODULES=(forge-engine/forge-core forge-engine/forge-game forge-engine/forge-ai forge-engine/forge-gui)
MANIFEST="forge-engine/ENGINE_BASELINE.txt"

# Hashes the WORKING TREE, not the index or HEAD. An unstaged edit to an engine file is
# exactly the kind of change this is meant to catch, and reading HEAD would miss it. Untracked
# files are included too, both so a newly added engine file counts as drift and so this still
# describes the tree correctly after an upgrade has replaced the modules but committed nothing.
generate() {
  local list
  list="$(mktemp)"
  git ls-files --cached --others --exclude-standard -- "${MODULES[@]}" | sort > "$list"
  if [ ! -s "$list" ]; then
    echo "ERROR: no engine files found — wrong working directory?" >&2
    rm -f "$list"
    exit 1
  fi
  paste -d'  ' <(git hash-object --stdin-paths < "$list") "$list"
  rm -f "$list"
}

case "${1:-verify}" in
  write)
    generate > "$MANIFEST"
    echo "Wrote $MANIFEST ($(wc -l < "$MANIFEST") files)"
    ;;
  verify)
    if [ ! -f "$MANIFEST" ]; then
      echo "ERROR: $MANIFEST is missing. Run 'scripts/engine-baseline.sh write'." >&2
      exit 1
    fi
    if diff_out=$(diff <(sort -k2 "$MANIFEST") <(generate)); then
      echo "Engine matches its baseline ($(wc -l < "$MANIFEST") files)."
    else
      echo "ERROR: the vendored Forge engine has drifted from its recorded baseline." >&2
      echo "" >&2
      echo "$diff_out" >&2
      echo "" >&2
      echo "The engine is meant to be an unmodified upstream copy — every Undercroft-specific" >&2
      echo "line belongs in forge-server/, which upgrades never touch. If this change is" >&2
      echo "deliberate, re-record it with 'scripts/engine-baseline.sh write' and say why in" >&2
      echo "the commit, knowing the next upgrade will overwrite it." >&2
      exit 1
    fi
    ;;
  *)
    echo "usage: $0 [write|verify]" >&2
    exit 2
    ;;
esac
