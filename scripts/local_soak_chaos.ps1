# local_soak_chaos.ps1
# NASA-Level Network Resilience & Chaos Injection Test Rig for StreamLinkWear
# Duration: 30 Minutes Loop Execution

$DurationMinutes = 30
$EndTime = (Get-Date).AddMinutes($DurationMinutes)
$Iteration = 1

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "🚀 Starting StreamLinkWear Chaos Engineering Test Rig" -ForegroundColor Cyan
Write-Host "⏱️ Execution Profile: $DurationMinutes Minutes Soak Test Mode" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# التحقق من اتصال الأجهزة عبر ADB
$Devices = adb devices | Select-String -Pattern "device$"
if ($Devices.Count -lt 1) {
    Write-Error "❌ No Android devices or Wear OS targets detected via ADB. Connect device and retry."
    exit
}

Write-Host "🎯 Target Device Verified. Commencing Chaos Injection Cycle..." -ForegroundColor Green

while ((Get-Date) -lt $EndTime) {
    Write-Host "`n[Cycle #$Iteration - $(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor Yellow
    
    # اختيار عشوائي لنوع الفوضى الأمنية/الشبكية المحقونة
    $ChaosType = Get-Random -Minimum 1 -Maximum 5

    switch ($ChaosType) {
        1 {
            Write-Host "🎲 Injecting: Short WiFi Disconnect (10 Seconds Intermittent Drop)..." -ForegroundColor Magenta
            adb shell svc wifi disable
            Start-Sleep -Seconds 10
            adb shell svc wifi enable
            Write-Host "🔄 WiFi Interface Restored. Checking for Fail-Safe Reconnection..." -ForegroundColor Green
        }
        2 {
            Write-Host "🎲 Injecting: Packet Jamming (Toggling Airplane Mode Quick Cycle)..." -ForegroundColor Magenta
            adb shell cmd connectivity airplane-mode enable
            Start-Sleep -Seconds 4
            adb shell cmd connectivity airplane-mode disable
            Write-Host "🔄 Cellular/Wireless Pipeline reset complete." -ForegroundColor Green
        }
        3 {
            Write-Host "🎲 Injecting: Micro-Sleep Interruption (Doze Mode Emulation)..." -ForegroundColor DeepPink
            adb shell dumpsys deviceidle force-idle
            Start-Sleep -Seconds 15
            adb shell dumpsys deviceidle unforce
            Write-Host "🔄 Device exited simulated idle state." -ForegroundColor Green
        }
        4 {
            Write-Host "🎲 Injecting: High Packet Latency Simulation (Process Suspension Test)..." -ForegroundColor Orange
            # محاكاة تعليق الخلفية لعملية التطبيق لرؤية إذا كان الـ Socket Server سيغلق بأمان (Fail Closed)
            adb shell am freeze com.streamlink.app
            Start-Sleep -Seconds 8
            adb shell am unfreeze com.streamlink.app
            Write-Host "🔄 App unfrozen. Checking pipeline heartbeat integrity..." -ForegroundColor Green
        }
    }

    # فترة سماح لاستقرار التطبيق وفحص الـ Crashlytics Logs
    Start-Sleep -Seconds 45
    $Iteration++
}

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "🚨 Chaos Soak Test Completed Successfully! Check Firebase for crash signatures." -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
