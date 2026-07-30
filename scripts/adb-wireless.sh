#!/usr/bin/env bash
# Wirelessly attach the phone for future installs (same Wi‑Fi as this PC required).
#
# One-time (USB plugged in):
#   ./scripts/adb-wireless.sh setup
#
# Later (USB unplugged, same LAN):
#   ./scripts/adb-wireless.sh
#
# Tip: PC is on 10.10.20.x — phone must join that same Wi‑Fi (not guest/LTE).
set -euo pipefail

PORT="${ADB_PORT:-5555}"
IP_FILE="$(dirname "$0")/.adb-wireless-ip"

discover_ip() {
  adb shell "ip -f inet addr show wlan0 2>/dev/null" \
    | awk '/inet /{print $2}' | cut -d/ -f1 | head -1
}

cmd="${1:-connect}"

case "$cmd" in
  setup)
    echo "Enabling TCP mode on USB device…"
    adb tcpip "$PORT"
    sleep 2
    IP="$(discover_ip)"
    if [[ -z "$IP" ]]; then
      echo "Could not read wlan0 IP. Connect phone to the same Wi‑Fi as this PC, then retry."
      exit 1
    fi
    echo "$IP" > "$IP_FILE"
    echo "Phone Wi‑Fi IP: $IP (saved to $IP_FILE)"
    echo "Connecting…"
    adb connect "${IP}:${PORT}"
    adb devices -l
    echo
    echo "If connect fails: phone and PC must share one LAN (not guest / mobile hotspot mismatch)."
    ;;
  *)
    IP="${1:-}"
    if [[ -z "$IP" || "$IP" == "connect" ]]; then
      if [[ -f "$IP_FILE" ]]; then
        IP="$(cat "$IP_FILE")"
      else
        echo "No saved IP. Run: ./scripts/adb-wireless.sh setup   (USB once)"
        exit 1
      fi
    fi
    adb connect "${IP}:${PORT}"
    adb devices -l
    ;;
esac
