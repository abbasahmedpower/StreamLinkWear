param(
    [int]$Hours = 2,
    [int]$IntervalSeconds = 60
)

$durationSec = $Hours * 60 * 60
$iterations = [math]::Ceiling($durationSec / $IntervalSeconds)

Write-Host "=============================================="
Write-Host " Starting StreamLinkWear Soak Test"
Write-Host " Duration: $Hours hours ($durationSec seconds)"
Write-Host " Interval: $IntervalSeconds seconds"
Write-Host "=============================================="

# Clear logcat
adb logcat -c

# Launch the Android app
Write-Host "Launching app on device/emulator..."
adb shell am start -n com.streamlink.app/com.streamlink.app.ui.MainActivity

$logFile = "soak_test_results.log"
"Time,Memory_KB" | Out-File $logFile

for ($i = 1; $i -le $iterations; $i++) {
    Start-Sleep -Seconds $IntervalSeconds
    
    # Get total memory usage in KB
    $memInfo = adb shell dumpsys meminfo com.streamlink.app | Select-String "TOTAL:"
    if ($memInfo -match "TOTAL:\s+(\d+)") {
        $memKb = $matches[1]
        $elapsed = $i * $IntervalSeconds
        Write-Host "[$elapsed s] Memory: $memKb KB"
        "$elapsed,$memKb" | Out-File $logFile -Append
    }
    
    # Check if process is still alive
    $pid = adb shell pidof com.streamlink.app
    if ([string]::IsNullOrWhiteSpace($pid)) {
        Write-Host "ERROR: App crashed or was killed during soak test!" -ForegroundColor Red
        exit 1
    }
}

Write-Host "=============================================="
Write-Host " Soak test completed successfully! No crashes."
Write-Host " Results saved to $logFile"
Write-Host "=============================================="
