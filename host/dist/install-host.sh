#!/bin/sh
# Installs the Orbit host for the current user on the Sunshine machine: the app (with its own Java runtime), an entry
# in the application menu, and a start at login, where Orbit waits in the tray. Run it in your desktop session, from
# the unzipped orbit-host bundle; running it again updates Orbit and keeps its settings.
# Usage: ./install-host.sh [--no-autostart] [--no-start]
#        ./install-host.sh --uninstall
#   --no-autostart  doesn't start Orbit at login
#   --no-start      doesn't start Orbit now
set -eu

here=$(cd "$(dirname "$0")" && pwd)
data=${XDG_DATA_HOME:-$HOME/.local/share}
config=${XDG_CONFIG_HOME:-$HOME/.config}
app_dir=$data/orbit-host
launcher=$app_dir/Orbit/bin/Orbit
icon=$app_dir/orbit.png
menu_entry=$data/applications/orbit.desktop
autostart_entry=$config/autostart/orbit.desktop
# Orbit's port for the agents: host.json's, else the default.
port=$(sed -n 's/.*"port" *: *\([1-9][0-9]*\).*/\1/p' "$config/orbit/host.json" 2>/dev/null | head -n 1)
port=${port:-47900}

autostart=yes
start=yes
uninstall=no
for arg in "$@"; do
    case "$arg" in
        --no-autostart) autostart=no ;;
        --no-start) start=no ;;
        --uninstall) uninstall=yes ;;
        *) echo "usage: $(basename "$0") [--no-autostart] [--no-start] | --uninstall" >&2; exit 2 ;;
    esac
done

if [ "$(id -u)" -eq 0 ]; then
    echo "Run this as the user whose desktop Orbit runs in, not as root." >&2
    exit 1
fi

# The installed Orbit's processes, found by the program they run (never by a pattern, which can match this shell).
installed_pids() {
    for exe in /proc/[0-9]*/exe; do
        if [ "$(readlink "$exe" 2>/dev/null)" = "$launcher" ]; then
            pid=${exe#/proc/}
            echo "${pid%/exe}"
        fi
    done
}

# Quits the installed Orbit, which turns monitors it switched off back on and restores their modes on the way out.
stop_installed() {
    pids=$(installed_pids)
    [ -n "$pids" ] || return 0
    echo "Quitting the running Orbit…"
    # shellcheck disable=SC2086 # one PID per word
    kill $pids 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        [ -n "$(installed_pids)" ] || return 0
        sleep 1
    done
    echo "Orbit didn't quit within 10 s; stopping it." >&2
    # shellcheck disable=SC2086
    kill -9 $pids 2>/dev/null || true
}

if [ "$uninstall" = yes ]; then
    stop_installed
    rm -f "$menu_entry" "$autostart_entry"
    rm -rf "$app_dir"
    command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q "$data/applications" || true
    echo "Orbit removed. Its settings and paired clients stay in $config/orbit."
    exit 0
fi

if [ ! -x "$here/Orbit/bin/Orbit" ]; then
    echo "No Orbit app next to this script ($here/Orbit); run it from the unzipped orbit-host bundle." >&2
    exit 1
fi
if ! command -v sunshine >/dev/null 2>&1 && ! flatpak info dev.lizardbyte.app.Sunshine >/dev/null 2>&1; then
    echo "warning: Sunshine not found. Orbit runs on the machine Sunshine streams from; install Sunshine there." >&2
fi
case "${XDG_CURRENT_DESKTOP:-}" in
    *KDE*) ;;
    *) echo "note: matching the monitor's resolution and placing windows need KDE Plasma; streams work without." ;;
esac

# 1. The app, replacing an older one only once the copy is complete.
stop_installed
mkdir -p "$data"
rm -rf "$app_dir.new"
mkdir "$app_dir.new"
cp -a "$here/Orbit" "$app_dir.new/Orbit"
install -m 644 "$here/orbit.png" "$app_dir.new/orbit.png"
rm -rf "$app_dir"
mv "$app_dir.new" "$app_dir"

# 2. The application menu entry, and the same entry to start Orbit at login.
mkdir -p "$(dirname "$menu_entry")"
cat > "$menu_entry" <<EOF
[Desktop Entry]
Type=Application
Name=Orbit
GenericName=Moonlight stream control
Comment=Start and control Moonlight streams from your Sunshine host
Exec="$launcher"
Icon=$icon
Terminal=false
Categories=Game;
EOF
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q "$data/applications" || true
if [ "$autostart" = yes ]; then
    mkdir -p "$(dirname "$autostart_entry")"
    cp "$menu_entry" "$autostart_entry"
else
    rm -f "$autostart_entry"
fi

echo "Orbit installed in $app_dir."
if [ "$autostart" = yes ]; then
    echo "It starts at each login and waits in the tray."
fi
if command -v firewall-cmd >/dev/null 2>&1 || command -v ufw >/dev/null 2>&1; then
    echo "If a firewall runs here, clients need $port/tcp (Orbit) and 5353/udp (mDNS) open, e.g."
    echo "  sudo firewall-cmd --permanent --add-port=$port/tcp --add-service=mdns && sudo firewall-cmd --reload"
fi

# 3. Start it now, unless something else (e.g. Orbit run from a build) already has its port.
if [ "$start" = no ]; then
    exit 0
fi
if [ -z "${WAYLAND_DISPLAY:-}" ] && [ -z "${DISPLAY:-}" ]; then
    echo "No desktop session here; Orbit starts at your next desktop login."
    exit 0
fi
if command -v ss >/dev/null 2>&1 && ss -Hltn "sport = :$port" | grep -q .; then
    echo "Something else listens on port $port already (another Orbit?); not starting Orbit now." >&2
    exit 0
fi
setsid "$launcher" >/dev/null 2>&1 < /dev/null &
echo "Orbit started; it's in the tray. Without a Sunshine login yet, its window opens to ask for one."
