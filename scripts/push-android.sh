#!/usr/bin/env bash
# Build and push the StaticGroot debug APK to one or more Android TV devices over ADB,
# whitelist Tailscale from Android TV standby if present, and launch the app.
#
# Usage:
#   ./scripts/push-android.sh 10.69.69.93
#   ./scripts/push-android.sh 10.69.69.93 100.64.35.62
#   ./scripts/push-android.sh --no-build 10.69.69.93
#   ./scripts/push-android.sh --apk /path/to/app-debug.apk 10.69.69.93
#   ./scripts/push-android.sh                  # uses ADB_TARGETS, adb.targets in local.properties, or connected adb devices
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
DO_BUILD=1
TARGETS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --no-build)
      DO_BUILD=0
      shift
      ;;
    --build)
      DO_BUILD=1
      shift
      ;;
    --apk)
      APK="$2"
      DO_BUILD=0
      shift 2
      ;;
    -h|--help)
      sed -n '2,10p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *)
      TARGETS+=("$1")
      shift
      ;;
  esac
done

find_jdk17() {
  if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "17\.'; then
    echo "$JAVA_HOME"
    return 0
  fi
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local mac_jdk
    mac_jdk="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    if [ -n "$mac_jdk" ]; then
      echo "$mac_jdk"
      return 0
    fi
  fi
  for candidate in /usr/lib/jvm/java-17-openjdk* /usr/lib/jvm/temurin-17* /Library/Java/JavaVirtualMachines/*17*/Contents/Home; do
    if [ -x "$candidate/bin/java" ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

find_android_sdk() {
  for var in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    if [ -n "$var" ] && [ -d "$var" ]; then
      echo "$var"
      return 0
    fi
  done
  if [ -f "$ROOT/android/local.properties" ]; then
    local from_prop
    from_prop="$(grep -E '^sdk\.dir=' "$ROOT/android/local.properties" | head -n1 | cut -d= -f2- || true)"
    if [ -n "$from_prop" ] && [ -d "$from_prop" ]; then
      echo "$from_prop"
      return 0
    fi
  fi
  for dir in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "/opt/android-sdk" "/usr/lib/android-sdk"; do
    if [ -d "$dir" ]; then
      echo "$dir"
      return 0
    fi
  done
  return 1
}

discover_lan_jellyfin() {
  command -v node >/dev/null 2>&1 || return 0
  node -e '
    const dgram = require("dgram");
    const sock = dgram.createSocket("udp4");
    const timer = setTimeout(() => { try { sock.close(); } catch {} }, 1200);
    sock.on("message", (msg, rinfo) => {
      try {
        const json = JSON.parse(msg.toString("utf8"));
        const addr = (json.Address || "").trim() || `http://${rinfo.address}:8096`;
        if (addr) { process.stdout.write(addr); clearTimeout(timer); sock.close(); }
      } catch {}
    });
    sock.bind(0, () => {
      sock.setBroadcast(true);
      const buf = Buffer.from("who is JellyfinServer?");
      sock.send(buf, 0, buf.length, 7359, "255.255.255.255");
    });
  ' 2>/dev/null || true
}

if [ "$DO_BUILD" -eq 1 ]; then
  if JDK17="$(find_jdk17)"; then
    export JAVA_HOME="$JDK17"
  fi
  if SDK_DIR="$(find_android_sdk)"; then
    export ANDROID_HOME="$SDK_DIR"
  fi
  if ! grep -q '^DEFAULT_JELLYFIN_URLS=' "$ROOT/android/local.properties" 2>/dev/null && [ -z "${DEFAULT_JELLYFIN_URLS:-}" ]; then
    DISCOVERED_URL="$(discover_lan_jellyfin)"
    if [ -n "$DISCOVERED_URL" ]; then
      echo "==> Discovered Jellyfin on LAN: $DISCOVERED_URL"
      export DEFAULT_JELLYFIN_URLS="$DISCOVERED_URL"
    fi
  fi
  echo "==> Building debug APK"
  "$ROOT/android/gradlew" -p "$ROOT/android" :app:assembleDebug
fi

if [ ! -f "$APK" ]; then
  echo "ERROR: APK not found at $APK" >&2
  exit 1
fi

if [ "${#TARGETS[@]}" -eq 0 ] && [ -n "${ADB_TARGETS:-}" ]; then
  read -r -a TARGETS <<< "${ADB_TARGETS//,/ }"
fi

if [ "${#TARGETS[@]}" -eq 0 ] && [ -f "$ROOT/android/local.properties" ]; then
  PROP_TARGETS="$(grep -E '^adb\.targets=' "$ROOT/android/local.properties" | head -n1 | cut -d= -f2- || true)"
  if [ -n "$PROP_TARGETS" ]; then
    read -r -a TARGETS <<< "${PROP_TARGETS//,/ }"
  fi
fi

if [ "${#TARGETS[@]}" -eq 0 ]; then
  while IFS= read -r line; do
    [ -n "$line" ] && TARGETS+=("$line")
  done < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
fi

if [ "${#TARGETS[@]}" -eq 0 ]; then
  echo "Usage: $0 <tv-ip-or-host> [more-tvs...]" >&2
  echo "No ADB targets specified and no devices currently connected." >&2
  exit 1
fi

for raw_target in "${TARGETS[@]}"; do
  target="$raw_target"
  # Append :5555 if target looks like a host/IP without a port and isn't already a connected USB serial.
  if [[ "$target" != *:* ]] && ! adb devices | awk 'NR>1 {print $1}' | grep -Fxq "$target"; then
    target="${target}:5555"
  fi

  if [[ "$target" == *:* ]]; then
    echo "==> Connecting to $target"
    adb connect "$target"
  fi

  echo "==> Installing $APK on $target"
  adb -s "$target" install -r "$APK"

  if adb -s "$target" shell pm list packages com.tailscale.ipn 2>/dev/null | grep -q 'com.tailscale.ipn'; then
    echo "==> Keeping Tailscale awake in standby on $target"
    adb -s "$target" shell dumpsys deviceidle whitelist +com.tailscale.ipn >/dev/null 2>&1 || true
    adb -s "$target" shell cmd appops set com.tailscale.ipn RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true
    adb -s "$target" shell cmd appops set com.tailscale.ipn RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
    adb -s "$target" shell settings put secure always_on_vpn_app com.tailscale.ipn >/dev/null 2>&1 || true
  fi

  echo "==> Launching StaticGroot on $target"
  adb -s "$target" shell am start -S -n io.github.madmax3553.staticgroot/.MainActivity
done

echo "==> Done."
