// The tray icon shared by the host and the agent: a D-Bus StatusNotifierItem on Linux desktops, else Java's tray.
dependencies {
    api(project(":protocol"))
    api(libs.dbus.core)
}
