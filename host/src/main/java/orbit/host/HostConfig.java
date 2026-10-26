package orbit.host;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import orbit.protocol.JsonFiles;
import orbit.protocol.Protocol;

/**
 * Host settings, stored in {@code host.json} in the Orbit config directory and edited in {@link HostSettingsDialog}.
 * Missing values get their defaults, so a file from an older Orbit reads the same.
 *
 * @param port          TCP port of the agent WebSocket endpoint
 * @param advertiseName mDNS instance name agents see; null for the machine's hostname
 * @param theme         "system", "light" or "dark"; follows the system if absent
 * @param startInTray   with a tray icon, Orbit starts with its window hidden there; on if absent
 */
public record HostConfig(int port, String advertiseName, String theme, Sunshine sunshine, Monitor monitor,
                         Stream stream, Boolean startInTray) {

    public static final String FILE_NAME = "host.json";
    static final String DEFAULT_SUNSHINE_URL = "https://localhost:47990";

    public HostConfig {
        port = port > 0 ? port : Protocol.DEFAULT_PORT;
        advertiseName = advertiseName != null && !advertiseName.isBlank() ? advertiseName.strip() : null;
        sunshine = sunshine != null ? sunshine : new Sunshine(null, null, null);
        monitor = monitor != null ? monitor : new Monitor(null, null);
        stream = stream != null ? stream : new Stream(null, null, null, null, null);
        startInTray = !Boolean.FALSE.equals(startInTray);
    }

    public HostConfig(int port, String advertiseName, String theme, Sunshine sunshine, Monitor monitor,
            Stream stream) {
        this(port, advertiseName, theme, sunshine, monitor, stream, null);
    }

    /**
     * The Sunshine web UI's address and login.
     *
     * @param password the file is written owner-readable only
     */
    public record Sunshine(String url, String user, String password) {
        public Sunshine {
            url = url != null && !url.isBlank() ? url.strip() : DEFAULT_SUNSHINE_URL;
        }

        public boolean hasCredentials() {
            return user != null && !user.isBlank() && password != null;
        }

        public URI uri() {
            return URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
        }
    }

    /**
     * Sunshine's monitor around streams.
     *
     * @param turnOffWhenIdle turn it off while nothing streams; off if absent
     * @param matchResolution set its mode to the client's resolution and frame rate for a stream; on if absent
     */
    public record Monitor(Boolean turnOffWhenIdle, Boolean matchResolution) {
        public Monitor {
            turnOffWhenIdle = Boolean.TRUE.equals(turnOffWhenIdle);
            matchResolution = !Boolean.FALSE.equals(matchResolution);
        }
    }

    /**
     * What the desktop integration does during a stream; each is on if absent, except {@code unlockScreen}.
     *
     * @param placeWindows   moves the app's windows to Sunshine's monitor, with focus and the cursor
     * @param launchSplash   "Starting <app>" on Sunshine's monitor until the app is up; needs {@code placeWindows}
     * @param startNotice    the banner asking whoever is at the host to leave mouse and keyboard alone; likewise
     * @param hideIdleCursor hides the cursor in the TV remote's key mode
     * @param remoteNotices  shows the TV remote's mode switches and Stop on Sunshine's monitor
     * @param unlockScreen   unlocks a locked session for a stream, without its password; off if absent, since it
     *                       unlocks every monitor
     */
    public record Stream(Boolean placeWindows, Boolean launchSplash, Boolean startNotice, Boolean hideIdleCursor,
                         Boolean remoteNotices, Boolean unlockScreen) {
        public Stream {
            placeWindows = !Boolean.FALSE.equals(placeWindows);
            launchSplash = !Boolean.FALSE.equals(launchSplash);
            startNotice = !Boolean.FALSE.equals(startNotice);
            hideIdleCursor = !Boolean.FALSE.equals(hideIdleCursor);
            remoteNotices = !Boolean.FALSE.equals(remoteNotices);
            unlockScreen = Boolean.TRUE.equals(unlockScreen);
        }

        public Stream(Boolean placeWindows, Boolean launchSplash, Boolean startNotice, Boolean hideIdleCursor,
                Boolean remoteNotices) {
            this(placeWindows, launchSplash, startNotice, hideIdleCursor, remoteNotices, null);
        }
    }

    public static HostConfig defaults() {
        return new HostConfig(0, null, null, null, null, null);
    }

    public static HostConfig load(Path file) throws IOException {
        return JsonFiles.read(file, Stored.class).map(Stored::config).orElseGet(HostConfig::defaults);
    }

    public void save(Path file) throws IOException {
        JsonFiles.write(file, this);
    }

    public URI sunshineUri() {
        return sunshine.uri();
    }

    public boolean hasSunshineCredentials() {
        return sunshine.hasCredentials();
    }

    /** {@code host.json} as read: the sections, or the flat fields Orbit wrote before it had them. */
    record Stored(int port, String advertiseName, String theme, Sunshine sunshine, Monitor monitor, Stream stream,
                  Boolean startInTray, String sunshineUrl, String sunshineUser, String sunshinePassword,
                  Boolean turnOffStreamingMonitor) {

        HostConfig config() {
            return new HostConfig(port, advertiseName, theme,
                    sunshine != null ? sunshine : new Sunshine(sunshineUrl, sunshineUser, sunshinePassword),
                    monitor != null ? monitor : new Monitor(turnOffStreamingMonitor, null), stream, startInTray);
        }
    }
}
