# Generate Production Keystore for StreamLinkWear
# Run this ONCE from PowerShell — store the output securely (NOT in Git)

$KEYS_DIR = "$env:USERPROFILE\Documents\streamlink_keys"
$KEYSTORE  = "$KEYS_DIR\streamlink-release.jks"

if (!(Test-Path $KEYS_DIR)) {
    New-Item -ItemType Directory -Force -Path $KEYS_DIR | Out-Null
}

if (Test-Path $KEYSTORE) {
    Write-Host "⚠️  Keystore already exists at $KEYSTORE — skipping generation." -ForegroundColor Yellow
    exit 0
}

Write-Host "🔐 Generating Production Keystore..." -ForegroundColor Cyan
Write-Host "   Location: $KEYSTORE" -ForegroundColor Gray

# RSA-4096, valid for 27+ years (10000 days)
keytool -genkeypair `
    -v `
    -keystore "$KEYSTORE" `
    -storetype PKCS12 `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10000 `
    -alias streamlink `
    -dname "CN=StreamLinkWear, OU=Mobile, O=AbbasAhmed, L=Cairo, S=Cairo, C=EG"

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ keytool failed. Make sure Java JDK is on PATH." -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "✅ Keystore created at: $KEYSTORE" -ForegroundColor Green
Write-Host ""
Write-Host "📋 Add the following to your local.properties (NOT committed to git):" -ForegroundColor Cyan
Write-Host ""
Write-Host "   keystore.file=$($KEYSTORE -replace '\\','\\\\')"
Write-Host "   keystore.password=<your_store_password>"
Write-Host "   keystore.alias=streamlink"
Write-Host "   keystore.key_password=<your_key_password>"
Write-Host ""
Write-Host "🔒 Keep the keystore and passwords in a secure password manager." -ForegroundColor Yellow
Write-Host "   Losing the keystore = losing the ability to update on Google Play." -ForegroundColor Yellow
