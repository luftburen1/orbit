package orbit.agent;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/**
 * The list of Sunshine apps on the idle screen, picked with the TV remote: up and down move, OK or Play starts.
 *
 * @param apps     the apps' names, in Sunshine's order
 * @param icons    each app's cover, in the same order, null where it has none; empty when no app has one
 * @param selected index of the highlighted app
 */
record AppMenu(List<String> apps, List<BufferedImage> icons, int selected) {

    private static final Logger LOG = Logger.getLogger(AppMenu.class.getName());

    AppMenu {
        apps = List.copyOf(apps);
        if (apps.isEmpty()) {
            throw new IllegalArgumentException("no apps");
        }
        // Icons that don't match the apps one to one would go on the wrong rows.
        icons = icons.size() == apps.size() && icons.stream().anyMatch(i -> i != null)
                ? Collections.unmodifiableList(new ArrayList<>(icons)) : List.of();
        selected = Math.floorMod(selected, apps.size());
    }

    AppMenu(List<String> apps, int selected) {
        this(apps, List.of(), selected);
    }

    /** Opens on {@code lastApp}, or the first app if it isn't in the list. */
    static AppMenu of(List<String> apps, String lastApp) {
        return of(apps, null, lastApp);
    }

    /**
     * Opens on {@code lastApp}, or the first app if it isn't in the list.
     *
     * @param icons as the host sends them ({@code Message.AppList.icons}): PNGs in Base64, or null
     */
    static AppMenu of(List<String> apps, List<String> icons, String lastApp) {
        List<BufferedImage> images = new ArrayList<>();
        if (icons != null) {
            icons.forEach(icon -> images.add(decode(icon)));
        }
        return new AppMenu(apps, images, lastApp == null ? 0 : Math.max(0, apps.indexOf(lastApp)));
    }

    private static BufferedImage decode(String icon) {
        if (icon == null) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(java.util.Base64.getDecoder().decode(icon)));
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(Level.FINE, "cannot read an app's icon", e);
            return null;
        }
    }

    /** The next app down, wrapping around to the top. */
    AppMenu down() {
        return new AppMenu(apps, icons, selected + 1);
    }

    /** The next app up, wrapping around to the bottom. */
    AppMenu up() {
        return new AppMenu(apps, icons, selected - 1);
    }

    String app() {
        return apps.get(selected);
    }

    /** The icon of the app at {@code index}, or null. */
    BufferedImage icon(int index) {
        return icons.isEmpty() ? null : icons.get(index);
    }

    /** Whether any app has an icon, so the rows make room for them. */
    boolean hasIcons() {
        return !icons.isEmpty();
    }

    /**
     * Which apps to show when only {@code rows} fit: a run of them that keeps the selected one in view, as near the
     * middle as the list allows. Returns the index of the first.
     */
    int firstVisible(int rows) {
        if (apps.size() <= rows) {
            return 0;
        }
        return Math.clamp(selected - rows / 2, 0, apps.size() - rows);
    }
}
