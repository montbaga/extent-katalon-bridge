#!/usr/bin/env bash
# Double-click entry point for macOS - delegates to view-extent-report.sh
# sitting right next to this file (bash is identical on macOS and Linux).
cd "$(dirname "$0")" || exit 1
./view-extent-report.sh
echo ""
read -r -p "Press Enter to close..." _
