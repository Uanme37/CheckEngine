package com.guiltypotato.checkengine.core.intercept;

import com.guiltypotato.checkengine.core.intercept.LaunchIntercept.Choice;
import com.guiltypotato.checkengine.core.intercept.LaunchIntercept.Level;
import com.guiltypotato.checkengine.core.intercept.LaunchIntercept.Message;
import com.guiltypotato.checkengine.core.intercept.Page.Action;
import com.guiltypotato.checkengine.core.intercept.Page.Line;
import com.guiltypotato.checkengine.core.intercept.Page.Section;
import com.guiltypotato.checkengine.core.intercept.Page.Style;
import com.guiltypotato.checkengine.core.intercept.Page.Tone;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;

/**
 * The Check Engine window, styled like a car dashboard: a blinking check-engine light, a fault code and the fix.
 * Plain Swing, so it can open before Minecraft (and its window) exists. Every page is drawn in the same frame
 * ({@link Page}): features only decide the panels and buttons.
 */
final class Popup {
    private static final Color BG = new Color(0x19191B);
    private static final Color PANEL = new Color(0x2A2B2F);
    private static final Color TEXT = new Color(0xE6E6E6);
    private static final Color DIM = new Color(0x9A9CA3);
    private static final Color RED = new Color(0xFF4B3E);
    private static final Color AMBER = new Color(0xFF9A1F);
    private static final Color GREEN = new Color(0x3DDC4A);
    private static final Color BLUE = new Color(0x3B82F6);
    private static final Color RIVET = new Color(0x5E6168);
    private static final Color BUTTON = new Color(0x3A3D45);
    private static final Color STRIP_BUTTON = new Color(0x8A5A12);
    /** Lines a panel shows before it scrolls (the window keeps its size). */
    private static final int VISIBLE_PROBLEMS = 4;
    /** Buttons shown in a row; more than this and the rest go under MORE. */
    private static final int MAX_BUTTONS = 3;

    private Popup() {}

    static Choice show(Message m, byte[] logoPng) throws Exception {
        System.setProperty("java.awt.headless", "false");
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("no screen");
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        AtomicReference<Choice> choice = new AtomicReference<>(Choice.CONTINUE);

        JDialog d = new JDialog((java.awt.Frame) null, "Check Engine", true);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.setAlwaysOnTop(true);
        Image logo = logoPng == null ? null : ImageIO.read(new ByteArrayInputStream(logoPng));
        if (logo != null) d.setIconImage(logo);
        Frame frame = new Frame(d, logo, image("/checkengine_uan.png"), new ArrayList<>());

        AtomicReference<JPanel> main = new AtomicReference<>();
        Runnable back = () -> frame.swap(main.get());
        main.set(frame.render(mainPage(m, choice, d,
                () -> frame.swap(frame.render(stripMinePage(m, false, choice, frame, back))))));

        d.setContentPane(main.get());
        d.pack();
        d.setMinimumSize(new Dimension(560, d.getHeight()));
        d.setResizable(false);
        d.setLocationRelativeTo(null);
        Timer blink = new Timer(550, e -> frame.lights().forEach(WarningLight::toggle));
        blink.start();
        d.setVisible(true); // blocks until closed
        blink.stop();
        return choice.get();
    }

    /** The first page: what's wrong, what changed, the fix. */
    static Page mainPage(Message m, AtomicReference<Choice> choice, JDialog d, Runnable openStripMine) {
        Tone tone = m.level() == Level.WILL_FAIL ? Tone.RED : m.level() == Level.FOUND ? Tone.GREEN : Tone.AMBER;
        List<Section> sections = new ArrayList<>();
        List<Line> problem = new ArrayList<>();
        for (int i = 0; i < m.problems().size(); i++) {
            problem.add(Line.strong(m.problems().get(i)));
            if (i == 0) m.details().forEach(dl -> problem.add(Line.dim(dl)));
        }
        sections.add(new Section(m.level() == Level.WILL_FAIL ? "WHAT'S WRONG"
                : m.level() == Level.FOUND ? "THE FAULTY MOD" : "LAST CRASH", tone, problem));
        if (!m.changed().isEmpty()) {
            sections.add(new Section("CHANGED SINCE IT LAST WORKED", Tone.AMBER,
                    m.changed().stream().map(Line::text).toList()));
        }
        if (!m.fixes().isEmpty()) {
            sections.add(new Section("FIX", Tone.GREEN, m.fixes().stream()
                    .map(f -> Line.text(m.fixes().size() > 1 ? "• " + f : f)).toList()));
        }
        List<Action> actions = new ArrayList<>();
        actions.add(new Action("QUIT", Tone.RED, () -> {
            choice.set(Choice.QUIT);
            d.dispose();
        }));
        if (m.stripMine() != null) actions.add(new Action("STRIP MINE", Tone.AMBER, openStripMine));
        actions.add(new Action("LOAD ANYWAY", Tone.GREY, d::dispose));
        if (m.report() != null) actions.add(new Action("SHOW FULL ERROR", Tone.GREY, () -> openReport(m)));
        return new Page(tone, m.headline(), "FAULT " + faultCode(m) + "  ·  " + DateTimeNow.now(), sections,
                actions, null);
    }

    /** What Strip Mine is, before it starts, with Back and Start. */
    private static Page stripMinePage(Message m, boolean stillLocked, AtomicReference<Choice> choice, Frame frame,
                                      Runnable back) {
        List<Section> sections = new ArrayList<>();
        // CurseForge-installed packs are locked: the app would download the switched-off mods again.
        if (m.isStripMineLocked()) {
            List<Line> unlock = new ArrayList<>(List.of(
                    Line.text("CurseForge has this pack locked, so it would put switched-off mods straight back."),
                    Line.text("In the CurseForge app: three dots next to Play > Profile options >"),
                    Line.text("turn on \"Allow content management\". Then press Start.")));
            if (stillLocked) unlock.add(Line.strong("Still locked: turn on \"Allow content management\" first."));
            sections.add(new Section("FIRST: LET STRIP MINE CHANGE THIS PACK", Tone.RED, unlock));
        }
        sections.add(new Section("HOW IT WORKS", Tone.AMBER, List.of(
                Line.text("Check Engine switches off half of your mods, and you launch."),
                Line.text("It crashes: the faulty mod is still on. No crash: it's in the half that was off."),
                Line.text("Mods you changed lately are tested first. Keep pressing Play until it says which mod."))));
        sections.add(new Section("SAFE", Tone.GREEN, List.of(
                Line.text("Nothing is deleted: mods are renamed to .disabled and switched back on at the end."),
                Line.text("You can stop any time from the title screen, and every mod goes back."))));
        sections.add(new Section("WHAT HAPPENS NOW", Tone.GREY, List.of(
                Line.text("The game closes. Press Play again to start round 1."))));
        List<Action> actions = List.of(
                new Action("BACK", Tone.GREY, back),
                new Action("START STRIP MINE", Tone.AMBER, () -> {
                    if (m.isStripMineLocked()) { // checked again: the app saves the switch right away
                        frame.swap(frame.render(stripMinePage(m, true, choice, frame, back)));
                        return;
                    }
                    choice.set(Choice.STRIP_MINE);
                    frame.dialog().dispose();
                }));
        return new Page(Tone.AMBER, "STRIP MINE: FIND THE FAULTY MOD", m.stripMineMods() > 1
                ? m.stripMineMods() + " mods, about " + m.stripMineRounds() + " launches (fewer if you changed mods lately)"
                : "One launch per round", sections, actions, null);
    }

    /** The window and what every page in it shares: the logos and the blinking lights. */
    private record Frame(JDialog dialog, Image logo, Image uan, List<WarningLight> lights) {
        /** Shows another page in the same window, sized to fit it and kept centred where it was. */
        void swap(JPanel page) {
            JDialog d = dialog;
            java.awt.Point centre = new java.awt.Point(d.getX() + d.getWidth() / 2, d.getY() + d.getHeight() / 2);
            d.setContentPane(page);
            d.pack();
            d.setLocation(centre.x - d.getWidth() / 2, centre.y - d.getHeight() / 2);
            d.repaint();
        }

        /** Draws a page in the Check Engine frame. */
        JPanel render(Page p) {
            Color tone = color(p.tone());
            JPanel root = new JPanel(new BorderLayout(0, 10)) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g;
                    g2.setPaint(new GradientPaint(0, 0, new Color(0x222225), 0, getHeight(), BG));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    g2.setColor(tone); // warning stripe across the top, like a dashboard alert bar
                    g2.fillRect(0, 0, getWidth(), 4);
                }
            };
            root.setBorder(new EmptyBorder(14, 16, 0, 16));

            // Header: the check-engine light, the name, the headline.
            WarningLight light = new WarningLight(logo, tone);
            lights.add(light);
            JPanel header = new JPanel(new BorderLayout(14, 0));
            header.setOpaque(false);
            header.add(light, BorderLayout.WEST);
            JPanel titles = column();
            titles.add(label("CHECK ENGINE", font(Font.BOLD, 21), TEXT));
            titles.add(label(p.headline(), font(Font.BOLD, 16), tone));
            if (p.subline() != null) titles.add(label(p.subline(), font(Font.PLAIN, 12), DIM));
            header.add(titles, BorderLayout.CENTER);
            root.add(header, BorderLayout.NORTH);

            // Body: one dashboard panel per section.
            JPanel body = column();
            for (int i = 0; i < p.sections().size(); i++) {
                Section s = p.sections().get(i);
                if (i > 0) body.add(Box.createVerticalStrut(6));
                JPanel panel = panel(color(s.tone()));
                panel.add(label(s.title(), font(Font.BOLD, 12), color(s.tone())));
                JPanel lines = column();
                for (Line l : s.lines()) {
                    lines.add(label(l.text(), font(l.style() == Style.STRONG ? Font.BOLD : Font.PLAIN, 14),
                            l.style() == Style.DIM ? DIM : TEXT));
                }
                panel.add(fit(lines));
                body.add(panel);
            }
            root.add(body, BorderLayout.CENTER);

            // Bottom: help line, buttons, and the UAN footer.
            JPanel bottom = column();
            if (p.help() != null) {
                JLabel help = label(p.help(), font(Font.PLAIN, 13), DIM);
                help.setBorder(new EmptyBorder(0, 0, 8, 0));
                bottom.add(help);
            }
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            buttons.setOpaque(false);
            buttons.setAlignmentX(0f);
            List<Action> shown = p.actions().size() > MAX_BUTTONS ? p.actions().subList(0, MAX_BUTTONS - 1) : p.actions();
            for (Action a : shown) buttons.add(button(a.label(), buttonColor(a), a.run()));
            if (shown.size() < p.actions().size()) buttons.add(more(p.actions().subList(shown.size(), p.actions().size())));
            bottom.add(buttons);
            bottom.add(footer(uan));
            root.add(bottom, BorderLayout.SOUTH);
            return root;
        }
    }

    /** "MORE" button: the extra actions in a small menu. */
    private static JButton more(List<Action> extra) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        menu.setBorder(BorderFactory.createLineBorder(RIVET));
        for (Action a : extra) {
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(a.label());
            item.setFont(font(Font.BOLD, 13));
            item.setForeground(Color.WHITE);
            item.setBackground(BUTTON);
            item.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
            item.addActionListener(e -> a.run().run());
            menu.add(item);
        }
        JButton b = button("MORE ▼", BUTTON, () -> {});
        b.addActionListener(e -> menu.show(b, 0, b.getHeight()));
        return b;
    }

    /** The UAN maker's mark at the bottom: the logo and "Check Engine". */
    private static JComponent footer(Image uan) {
        JPanel f = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        f.setOpaque(false);
        f.setBorder(new EmptyBorder(8, 0, 8, 0));
        if (uan != null) f.add(new JLabel(new javax.swing.ImageIcon(uan.getScaledInstance(16, 16, Image.SCALE_SMOOTH))));
        f.add(label("Check Engine", font(Font.PLAIN, 12), DIM));
        JPanel wrap = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(new Color(0x2C2D31));
                g.fillRect(0, 12, getWidth(), 1); // thin divider line above the mark
            }
        };
        wrap.setOpaque(false);
        wrap.setAlignmentX(0f);
        wrap.setBorder(new EmptyBorder(13, 0, 0, 0));
        wrap.add(f);
        return wrap;
    }

    private static Color color(Tone t) {
        return switch (t) {
            case RED -> RED;
            case AMBER -> AMBER;
            case GREEN -> GREEN;
            case BLUE -> BLUE;
            case GREY -> DIM;
        };
    }

    private static Color buttonColor(Action a) {
        return switch (a.tone()) {
            case RED -> RED;
            case AMBER -> STRIP_BUTTON;
            case GREEN -> new Color(0x1F7A2A);
            case BLUE -> BLUE;
            case GREY -> BUTTON;
        };
    }

    private static Image image(String resource) {
        try (java.io.InputStream in = Popup.class.getResourceAsStream(resource)) {
            return in == null ? null : ImageIO.read(in);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Fallback when the window can't open: the plain system box (TinyFD if it's there), Yes = quit. */
    static Choice simple(Message m) throws Exception {
        String text = (m.toText() + "\n\nQuit now? (No = load anyway)")
                .replace('"', '`').replace('\'', '`');
        Class<?> tfd = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
        Object yes = tfd.getMethod("tinyfd_messageBox", CharSequence.class, CharSequence.class, CharSequence.class,
                CharSequence.class, boolean.class).invoke(null, "Check Engine", text, "yesno", "error", true);
        return Boolean.TRUE.equals(yes) ? Choice.QUIT : Choice.CONTINUE;
    }

    /** Opens the full report in the system's text editor; the window stays open. */
    private static void openReport(Message m) {
        try {
            java.awt.Desktop.getDesktop().open(m.report().toFile());
        } catch (Exception ignored) {
            // nothing to open; the window still has the summary
        }
    }

    /** "CE-01 · MISSING MOD": a car-style fault code for the first problem. */
    static String faultCode(Message m) {
        if (m.level() == Level.MAY_FAIL) return "CE-09  ·  REPEAT CRASH";
        if (m.level() == Level.FOUND) return "CE-10  ·  STRIP MINE RESULT";
        String p = m.problems().get(0).toLowerCase();
        if (p.contains("fabric version") || p.contains("wrong build")) return "CE-02  ·  WRONG BUILD";
        if (p.contains("broken")) return "CE-03  ·  BROKEN FILE";
        if (p.contains("switched off")) return "CE-04  ·  MOD SWITCHED OFF";
        if (p.contains("too old") || p.contains("too new") || p.contains("version")) return "CE-05  ·  WRONG VERSION";
        if (p.contains("doesn't work with")) return "CE-06  ·  INCOMPATIBLE";
        return "CE-01  ·  MISSING MOD";
    }

    /** The list as is, or scrolling once it has more than {@link #VISIBLE_PROBLEMS} lines. */
    private static JComponent fit(JPanel list) {
        return list.getComponentCount() > VISIBLE_PROBLEMS ? scroll(list) : list;
    }

    /** A scrolling list that shows about {@link #VISIBLE_PROBLEMS} lines, with a thin dashboard-style scrollbar. */
    private static JComponent scroll(JPanel list) {
        JScrollPane sp = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setBackground(PANEL);
        sp.setAlignmentX(0f);
        int row = list.getComponent(0).getPreferredSize().height;
        sp.setPreferredSize(new Dimension(list.getPreferredSize().width + 14, row * (VISIBLE_PROBLEMS + 1) + row / 2));
        JScrollBar bar = sp.getVerticalScrollBar();
        bar.setPreferredSize(new Dimension(8, 0));
        bar.setUnitIncrement(row);
        bar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = RIVET;
                trackColor = PANEL;
            }

            @Override
            protected JButton createDecreaseButton(int orientation) {
                return noButton();
            }

            @Override
            protected JButton createIncreaseButton(int orientation) {
                return noButton();
            }

            private JButton noButton() {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(0, 0));
                return b;
            }
        });
        return sp;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        return p;
    }

    /** A dark panel with a colored stripe on the left, like a gauge cluster readout. */
    private static JPanel panel(Color accent) {
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(PANEL);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(accent);
                g.fillRect(0, 0, 4, getHeight());
                // Rivets in the corners, like the steel plates of the Check Engine block.
                g.setColor(RIVET);
                int w = getWidth(), h = getHeight();
                for (int[] r : new int[][] {{8, 4}, {w - 8, 4}, {8, h - 8}, {w - 8, h - 8}}) g.fillRect(r[0], r[1], 4, 4);
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(7, 13, 7, 12));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE)); // full width, like a gauge readout
        p.setAlignmentX(0f);
        return p;
    }

    private static JLabel label(String text, Font f, Color c) {
        JLabel l = new JLabel(text);
        // Bahnschrift has no arrows; anything it can't draw falls back to a font that can.
        l.setFont(f.canDisplayUpTo(text) == -1 ? f : new Font(Font.SANS_SERIF, f.getStyle(), f.getSize()));
        l.setForeground(c);
        l.setAlignmentX(0f);
        l.setBorder(new EmptyBorder(1, 0, 1, 0));
        return l;
    }

    private static JButton button(String text, Color color, Runnable action) {
        JButton b = new JButton(text);
        Font f = font(Font.BOLD, 14);
        b.setFont(f.canDisplayUpTo(text) == -1 ? f : new Font(Font.SANS_SERIF, Font.BOLD, 14));
        b.setForeground(Color.WHITE);
        b.setBackground(color);
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createEmptyBorder(7, 16, 7, 16));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> action.run());
        return b;
    }

    /** Bahnschrift is Windows' dashboard-style font; anything else falls back to the system sans-serif. */
    private static Font font(int style, int size) {
        String name = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                .contains("Bahnschrift") ? "Bahnschrift" : "SansSerif";
        return new Font(name, style, size);
    }

    /** The Check Engine logo with a glowing light behind it that blinks, like the real dashboard lamp. */
    private static final class WarningLight extends JComponent {
        private final Image logo;
        private final Color color;
        private boolean on = true;

        WarningLight(Image logo, Color color) {
            this.logo = logo;
            this.color = color;
            setPreferredSize(new Dimension(66, 66));
        }

        void toggle() {
            on = !on;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int s = Math.min(getWidth(), getHeight());
            if (on) {
                Color glow = new Color(color.getRed(), color.getGreen(), color.getBlue(), 150);
                g2.setPaint(new RadialGradientPaint(s / 2f, s / 2f, s / 2f, new float[] {0f, 1f},
                        new Color[] {glow, new Color(0, 0, 0, 0)}));
                g2.fillOval(0, 0, s, s);
            }
            g2.setColor(on ? color : color.darker().darker());
            g2.setStroke(new BasicStroke(2.5f));
            g2.drawOval(6, 6, s - 12, s - 12);
            if (logo != null) {
                // The logo is square with a dark background: cut it to a circle just inside the ring, so no corners
                // show, and zoom in a little so the block fills it.
                int inner = s - 18;
                BufferedImage img = new BufferedImage(inner, inner, BufferedImage.TYPE_INT_ARGB);
                Graphics2D gi = img.createGraphics();
                gi.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                gi.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                gi.fill(new java.awt.geom.Ellipse2D.Float(0, 0, inner, inner));
                gi.setComposite(java.awt.AlphaComposite.SrcIn);
                int zoom = inner / 8;
                gi.drawImage(logo, -zoom, -zoom, inner + 2 * zoom, inner + 2 * zoom, null);
                gi.dispose();
                g2.drawImage(img, 9, 9, null);
            }
            g2.dispose();
        }
    }

    /** "08 Oct 2026 16:42", for the fault line. */
    private static final class DateTimeNow {
        static String now() {
            return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"));
        }
    }
}
