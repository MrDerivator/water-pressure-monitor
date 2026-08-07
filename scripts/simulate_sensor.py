#!/usr/bin/env python3
"""
Water Pressure Monitor — ESP32 simulator

Sends fake measurements to your deployed backend, mimicking what the real
ESP32 sketch does: same endpoint, same X-Api-Key header, same JSON shape.
Useful for testing the dashboard and alert engine without real hardware.

Setup:
    pip install requests

Basic usage (steady realistic readings every 2 minutes, like the real sensor):
    python simulate_sensor.py --url https://your-app.up.railway.app --api-key YOUR_KEY

Faster testing (every 5 seconds instead of every 2 minutes):
    python simulate_sensor.py --url https://your-app.up.railway.app --api-key YOUR_KEY --interval 5

Scenarios (see --help for all of them):
    python simulate_sensor.py --url ... --api-key ... --scenario leak
    python simulate_sensor.py --url ... --api-key ... --scenario low
    python simulate_sensor.py --url ... --api-key ... --scenario offline

Stop anytime with Ctrl+C.
"""

import argparse
import random
import sys
import time
from datetime import datetime

try:
    import requests
except ImportError:
    print("This script needs the 'requests' library. Install it with:\n    pip install requests")
    sys.exit(1)


def log(msg: str) -> None:
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}")


def send_measurement(url: str, api_key: str, psi: float, timeout: int = 10) -> bool:
    """POSTs one reading, same as the ESP32 firmware does. Returns True on success."""
    endpoint = url.rstrip("/") + "/api/measurements"
    try:
        resp = requests.post(
            endpoint,
            json={"pressurePsi": round(psi, 2)},
            headers={"X-Api-Key": api_key, "Content-Type": "application/json"},
            timeout=timeout,
        )
    except requests.RequestException as e:
        log(f"Request failed: {e}")
        return False

    if resp.status_code == 201:
        log(f"Sent {psi:.2f} PSI -> 201 Created")
        return True

    log(f"Sent {psi:.2f} PSI -> {resp.status_code} {resp.text}")
    return False


def steady_stream(args):
    """Normal operation: baseline pressure with small realistic noise (random walk)."""
    psi = args.baseline
    log(f"Simulating steady readings around {args.baseline} PSI, every {args.interval}s. Ctrl+C to stop.")
    while True:
        psi += random.uniform(-1.5, 1.5)
        psi = max(0, min(psi, 174))  # clamp to sensor range
        send_measurement(args.url, args.api_key, psi)
        time.sleep(args.interval)


def leak_scenario(args):
    """
    Simulates a slow leak: steady baseline for a bit, then a gradual decline
    over several minutes -- should trigger the SUDDEN_DROP rule once the
    drop exceeds the configured delta within the configured window
    (defaults: >15 PSI within 10 min), and eventually LOW_PRESSURE if it
    keeps falling below threshold (default 20 PSI).
    """
    psi = args.baseline
    log(f"Simulating a LEAK starting from {psi} PSI. Ctrl+C to stop.")
    log("Phase 1: normal readings for 60s...")
    t0 = time.time()
    while time.time() - t0 < 60:
        psi += random.uniform(-1, 1)
        send_measurement(args.url, args.api_key, psi)
        time.sleep(args.interval)

    log("Phase 2: pressure declining steadily (simulated leak)...")
    while psi > 5:
        psi -= random.uniform(2, 4)
        psi = max(psi, 0)
        send_measurement(args.url, args.api_key, psi)
        time.sleep(args.interval)

    log("Phase 3: holding near zero (pipe empty)...")
    while True:
        psi = random.uniform(0, 3)
        send_measurement(args.url, args.api_key, psi)
        time.sleep(args.interval)


def low_scenario(args):
    """Instantly sends a single low-pressure reading to test the LOW_PRESSURE alert."""
    log("Sending one low-pressure reading to trigger the LOW_PRESSURE alert...")
    send_measurement(args.url, args.api_key, args.low_value)
    log("Done. Check Telegram / the dashboard alerts list.")


def burst_scenario(args):
    """Simulates a sudden burst pipe: high pressure, then an instant sharp drop."""
    log(f"Sending baseline reading ({args.baseline} PSI)...")
    send_measurement(args.url, args.api_key, args.baseline)
    time.sleep(2)
    dropped = max(0, args.baseline - 40)
    log(f"Simulating a burst: instant drop to {dropped} PSI...")
    send_measurement(args.url, args.api_key, dropped)
    log("Done. This should trigger SUDDEN_DROP (and likely LOW_PRESSURE too).")


def offline_scenario(args):
    """Sends a few normal readings, then stops -- to test the offline watchdog."""
    log(f"Sending {args.count} normal readings, then going silent to simulate the sensor dying...")
    psi = args.baseline
    for _ in range(args.count):
        psi += random.uniform(-1.5, 1.5)
        send_measurement(args.url, args.api_key, psi)
        time.sleep(args.interval)
    log(f"Done sending. Now staying silent — the OFFLINE alert should fire after "
        f"your configured offline_timeout_minutes (default 10 min) has passed with no data.")
    log("This script will just idle now; press Ctrl+C to exit whenever.")
    while True:
        time.sleep(60)


SCENARIOS = {
    "steady": steady_stream,
    "leak": leak_scenario,
    "low": low_scenario,
    "burst": burst_scenario,
    "offline": offline_scenario,
}


def main():
    parser = argparse.ArgumentParser(
        description="Simulate an ESP32 water pressure sensor sending readings to your backend.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Scenarios:
  steady   Continuous realistic readings around --baseline (default). Good for
           general dashboard testing.
  leak     Normal readings, then a gradual decline over minutes -- tests
           SUDDEN_DROP and LOW_PRESSURE together, like a real leak would.
  low      One single low reading (--low-value, default 15) -- quick test of
           the LOW_PRESSURE alert only.
  burst    Baseline reading immediately followed by a sharp instant drop --
           quick test of the SUDDEN_DROP alert only.
  offline  Sends a handful of readings then goes silent -- tests the OFFLINE
           watchdog (wait for your configured offline_timeout_minutes).
        """,
    )
    parser.add_argument("--url", required=True, help="Your Railway app URL, e.g. https://your-app.up.railway.app")
    parser.add_argument("--api-key", required=True, help="Your APP_DEVICE_API_KEY value")
    parser.add_argument("--scenario", choices=SCENARIOS.keys(), default="steady", help="Which scenario to run (default: steady)")
    parser.add_argument("--interval", type=float, default=120, help="Seconds between readings (default: 120, matching the real ESP32's 2-minute cadence)")
    parser.add_argument("--baseline", type=float, default=55, help="Baseline pressure in PSI for steady/leak/burst scenarios (default: 55)")
    parser.add_argument("--low-value", type=float, default=15, help="PSI value to send for the 'low' scenario (default: 15)")
    parser.add_argument("--count", type=int, default=3, help="Number of readings to send before going silent, for 'offline' scenario (default: 3)")
    args = parser.parse_args()

    try:
        SCENARIOS[args.scenario](args)
    except KeyboardInterrupt:
        log("Stopped.")


if __name__ == "__main__":
    main()
