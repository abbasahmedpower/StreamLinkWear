import subprocess
import time
import socket
import json
import sys


CONTROL_HOST = "127.0.0.1"
CONTROL_PORT = 9600


def send_control(action, value=None):
    """Send a JSON control command to the chaos proxy's control server."""
    cmd = {"action": action}
    if value is not None:
        cmd["value"] = value
    with socket.create_connection((CONTROL_HOST, CONTROL_PORT), timeout=5) as s:
        s.sendall(json.dumps(cmd).encode())
        resp = json.loads(s.recv(1024).decode())
    if not resp.get("ok"):
        raise RuntimeError(f"Control command failed: {resp}")
    return resp


def fetch_watch_recovery_metrics():
    """
    ✅ §2.5: This function must read REAL telemetry produced by RecoveryManager on the watch.

    Wire this to one of:
      (a) A metrics file the watch's RecoveryManager writes during the test run, e.g.:
              import pathlib, json
              data = json.loads(pathlib.Path("/tmp/slw_recovery_metrics.json").read_text())
              return data
      (b) A debug HTTP endpoint on the watch's local bridge, e.g.:
              import urllib.request
              resp = urllib.request.urlopen("http://localhost:7890/recovery_metrics", timeout=3)
              return json.loads(resp.read())

    Until one of the above is wired, this raises NotImplementedError so the test
    FAILS HONESTLY instead of printing "NASA Validation Passed" with no real signal.
    """
    raise NotImplementedError(
        "[CI CHAOS] fetch_watch_recovery_metrics() is not wired to a real telemetry source.\n"
        "  See comments in run_chaos_assessment.py for integration options.\n"
        "  Do NOT replace this with a hardcoded return value — that recreates the original bug."
    )


def execute_ci_chaos_test():
    print("[CI CHAOS] Starting Chaos Proxy Gateway in background...")
    proxy_proc = subprocess.Popen(
        ["python", "backend/chaos_stream_proxy.py"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    time.sleep(2)  # Wait for sockets to bind

    if proxy_proc.poll() is not None:
        stdout, stderr = proxy_proc.communicate()
        print("[CI CHAOS] Proxy failed to start")
        print(stdout.decode(errors="ignore"))
        print(stderr.decode(errors="ignore"))
        sys.exit(1)

    try:
        print("[CI CHAOS] Step 1: Baseline — verifying perfect-network delivery for 5s...")
        time.sleep(5)

        print("[CI CHAOS] Step 2: Injecting 30% packet loss + 150ms jitter...")
        send_control("set_drop_rate", 0.30)
        send_control("set_jitter", 150)
        time.sleep(5)

        print("[CI CHAOS] Step 3: Triggering total blackout (Dead Zone) for 8s...")
        send_control("set_blackout", True)
        time.sleep(8)

        print("[CI CHAOS] Step 4: Clearing chaos — allowing RecoveryManager to self-heal...")
        send_control("reset")
        time.sleep(6)  # Recovery window

        print("[CI CHAOS] Step 5: Reading real recovery metrics from watch telemetry...")
        metrics = fetch_watch_recovery_metrics()   # ✅ §2.5: Raises if not wired = honest failure

        recovered = (
            metrics.get("reconnected", False) and
            metrics.get("frames_resumed_within_ms", 99999) < 3000
        )

        if not recovered:
            print(f"[CI CHAOS] FAILURE: recovery metrics did not meet thresholds: {metrics}")
            sys.exit(1)

        print(f"[CI CHAOS] SUCCESS: System self-healed. Metrics: {metrics}")
        sys.exit(0)

    except NotImplementedError as e:
        print(f"\n[CI CHAOS] BLOCKED — telemetry not wired yet:\n{e}")
        sys.exit(2)   # exit code 2 = "not ready" (distinct from a real failure)

    except Exception as e:
        print(f"[CI CHAOS] Automation error: {e}")
        sys.exit(1)

    finally:
        proxy_proc.terminate()
        try:
            proxy_proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proxy_proc.kill()


if __name__ == "__main__":
    execute_ci_chaos_test()
