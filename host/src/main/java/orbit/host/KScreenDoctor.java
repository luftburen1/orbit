package orbit.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import orbit.protocol.Commands;
import orbit.protocol.MessageCodec;

/** {@link DisplayControl} for KDE Plasma (X11 and Wayland) through its {@code kscreen-doctor} tool. */
final class KScreenDoctor implements DisplayControl {

    private static final ObjectMapper MAPPER = MessageCodec.newMapper();

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final String executable;

    private KScreenDoctor(String executable) {
        this.executable = executable;
    }

    /** Available when {@code kscreen-doctor} is on the PATH. */
    static Optional<DisplayControl> find() {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (String dir : path.split(File.pathSeparator)) {
            Path candidate = Path.of(dir, "kscreen-doctor");
            if (!dir.isEmpty() && Files.isExecutable(candidate)) {
                return Optional.of(new KScreenDoctor(candidate.toString()));
            }
        }
        return Optional.empty();
    }

    @Override
    public List<Output> outputs() throws IOException, InterruptedException {
        return parse(run("-j"));
    }

    @Override
    public void setMode(String output, String modeId) throws IOException, InterruptedException {
        run("output." + output + ".mode." + modeId);
    }

    @Override
    public void setEnabled(String output, boolean enabled) throws IOException, InterruptedException {
        run("output." + output + (enabled ? ".enable" : ".disable"));
    }

    @Override
    public Optional<Rectangle> bounds(String output) throws IOException, InterruptedException {
        return bounds(run("-j"), output);
    }

    /** {@code size} is the mode's, in pixels: the desktop's is that over the scale, turned with the monitor. */
    static Optional<Rectangle> bounds(String json, String output) throws IOException {
        for (JsonNode o : MAPPER.readTree(json).path("outputs")) {
            if (!o.path("name").asText().equals(output) || !o.path("enabled").asBoolean()) {
                continue;
            }
            double scale = o.path("scale").asDouble(1);
            int width = (int) Math.round(o.path("size").path("width").asInt() / (scale > 0 ? scale : 1));
            int height = (int) Math.round(o.path("size").path("height").asInt() / (scale > 0 ? scale : 1));
            // Rotation 2 and 8 are a quarter turn left and right (1 none, 4 upside down).
            int rotation = o.path("rotation").asInt(1);
            boolean turned = rotation == 2 || rotation == 8;
            return Optional.of(new Rectangle(o.path("pos").path("x").asInt(), o.path("pos").path("y").asInt(),
                    turned ? height : width, turned ? width : height));
        }
        return Optional.empty();
    }

    static List<Output> parse(String json) throws IOException {
        List<Output> outputs = new ArrayList<>();
        for (JsonNode o : MAPPER.readTree(json).path("outputs")) {
            if (!o.path("connected").asBoolean()) {
                continue;
            }
            List<Mode> modes = new ArrayList<>();
            for (JsonNode m : o.path("modes")) {
                modes.add(new Mode(m.path("id").asText(), m.path("size").path("width").asInt(),
                        m.path("size").path("height").asInt(), m.path("refreshRate").asDouble()));
            }
            // Plasma 6 numbers the monitors by priority (1 is the primary); Plasma 5 had a "primary" flag.
            boolean primary = o.has("priority") ? o.path("priority").asInt() == 1 : o.path("primary").asBoolean();
            outputs.add(new Output(o.path("name").asText(), o.path("enabled").asBoolean(), primary,
                    o.path("currentModeId").asText(), modes));
        }
        return outputs;
    }

    private String run(String argument) throws IOException, InterruptedException {
        Commands.Result result = Commands.run(List.of(executable, argument), TIMEOUT);
        if (!result.ok()) {
            throw new IOException("kscreen-doctor " + argument + " failed: " + result.output().strip());
        }
        return result.output();
    }
}
