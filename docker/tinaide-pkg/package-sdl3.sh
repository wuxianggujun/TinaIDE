#!/usr/bin/env bash
# Backward-compatible SDL3 assets packaging entry point for Windows Git Bash.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/package-for-assets-win.sh" sdl3 "${1:-arm64-v8a}"
