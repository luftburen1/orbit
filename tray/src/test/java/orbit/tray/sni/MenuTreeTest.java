package orbit.tray.sni;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import orbit.tray.TrayMenu.Entry;
import orbit.tray.TrayMenu.Item;
import orbit.tray.TrayMenu.Separator;
import org.junit.jupiter.api.Test;

class MenuTreeTest {

    private static List<Entry> menu(String status, boolean stopEnabled) {
        return List.of(
                Item.submenu("client/a", "living_room — " + status, List.of(
                        Item.action("client/a/stop", "Stop stream", "media-playback-stop", stopEnabled, () -> { }),
                        new Separator(),
                        Item.action("client/a/remove", "Remove client…", null, true, () -> { }))),
                new Separator(),
                Item.action("quit", "Quit Orbit", "application-exit", true, () -> { }));
    }

    @Test
    void buildsDbusmenuItems() {
        MenuTree.Snapshot s = new MenuTree().build(menu("Streaming", true));
        assertEquals(7, s.byId().size(), "root plus six items");

        MenuTree.Node client = s.root().children().getFirst();
        assertEquals("living__room — Streaming", client.properties().get("label").getValue(),
                "underscores are doubled so they don't become access keys");
        assertEquals("submenu", client.properties().get("children-display").getValue());
        MenuTree.Node stop = client.children().getFirst();
        assertEquals("media-playback-stop", stop.properties().get("icon-name").getValue());
        assertEquals(true, stop.properties().get("enabled").getValue(),
                "sent even though it is the default: Plasma keeps the old value of a property left out");
        MenuTree.Node remove = client.children().get(2);
        assertEquals("", remove.properties().get("icon-name").getValue(), "an icon that goes away is cleared");
        assertEquals(List.of(MenuTree.ROOT, client.id()), s.menuIds());
        assertEquals("separator", client.children().get(1).properties().get("type").getValue());

        DBusMenu.Layout shallow = s.layout(MenuTree.ROOT, 1);
        assertEquals(3, shallow.children.size());
        DBusMenu.Layout top = (DBusMenu.Layout) shallow.children.getFirst().getValue();
        assertEquals(0, top.children.size(), "depth 1 stops below the top level");
        DBusMenu.Layout full = s.layout(MenuTree.ROOT, -1);
        assertEquals(3, ((DBusMenu.Layout) full.children.getFirst().getValue()).children.size());
        assertEquals(3, s.layout(client.id(), -1).children.size(), "a submenu can be fetched on its own");
    }

    @Test
    void checkmarkItemsCarryTheirState() {
        MenuTree.Snapshot s = new MenuTree().build(List.of(
                Item.toggle("on", "Show idle screen at startup", true, () -> { }),
                Item.toggle("off", "Something else", false, () -> { }),
                Item.action("plain", "Quit", null, true, () -> { })));
        List<MenuTree.Node> items = s.root().children();
        assertEquals("checkmark", items.get(0).properties().get("toggle-type").getValue());
        assertEquals(1, items.get(0).properties().get("toggle-state").getValue());
        assertEquals(0, items.get(1).properties().get("toggle-state").getValue());
        assertEquals("", items.get(2).properties().get("toggle-type").getValue(),
                "sent for plain items too, so an item that loses its checkmark doesn't keep it in Plasma");
    }

    @Test
    void idsStayStableAcrossUpdates() {
        MenuTree tree = new MenuTree();
        MenuTree.Snapshot before = tree.build(menu("Streaming", true));
        MenuTree.Snapshot after = tree.build(menu("Stopping…", false));
        MenuTree.Node stopBefore = before.root().children().getFirst().children().getFirst();
        MenuTree.Node stopAfter = after.root().children().getFirst().children().getFirst();
        assertEquals(stopBefore.id(), stopAfter.id());
        assertFalse(stopAfter.enabled());
        assertEquals(false, stopAfter.properties().get("enabled").getValue());

        // A new item gets a fresh id instead of reusing one.
        MenuTree.Snapshot fewer = tree.build(List.of(Item.action("open", "Open Orbit", null, true, () -> { })));
        assertFalse(before.byId().containsKey(fewer.root().children().getFirst().id()));
    }
}
