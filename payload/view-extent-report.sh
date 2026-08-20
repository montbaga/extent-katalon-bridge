#!/usr/bin/env bash
# Opens the most recently generated Extent report. Generation itself happens
# automatically when a run finishes - each run writes its own
# "extent-report/<Name>_<timestamp>/Index.html", self-contained (CSS/JS/fonts
# copied alongside it, no CDN or server needed) so it opens straight in your
# browser from a double-click.

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

LATEST_DIR=$(ls -td "extent-report"/*/ 2>/dev/null | head -1 || true)
LATEST_DIR="${LATEST_DIR%/}"

if [ -z "$LATEST_DIR" ]; then
    echo "No report found yet under extent-report/."
    echo "Run a Katalon test suite first - the report is generated automatically when it finishes."
    exit 1
fi

REPORT_FILE="$LATEST_DIR/Index.html"
echo "Opening report: $REPORT_FILE"
case "$(uname -s)" in
    Darwin) open "$REPORT_FILE" ;;
    *) xdg-open "$REPORT_FILE" 2>/dev/null || echo "Open this file in your browser: $REPORT_FILE" ;;
esac
