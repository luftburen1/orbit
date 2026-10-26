package orbit.tray;

import java.util.List;

/** The tray icon's menu, independent of how a tray backend shows it. */
public final class TrayMenu {

    private TrayMenu() {
    }

    public sealed interface Entry permits Item, Separator {
    }

    /**
     * A clickable item, or a submenu when it has children.
     *
     * @param key stays the same for the same item across menu updates, so a click on a menu that was open while it
     *            changed still reaches the item the user saw
     * @param action runs on the Swing event thread; null for submenus and labels
     * @param iconName a freedesktop icon theme name, so the icon follows the desktop's theme; optional
     * @param checked  for an item with a checkmark, whether it's ticked; null for a plain item
     * @param iconPng  a PNG for an icon no icon theme has, in place of {@code iconName}; optional, and shown only
     *                 by the D-Bus tray
     */
    public record Item(String key, String label, boolean enabled, Runnable action, String iconName,
            List<Entry> children, Boolean checked, byte[] iconPng)
            implements Entry {

        public Item {
            children = List.copyOf(children);
        }

        public Item(String key, String label, boolean enabled, Runnable action, String iconName,
                List<Entry> children) {
            this(key, label, enabled, action, iconName, children, null, null);
        }

        /** This item with {@code png} as its icon instead of a theme icon. */
        public Item withIconPng(byte[] png) {
            return new Item(key, label, enabled, action, null, children, checked, png);
        }

        /** An item with a checkmark; clicking it runs {@code action}, which should flip the setting. */
        public static Item toggle(String key, String label, boolean checked, Runnable action) {
            return new Item(key, label, true, action, null, List.of(), checked, null);
        }

        public static Item action(String key, String label, String iconName, boolean enabled, Runnable action) {
            return new Item(key, label, enabled, action, iconName, List.of());
        }

        public static Item submenu(String key, String label, List<Entry> children) {
            return submenu(key, label, null, children);
        }

        public static Item submenu(String key, String label, String iconName, List<Entry> children) {
            return new Item(key, label, true, null, iconName, children);
        }

        public static Item label(String key, String label) {
            return new Item(key, label, false, null, null, List.of());
        }
    }

    public record Separator() implements Entry {
    }
}
