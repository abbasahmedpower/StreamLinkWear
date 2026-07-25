#!/usr/bin/env bash
# =============================================================================
# check_string_parity.sh — Catch missing translation keys before they reach prod
#
# Usage:   bash check_string_parity.sh
# Exit:    0 = all languages in sync
#          1 = at least one missing key found
#
# Add to CI pipeline:
#   - run: bash scripts/check_string_parity.sh
# =============================================================================
set -euo pipefail

STRINGS_DIR="app/src/main/res"
BASE="$STRINGS_DIR/values/strings.xml"
FAILED=0

# Extract all key names from the English base file
EN_KEYS=$(grep -oP 'name="\K[^"]+' "$BASE")
EN_COUNT=$(echo "$EN_KEYS" | wc -l)

echo "=== String Key Parity Check ==="
echo "Base (EN): $EN_COUNT keys"
echo ""

for dir in "$STRINGS_DIR"/values-*/; do
    lang=$(basename "$dir")
    file="$dir/strings.xml"

    if [[ ! -f "$file" ]]; then
        echo "[$lang] ❌ strings.xml missing entirely"
        FAILED=1
        continue
    fi

    LANG_KEYS=$(grep -oP 'name="\K[^"]+' "$file")
    MISSING=()
    while IFS= read -r key; do
        if ! echo "$LANG_KEYS" | grep -qx "$key"; then
            MISSING+=("$key")
        fi
    done <<< "$EN_KEYS"

    LANG_COUNT=$(echo "$LANG_KEYS" | wc -l)

    if [[ ${#MISSING[@]} -eq 0 ]]; then
        echo "[$lang] ✅ $LANG_COUNT/$EN_COUNT keys — OK"
    else
        echo "[$lang] ❌ $LANG_COUNT/$EN_COUNT keys — MISSING (${#MISSING[@]}):"
        for k in "${MISSING[@]}"; do
            echo "         - $k"
        done
        FAILED=1
    fi
done

echo ""
if [[ $FAILED -eq 0 ]]; then
    echo "All languages are in sync. ✅"
    exit 0
else
    echo "Build FAILED: Fix missing translation keys above. ❌"
    exit 1
fi
