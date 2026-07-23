#!/usr/bin/env bash
# =============================================================================
# scripts/export_clean_zip.sh — Safe ZIP Export
# Always uses `git archive` which respects .gitignore automatically.
# NEVER zip the raw project folder — it will include .jks, secrets.properties, etc.
# =============================================================================
set -euo pipefail

OUT="StreamLinkWear_$(date +%Y%m%d_%H%M).zip"

echo "📦 Creating clean archive via git archive..."
git archive --format=zip -o "$OUT" HEAD

echo ""
echo "🔍 Verifying no secrets leaked into archive..."
LEAKED=$(unzip -l "$OUT" | grep -Ei "\.jks$|secrets\.properties$|\.env$|\.keystore$|signing\.properties$" || true)

if [[ -n "$LEAKED" ]]; then
  echo "❌ CRITICAL: Secrets found in archive! Aborting."
  echo "$LEAKED"
  rm -f "$OUT"
  exit 1
fi

echo "✅ Clean archive written to: $OUT"
echo "   (verified: no .jks / secrets.properties / .env found)"
