import subprocess
import time
import socket
import json
import sys

def send_control(action, value=None):
    with socket.create_connection(("127.0.0.1", 9600), timeout=3) as s:
        s.sendall(json.dumps({"action": action, "value": value}).encode())
        return json.loads(s.recv(1024).decode())

def fetch_watch_recovery_metrics():
    """Replace with a real call: e.g. GET /health on the watch's debug bridge,
    or read a metrics file the RecoveryManager writes during the test."""
    raise NotImplementedError("wire this to a real telemetry source before trusting this test")

def execute_ci_chaos_test():
    print("[CI CHAOS] Starting Chaos Proxy Gateway in background...")
    proxy_proc = subprocess.Popen(["python", "backend/chaos_stream_proxy.py"])
    time.sleep(2) # Wait for sockets to boot

    if proxy_proc.poll() is not None:
        print("[CI CHAOS] Proxy failed to start")
        sys.exit(1)

    try:
        # Programmatic injection sequence
        print("[CI CHAOS] Step 1: Simulating Perfect Network Baseline for 5 seconds...")
        time.sleep(5)

        print("[CI CHAOS] Step 2: Injecting Severe 30% Packet Loss & 150ms Jitter...")
        send_control("set_drop_rate", 0.30)
        send_control("set_jitter", 150)
        time.sleep(5)
        
        print("[CI CHAOS] Step 3: Triggering Total Network Blackout (Dead Zone) for 8 seconds...")
        send_control("set_blackout", True)
        time.sleep(8)
        
        print("[CI CHAOS] Step 4: Clearing Chaos. Allowing RecoveryManager to heal...")
        send_control("reset")
        time.sleep(6)

        print("[CI CHAOS] Step 5: Assessing System Resilience Metrics...")
        
        # ✅ §2.5: Actually assert on real telemetry instead of a hardcoded True
        metrics = fetch_watch_recovery_metrics()
        recovered = metrics.get("reconnected") and metrics.get("frames_resumed_within_ms", 9999) < 3000
        
        if recovered:
            print("[CI CHAOS] SUCCESS: verified against real recovery metrics.")
            proxy_proc.terminate()
            proxy_proc.wait(timeout=5)
            sys.exit(0)
        else:
            print(f"[CI CHAOS] CRITICAL FAILURE: real recovery metrics did not pass: {metrics}")
            proxy_proc.terminate()
            proxy_proc.wait(timeout=5)
            sys.exit(1)

    except NotImplementedError as e:
        print(f"[CI CHAOS] PENDING WIRING: {e}")
        proxy_proc.terminate()
        proxy_proc.wait(timeout=5)
        sys.exit(1)
    except Exception as e:
        print(f"[CI CHAOS] Automation Error: {e}")
        proxy_proc.terminate()
        try:
            proxy_proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proxy_proc.kill()
        sys.exit(1)

if __name__ == "__main__":
    execute_ci_chaos_test()
