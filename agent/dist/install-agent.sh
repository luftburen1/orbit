#!/bin/sh
# Installs the Orbit agent for the current user and starts it with the desktop session. Running it again updates the
# agent and restarts it; its settings are kept.
# Usage: ./install-agent.sh [path/to/orbit-agent.jar]
#        ./install-agent.sh --uninstall
set -eu

here=$(cd "$(dirname "$0")" && pwd)
share=${XDG_DATA_HOME:-$HOME/.local/share}/orbit
units=${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user
unit=$units/orbit-agent.service

if [ "${1:-}" = "--uninstall" ]; then
    systemctl --user disable --now orbit-agent.service 2>/dev/null || true
    rm -f "$unit"
    systemctl --user daemon-reload
    rm -rf "$share/orbit-agent.jar" "$share/runtime"
    echo "Orbit agent removed. Its settings stay in ${XDG_CONFIG_HOME:-$HOME/.config}/orbit."
    exit 0
fi

jar=${1:-$here/orbit-agent.jar}
if [ ! -f "$jar" ]; then
    echo "Agent jar not found: $jar" >&2
    exit 1
fi
# The console installer's agent would run alongside this one, with the same identity.
if [ -f "$share/run-agent-tty1.sh" ]; then
    echo "This client is set up to start the agent on tty1 (install-agent-tty1.sh); remove that first with" >&2
    echo "./install-agent-tty1.sh --uninstall, or update it by running install-agent-tty1.sh again." >&2
    exit 1
fi

# Bundles from agentBundle carry their own Java runtime; the plain bundle needs Java 25 on the system.
if [ -x "$here/runtime/bin/java" ]; then
    bundled=yes
else
    bundled=
    java_major=$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p') || true
    if [ -z "${java_major:-}" ] || [ "$java_major" -lt 25 ] 2>/dev/null; then
        echo "Java 25 or newer is required (found: ${java_major:-none}); install it first (e.g. openjdk-25-jre)." >&2
        exit 1
    fi
fi
if ! command -v moonlight >/dev/null 2>&1 && ! command -v moonlight-qt >/dev/null 2>&1 \
        && ! flatpak info com.moonlight_stream.Moonlight >/dev/null 2>&1; then
    echo "warning: Moonlight not found; install moonlight-qt and set moonlightCommand in ~/.config/orbit/agent.json if needed." >&2
fi

# A running agent would keep using the files replaced below, and load what's left of its runtime from the new one.
systemctl --user stop orbit-agent.service 2>/dev/null || true

mkdir -p "$share" "$units"
install -m 644 "$jar" "$share/orbit-agent.jar"
rm -rf "$share/runtime"
if [ -n "$bundled" ]; then
    cp -a "$here/runtime" "$share/runtime"
    sed 's|/usr/bin/env java|%h/.local/share/orbit/runtime/bin/java|' "$here/orbit-agent.service" > "$unit"
else
    install -m 644 "$here/orbit-agent.service" "$unit"
fi

systemctl --user daemon-reload
systemctl --user enable orbit-agent.service
systemctl --user restart orbit-agent.service

echo "Orbit agent installed and started. Approve this client in the Orbit window on the Sunshine host."
echo "Logs: journalctl --user -u orbit-agent -f"
