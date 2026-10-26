package orbit.tray.sni;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import orbit.tray.TrayMenu;
import org.freedesktop.dbus.types.Variant;

/**
 * Turns a {@link TrayMenu} into numbered {@code com.canonical.dbusmenu} items. An item keeps its id across menu
 * updates (by its key), so a click on a menu the shell still shows from before an update reaches the right item.
 * Every item carries all of its properties: Plasma's dbusmenu client updates the properties it is sent and keeps
 * the old value of any that are left out.
 * The snapshots it builds are immutable.
 */
final class MenuTree {

    static final int ROOT = 0;

    /** One menu item. {@code action} is null for submenus, labels and separators. */
    record Node(int id, Map<String, Variant<?>> properties, List<Node> children, boolean enabled, Runnable action) {
    }

    /** A complete menu. */
    record Snapshot(Node root, Map<Integer, Node> byId) {

        /** The root and every submenu. */
        List<Integer> menuIds() {
            return byId.values().stream().filter(n -> n.id() == ROOT || !n.children().isEmpty()).map(Node::id)
                    .sorted().toList();
        }

        DBusMenu.Layout layout(int id, int depth) {
            Node node = byId.get(id);
            if (node == null) {
                return new DBusMenu.Layout(id, Map.of(), List.of());
            }
            return layout(node, depth);
        }

        private static DBusMenu.Layout layout(Node node, int depth) {
            List<Variant<?>> children = new ArrayList<>();
            if (depth != 0) {
                for (Node child : node.children()) {
                    children.add(new Variant<>(layout(child, depth - 1), DBusMenu.Layout.SIGNATURE));
                }
            }
            return new DBusMenu.Layout(node.id(), node.properties(), children);
        }
    }

    private final Map<String, Integer> ids = new HashMap<>();
    private int nextId = ROOT + 1;

    synchronized Snapshot build(List<TrayMenu.Entry> entries) {
        Map<Integer, Node> byId = new HashMap<>();
        Map<String, Variant<?>> rootProperties = new LinkedHashMap<>();
        rootProperties.put("children-display", new Variant<>("submenu"));
        Node root = new Node(ROOT, rootProperties, children("", entries, byId), true, null);
        byId.put(ROOT, root);
        return new Snapshot(root, Map.copyOf(byId));
    }

    private List<Node> children(String parentKey, List<TrayMenu.Entry> entries, Map<Integer, Node> byId) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Node node = switch (entries.get(i)) {
                case TrayMenu.Separator s -> {
                    Map<String, Variant<?>> p = new LinkedHashMap<>();
                    p.put("type", new Variant<>("separator"));
                    yield new Node(id(parentKey + "/-" + i), p, List.of(), false, null);
                }
                case TrayMenu.Item item -> {
                    Map<String, Variant<?>> p = new LinkedHashMap<>();
                    // An underscore marks the access key in dbusmenu labels; a literal one is doubled.
                    p.put("label", new Variant<>(item.label().replace("_", "__")));
                    p.put("enabled", new Variant<>(item.enabled()));
                    p.put("icon-name", new Variant<>(item.iconName() != null ? item.iconName() : ""));
                    if (item.iconPng() != null) {
                        p.put("icon-data", new Variant<>(item.iconPng()));
                    }
                    // Sent for every item, as Plasma keeps a property it was once sent; "" means no checkmark.
                    p.put("toggle-type", new Variant<>(item.checked() != null ? "checkmark" : ""));
                    p.put("toggle-state", new Variant<>(Boolean.TRUE.equals(item.checked()) ? 1 : 0));
                    List<Node> children = children(item.key(), item.children(), byId);
                    if (!children.isEmpty()) {
                        p.put("children-display", new Variant<>("submenu"));
                    }
                    yield new Node(id(item.key()), p, children, item.enabled(), item.action());
                }
            };
            byId.put(node.id(), node);
            nodes.add(node);
        }
        return nodes;
    }

    private int id(String key) {
        return ids.computeIfAbsent(key, k -> nextId++);
    }
}
