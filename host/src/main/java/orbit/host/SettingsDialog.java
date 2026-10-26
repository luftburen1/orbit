package orbit.host;

import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import orbit.protocol.StreamSettings;

/**
 * The stream settings dialog: resolution, FPS, bitrate with a recommendation, video codec (those Sunshine can encode
 * and the client decodes in hardware), V-Sync, frame pacing, audio, the performance overlay, and extra Moonlight flags.
 */
final class SettingsDialog {

    private SettingsDialog() {
    }

    /**
     * Asks for new stream settings for {@code client}. Event thread only.
     *
     * @param sunshine for Sunshine's bitrate cap, read in the background; null without a Sunshine login
     * @return the settings to use, or empty if the dialog was cancelled
     */
    static Optional<StreamSettings> show(Component parent, ClientView client, SunshineClient sunshine) {
        Form form = new Form(client.settings(), client.hostname(), client.hardwareDecoders());
        loadSunshineBitrateCap(sunshine, form.hint);
        loadSunshineEncoders(sunshine, form);
        int answer = JOptionPane.showConfirmDialog(parent, form.panel, "Stream settings for " + client.hostname(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return answer == JOptionPane.OK_OPTION ? Optional.of(form.settings()) : Optional.empty();
    }

    /** The dialog's fields, filled from {@code s}. */
    static final class Form {
        final JPanel panel;
        final BitrateHint hint;
        private final JSpinner width;
        private final JSpinner height;
        private final JSpinner fps;
        private final JSpinner bitrate;
        private final JComboBox<CodecChoice> codec = new JComboBox<>();
        private final JLabel codecNote = new JLabel();
        private final String hostname;
        private final List<String> hardwareDecoders;
        private final JComboBox<Choice<Boolean>> vsync;
        private final JComboBox<Choice<Boolean>> framePacing;
        private final JComboBox<Choice<String>> audio;
        private final JComboBox<Choice<Boolean>> overlay;
        private final JTextField extra;

        /** @param hardwareDecoders the codecs the client decodes in hardware; null if unknown */
        Form(StreamSettings s, String hostname, List<String> hardwareDecoders) {
            width = new JSpinner(new SpinnerNumberModel(s.width(), 320, 7680, 1));
            height = new JSpinner(new SpinnerNumberModel(s.height(), 240, 4320, 1));
            fps = new JSpinner(new SpinnerNumberModel(s.fps(), 10, 480, 1));
            bitrate = new JSpinner(new SpinnerNumberModel(s.bitrateKbps(), 500, 500_000, 500));
            extra = new JTextField(String.join(" ", s.extraFlags()), 24);

            // Presets fill in width and height; "Custom" unlocks them for typing.
            String custom = "Custom…";
            JComboBox<Object> resolution = new JComboBox<>();
            ResolutionPreset.ALL.forEach(resolution::addItem);
            resolution.addItem(custom);
            resolution.setMaximumRowCount(resolution.getItemCount());
            resolution.addActionListener(e -> {
                boolean isCustom = resolution.getSelectedItem() == custom;
                if (resolution.getSelectedItem() instanceof ResolutionPreset p) {
                    width.setValue(p.width());
                    height.setValue(p.height());
                }
                width.setEnabled(isCustom);
                height.setEnabled(isCustom);
            });
            resolution.setSelectedItem(ResolutionPreset.of(s.width(), s.height()).<Object>map(p -> p).orElse(custom));
            width.setEnabled(resolution.getSelectedItem() == custom);
            height.setEnabled(resolution.getSelectedItem() == custom);

            hint = new BitrateHint(width, height, fps, bitrate);
            this.hostname = hostname;
            this.hardwareDecoders = hardwareDecoders;
            codecNote.setForeground(UIManager.getColor("Label.disabledForeground"));
            listCodecs(null, s.videoCodec());
            vsync = toggle(s.vsync(), "On: no tearing. Off: a little less latency.");
            framePacing = toggle(s.framePacing(), "Smoother motion, for a little more latency.");
            audio = choices(s.audioConfig(), List.of(new Choice<>("stereo", "Stereo"),
                    new Choice<>("5.1-surround", "5.1 surround"), new Choice<>("7.1-surround", "7.1 surround")));
            overlay = toggle(s.performanceOverlay(), "Moonlight's statistics in the stream: decoder, frame drops, "
                    + "latency.");
            panel = Forms.form(
                    "Resolution", resolution, "Width", width, "Height", height, "FPS", fps, "Bitrate (kbps)", bitrate,
                    "", hint.recommendation, "", hint.capWarning, "Video codec", codec, "", codecNote,
                    "V-Sync", vsync, "Frame pacing", framePacing, "Audio", audio, "Performance overlay", overlay,
                    "Extra Moonlight flags", extra);
        }

        StreamSettings settings() {
            List<String> flags = extra.getText().isBlank() ? List.of()
                    : Arrays.asList(extra.getText().strip().split("\\s+"));
            return new StreamSettings((Integer) width.getValue(), (Integer) height.getValue(),
                    (Integer) fps.getValue(), (Integer) bitrate.getValue(), flags,
                    ((CodecChoice) codec.getSelectedItem()).codec(), value(vsync), value(framePacing), value(audio),
                    value(overlay));
        }

        private static JComboBox<Choice<Boolean>> toggle(Boolean current, String tooltip) {
            JComboBox<Choice<Boolean>> box = choices(current, List.of(new Choice<>(true, "On"),
                    new Choice<>(false, "Off")));
            box.setToolTipText(tooltip);
            return box;
        }

        /** Moonlight's default first, then {@code choices}; {@code current} selected. */
        private static <T> JComboBox<Choice<T>> choices(T current, List<Choice<T>> choices) {
            JComboBox<Choice<T>> box = new JComboBox<>();
            box.addItem(new Choice<>(null, "Moonlight's default"));
            choices.forEach(box::addItem);
            for (int i = 0; i < box.getItemCount(); i++) {
                if (Objects.equals(box.getItemAt(i).value(), current)) {
                    box.setSelectedIndex(i);
                }
            }
            return box;
        }

        private static <T> T value(JComboBox<Choice<T>> box) {
            return box.getItemAt(box.getSelectedIndex()).value();
        }

        /** Sunshine's encoders arrived: lists only the codecs it can encode. */
        void setSunshineEncoders(List<String> encoders) {
            listCodecs(encoders, ((CodecChoice) codec.getSelectedItem()).codec());
        }

        /** Fills the codec list, keeping {@code selected} if it's still there (else Automatic). */
        private void listCodecs(List<String> encoders, String selected) {
            codec.removeAllItems();
            CodecChoice.all(encoders, hardwareDecoders).forEach(codec::addItem);
            for (int i = 0; i < codec.getItemCount(); i++) {
                if (Objects.equals(codec.getItemAt(i).codec(), selected)) {
                    codec.setSelectedIndex(i);
                }
            }
            codecNote.setText(CodecChoice.note(hostname, encoders, hardwareDecoders));
        }
    }

    /** An entry of a list of values; null leaves the setting to Moonlight. */
    record Choice<T>(T value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * An entry of the codec list.
     *
     * @param codec one of {@link StreamSettings#VIDEO_CODECS}, or null for Moonlight's choice
     */
    record CodecChoice(String codec, String label) {

        /** Automatic, then the {@link #listed} codecs, those decoded in software marked so. */
        static List<CodecChoice> all(List<String> encoders, List<String> hardwareDecoders) {
            List<CodecChoice> choices = new ArrayList<>();
            choices.add(new CodecChoice(null, "Automatic (Moonlight chooses)"));
            for (String c : listed(encoders, hardwareDecoders)) {
                String name = c.equals("HEVC") ? "HEVC (H.265)" : c;
                boolean software = hardwareDecoders != null && !hardwareDecoders.contains(c);
                choices.add(new CodecChoice(c, software ? name + " · software decoding" : name));
            }
            return choices;
        }

        /**
         * The codecs Sunshine can encode, and of those the ones the client decodes in hardware, if it decodes any of
         * them in hardware. A side that's unknown (null) narrows nothing.
         */
        static List<String> listed(List<String> encoders, List<String> hardwareDecoders) {
            List<String> encodable = StreamSettings.VIDEO_CODECS.stream()
                    .filter(c -> encoders == null || encoders.contains(c)).toList();
            if (hardwareDecoders == null) {
                return encodable;
            }
            List<String> both = encodable.stream().filter(hardwareDecoders::contains).toList();
            return both.isEmpty() ? encodable : both;
        }

        /** The line under the list: what decided which codecs are listed. */
        static String note(String hostname, List<String> encoders, List<String> hardwareDecoders) {
            boolean clientNarrows = hardwareDecoders != null
                    && listed(encoders, hardwareDecoders).stream().allMatch(hardwareDecoders::contains);
            if (encoders != null) {
                return clientNarrows ? "Codecs Sunshine can encode and " + hostname + " decodes in hardware."
                        : hardwareDecoders == null
                        ? "Codecs Sunshine can encode; " + hostname + " hasn't said which it decodes in hardware."
                        : "Codecs Sunshine can encode; " + hostname + " decodes them in software, on its CPU.";
            }
            return clientNarrows ? "Codecs " + hostname + " decodes in hardware; Sunshine's encoders are unknown."
                    : hardwareDecoders == null
                    ? "Neither Sunshine's encoders nor " + hostname + "'s hardware decoders are known."
                    : hostname + " decodes every codec in software; Sunshine's encoders are unknown.";
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Reads which codecs Sunshine can encode in the background, and narrows the dialog's codec list to them. */
    private static void loadSunshineEncoders(SunshineClient sunshine, Form form) {
        if (sunshine == null) {
            return;
        }
        new SwingWorker<Optional<List<String>>, Void>() {
            @Override
            protected Optional<List<String>> doInBackground() throws Exception {
                return sunshine.videoEncoders();
            }

            @Override
            protected void done() {
                try {
                    get().ifPresent(form::setSunshineEncoders);
                } catch (Exception e) {
                    // Without Sunshine's encoders the list just isn't narrowed by them.
                }
            }
        }.execute();
    }

    /** Reads Sunshine's bitrate cap in the background and passes it to the dialog's hint. */
    private static void loadSunshineBitrateCap(SunshineClient sunshine, BitrateHint hint) {
        if (sunshine == null) {
            return;
        }
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return sunshine.maxBitrateKbps();
            }

            @Override
            protected void done() {
                try {
                    hint.setSunshineCap(get());
                } catch (Exception e) {
                    // Without Sunshine's cap the hint just doesn't warn about it.
                }
            }
        }.execute();
    }

    /**
     * The bitrate's recommendation line and Sunshine-cap warning in the stream settings dialog. While the bitrate is
     * at the recommended value it follows changes to resolution and FPS; once the user types another value it stays.
     */
    static final class BitrateHint {
        final JPanel recommendation = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        final JLabel capWarning = new JLabel(" ");
        private final JLabel text = new JLabel();
        private final JButton use = new JButton("Use");
        private final JSpinner width;
        private final JSpinner height;
        private final JSpinner fps;
        private final JSpinner bitrate;
        private int sunshineCap;
        private boolean following;
        private boolean adjusting;

        BitrateHint(JSpinner width, JSpinner height, JSpinner fps, JSpinner bitrate) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrate = bitrate;
            following = value(bitrate) == recommended();
            text.setForeground(UIManager.getColor("Label.disabledForeground"));
            capWarning.setForeground(FlatLaf.isLafDark() ? new Color(0xFFB454) : new Color(0xB35C00));
            use.putClientProperty("JButton.buttonType", "borderless");
            Color link = UIManager.getColor("Component.linkColor");
            if (link != null) {
                use.setForeground(link);
            }
            use.setToolTipText("Set the bitrate to the recommended value");
            use.addActionListener(e -> bitrate.setValue(recommended()));
            recommendation.add(text);
            recommendation.add(Box.createHorizontalStrut(6));
            recommendation.add(use);

            for (JSpinner spinner : List.of(width, height, fps)) {
                spinner.addChangeListener(e -> {
                    if (following) {
                        adjusting = true;
                        bitrate.setValue(recommended());
                        adjusting = false;
                    }
                    refresh();
                });
            }
            bitrate.addChangeListener(e -> {
                if (!adjusting) {
                    following = value(bitrate) == recommended();
                }
                refresh();
            });
            refresh();
        }

        /** Sunshine's max_bitrate in kbps; 0 for none. */
        void setSunshineCap(int kbps) {
            sunshineCap = kbps;
            refresh();
        }

        private int recommended() {
            return BitrateAdvisor.recommendedKbps(value(width), value(height), value(fps));
        }

        private void refresh() {
            int recommended = recommended();
            text.setText("Recommended for " + value(width) + "×" + value(height) + " at " + value(fps) + " fps: "
                    + BitrateAdvisor.format(recommended));
            use.setVisible(value(bitrate) != recommended);
            if (sunshineCap > 0 && value(bitrate) > sunshineCap) {
                capWarning.setText("Sunshine caps streams at " + BitrateAdvisor.format(sunshineCap)
                        + " (its max_bitrate), so a higher bitrate has no effect.");
            } else if (sunshineCap > 0 && recommended > sunshineCap) {
                capWarning.setText("Sunshine caps streams at " + BitrateAdvisor.format(sunshineCap)
                        + " (its max_bitrate), below the recommendation.");
            } else {
                capWarning.setText(" ");
            }
        }

        private static int value(JSpinner spinner) {
            return (Integer) spinner.getValue();
        }
    }
}
