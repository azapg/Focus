#!/usr/bin/env bash
# Release builds set FOCUS_UPDATER_REPO explicitly. Local builds can use a GitHub origin.
set -euo pipefail

if [ "${FOCUS_UPDATER_REPO+x}" ]; then
  repo="$FOCUS_UPDATER_REPO"
else
  origin="$(git config --get remote.origin.url 2>/dev/null || true)"
  case "$origin" in
    https://github.com/*) repo="${origin#https://github.com/}" ;;
    git@github.com:*) repo="${origin#git@github.com:}" ;;
    ssh://git@github.com/*) repo="${origin#ssh://git@github.com/}" ;;
    *) repo="" ;;
  esac
  repo="${repo%.git}"
fi

if [ -n "$repo" ] && [[ ! "$repo" =~ ^[[:alnum:]-]+/[[:alnum:]_.-]+$ ]]; then
  if [ "${FOCUS_UPDATER_REPO+x}" ]; then
    echo "FOCUS_UPDATER_REPO must be a GitHub owner/repository pair." >&2
    exit 1
  fi
  repo=""
fi
printf '%s\n' "$repo"
