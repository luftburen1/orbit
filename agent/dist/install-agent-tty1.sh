#!/bin/sh
# Installs the Orbit agent on a client without a desktop: the current user is logged in
# automatically on the text console (tty1), and that login starts the agent. Moonlight then draws straight to the
# display. Running it again updates the agent, which restarts with the new files; its settings are kept.
#
# Run it as that user (not as root); it uses sudo for the system-wide steps.
# Usage: ./install-agent-tty1.sh [path/to/orbit-agent.jar]
#        ./install-agent-tty1.sh --uninstall
set -eu

SUDO=${SUDO:-sudo}
here=$(cd "$(dirname "$0")" && pwd)
user=$(id -un)
share=${XDG_DATA_HOME:-$HOME/.local/share}/orbit
state=${XDG_STATE_HOME:-$HOME/.local/state}/orbit
runner=$share/run-agent-tty1.sh
getty_dir=/etc/systemd/system/getty@tty1.service.d
getty_conf=$getty_dir/orbit-autologin.conf
uinput_rule=/etc/udev/rules.d/60-orbit-uinput.rules
uinput_module=/etc/modules-load.d/orbit-uinput.conf
begin='# >>> orbit-agent >>>'
end='# <<< orbit-agent <<<'

if [ "$(id -u)" -eq 0 ]; then
    echo "Run this as the user who should be logged in on tty1, not as root." >&2
    exit 1
fi

# The login script the shell reads on the console.
profile_file() {
    case "$(basename "${SHELL:-/bin/sh}")" in
        zsh) echo "$HOME/.zprofile" ;;
        bash)
            for f in "$HOME/.bash_profile" "$HOME/.bash_login"; do
                if [ -f "$f" ]; then echo "$f"; return; fi
            done
            echo "$HOME/.profile" ;;
        sh | dash | ash) echo "$HOME/.profile" ;;
        *)
            echo "Unsupported login shell ${SHELL:-?}; start $runner from its login script on tty1 yourself." >&2
            exit 1 ;;
    esac
}

remove_profile_block() {
    if [ -f "$1" ]; then
        sed -i "/^$begin\$/,/^$end\$/d" "$1"
    fi
}

if [ "${1:-}" = "--uninstall" ]; then
    profile=$(profile_file)
    remove_profile_block "$profile"
    rm -f "$runner"
    if [ -f "$getty_conf" ]; then
        $SUDO rm -f "$getty_conf"
        $SUDO rmdir "$getty_dir" 2>/dev/null || true
        $SUDO systemctl daemon-reload
    fi
    if [ -f "$uinput_rule" ] || [ -f "$uinput_module" ]; then
        $SUDO rm -f "$uinput_rule" "$uinput_module"
        $SUDO udevadm control --reload 2>/dev/null || true
    fi
    echo "Removed the tty1 autologin and the agent start from $profile."
    echo "The agent jar and its config (~/.config/orbit) are kept; reboot to get a normal login prompt back."
    exit 0
fi

jar=${1:-$here/orbit-agent.jar}
if [ ! -f "$jar" ]; then
    echo "Agent jar not found: $jar" >&2
    exit 1
fi

# Bundles from agentBundle carry their own Java runtime; the plain bundle needs Java 25 on the system.
if [ -x "$here/runtime/bin/java" ]; then
    bundled=yes
else
    bundled=
    java_major=$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p') || true
    if [ -z "${java_major:-}" ] || [ "$java_major" -lt 25 ] 2>/dev/null; then
        echo "Java 25 or newer is required (found: ${java_major:-none}); install it first (e.g. openjdk-25-jre-headless)." >&2
        exit 1
    fi
fi
if ! command -v moonlight >/dev/null 2>&1 && ! command -v moonlight-qt >/dev/null 2>&1 \
        && ! flatpak info com.moonlight_stream.Moonlight >/dev/null 2>&1; then
    echo "warning: Moonlight not found; install moonlight-qt or set moonlightCommand in ~/.config/orbit/agent.json." >&2
fi
# The agent tty1's loop runs, found by the jar it runs (never by a pattern, which can match this shell).
agent_pids() {
    for cmdline in /proc/[0-9]*/cmdline; do
        if tr '\0' '\n' < "$cmdline" 2>/dev/null | grep -qxF "$share/orbit-agent.jar"; then
            pid=${cmdline#/proc/}
            echo "${pid%/cmdline}"
        fi
    done
}

# 1. The agent, and its Java runtime if the bundle has one. A running agent is stopped first: it would keep using the
#    replaced files and load what's left of its runtime from the new one. The tty1 loop starts it again after 5 s.
running=$(agent_pids)
if [ -n "$running" ]; then
    # shellcheck disable=SC2086 # one PID per word
    kill $running 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        [ -n "$(agent_pids)" ] || break
        sleep 1
    done
fi
mkdir -p "$share" "$state"
install -m 644 "$jar" "$share/orbit-agent.jar"
rm -rf "$share/runtime"
if [ -n "$bundled" ]; then
    cp -a "$here/runtime" "$share/runtime"
    java_bin=$share/runtime/bin/java
else
    java_bin=$(command -v java)
fi

# 2. The script tty1's login runs: keeps the console quiet and restarts the agent if it exits.
cat > "$runner" <<EOF
#!/bin/sh
# Started by the login on tty1 (see $begin in $(profile_file)). Installed by install-agent-tty1.sh.
log=$state/agent.log
# No screen blanking or blinking cursor on top of the idle screen and the stream.
setterm --blank 0 --powersave off --powerdown 0 --cursor off 2>/dev/null || true
clear
while true; do
    if [ -f "\$log" ] && [ "\$(wc -c < "\$log")" -gt 5000000 ]; then
        mv -f "\$log" "\$log.1"
    fi
    echo "--- \$(date '+%F %T') starting the Orbit agent" >> "\$log"
    "$java_bin" -jar "$share/orbit-agent.jar" >> "\$log" 2>&1 || true
    echo "--- \$(date '+%F %T') the agent stopped; restarting in 5 s" >> "\$log"
    sleep 5
done
EOF
chmod 755 "$runner"

# 3. Start it from the login shell, but only on tty1 (not over SSH or on other consoles).
profile=$(profile_file)
remove_profile_block "$profile"
cat >> "$profile" <<EOF
$begin
if [ "\$(tty)" = /dev/tty1 ]; then
    exec "$runner"
fi
$end
EOF

# 4. Device access: video/render for the display, input for keyboards and controllers.
groups_added=
for group in video render input; do
    if getent group "$group" >/dev/null && ! id -nG "$user" | tr ' ' '\n' | grep -qx "$group"; then
        $SUDO usermod -aG "$group" "$user"
        groups_added="$groups_added $group"
    fi
done

# 5. The TV remote's mouse mode: the agent makes a virtual mouse and keyboard through /dev/uinput, which is root's
#    alone by default. The input group can read every keyboard already; this lets it type and point too.
$SUDO tee "$uinput_rule" >/dev/null <<'EOF'
# Installed by install-agent-tty1.sh: the Orbit agent's TV remote mouse.
KERNEL=="uinput", SUBSYSTEM=="misc", GROUP="input", MODE="0660", OPTIONS+="static_node=uinput"
EOF
echo uinput | $SUDO tee "$uinput_module" >/dev/null
$SUDO modprobe uinput 2>/dev/null || true
$SUDO udevadm control --reload 2>/dev/null || true
$SUDO udevadm trigger --subsystem-match=misc --sysname-match=uinput 2>/dev/null || true

# 6. Log this user in automatically on tty1.
agetty=$(command -v agetty || echo /sbin/agetty)
$SUDO mkdir -p "$getty_dir"
printf '[Service]\nExecStart=\nExecStart=-%s --autologin %s --noclear %%I $TERM\n' "$agetty" "$user" \
    | $SUDO tee "$getty_conf" >/dev/null
$SUDO systemctl daemon-reload

# 7. The desktop installer's service would run a second agent with the same identity.
if systemctl --user cat orbit-agent.service >/dev/null 2>&1; then
    systemctl --user disable --now orbit-agent.service >/dev/null 2>&1 || true
    echo "Disabled the orbit-agent systemd user service from install-agent.sh."
fi

echo "Orbit agent installed for $user on tty1."
if [ -n "$groups_added" ]; then
    echo "Added $user to:$groups_added."
fi
if [ -n "$running" ] && [ -z "$groups_added" ]; then
    echo "The running agent was stopped; tty1 starts the new one within a few seconds."
else
    echo "Reboot to start it (sudo reboot). Then approve this client in the Orbit window on the Sunshine host."
fi
echo "Logs: $state/agent.log. For a shell, use SSH or another console (Ctrl+Alt+F2)."
