package com.guiltypotato.checkengine.core.intercept;

import com.guiltypotato.checkengine.core.intercept.LaunchIntercept.Choice;
import com.guiltypotato.checkengine.core.intercept.LaunchIntercept.Level;
import com.guiltypotato.checkengine.core.intercept.LaunchIntercept.Message;
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
import java.util.Arrays;
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
 * The pre-launch window, styled like a car dashboard: a blinking check-engine light, a fault code and the fix.
 * Plain Swing, so it can open before Minecraft (and its window) exists.
 */
final class Popup {
    private static final Color BG = new Color(0x19191B);
    private static final Color PANEL = new Color(0x2A2B2F);
    private static final Color TEXT = new Color(0xE6E6E6);
    private static final Color DIM = new Color(0x9A9CA3);
    private static final Color RED = new Color(0xFF4B3E);
    private static final Color AMBER = new Color(0xFF9A1F);
    private static final Color GREEN = new Color(0x3DDC4A);
    private static final Color RIVET = new Color(0x5E6168);
    /** Lines a panel shows before it scrolls (the window keeps its size). */
    private static final int VISIBLE_PROBLEMS = 4;

    private Popup() {}

    static Choice show(Message m, byte[] logoPng) throws Exception {
        System.setProperty("java.awt.headless", "false");
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("no screen");
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        Color level = m.level() == Level.WILL_FAIL ? RED : AMBER;
        AtomicReference<Choice> choice = new AtomicReference<>(Choice.CONTINUE);

        JDialog d = new JDialog((java.awt.Frame) null, "Check Engine", true);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.setAlwaysOnTop(true);
        Image logo = logoPng == null ? null : ImageIO.read(new ByteArrayInputStream(logoPng));
        if (logo != null) d.setIconImage(logo);

        JPanel root = new JPanel(new BorderLayout(0, 10)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setPaint(new GradientPaint(0, 0, new Color(0x222225), 0, getHeight(), BG));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(level); // warning stripe across the top, like a dashboard alert bar
                g2.fillRect(0, 0, getWidth(), 4);
            }
        };
        root.setBorder(new EmptyBorder(14, 16, 12, 16));

        // Header: the check-engine light, the name, and the status lamp.
        WarningLight light = new WarningLight(logo, level);
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setOpaque(false);
        header.add(light, BorderLayout.WEST);
        JPanel titles = column();
        titles.add(label("CHECK ENGINE", font(Font.BOLD, 21), TEXT));
        titles.add(label(m.headline(), font(Font.BOLD, 16), level));
        titles.add(label("FAULT " + faultCode(m) + "  ·  " + DateTimeNow.now(), font(Font.PLAIN, 12), DIM));
        header.add(titles, BorderLayout.CENTER);
        root.add(header, BorderLayout.NORTH);

        // Body: the problem, what changed, the fix, each in its own dashboard panel.
        JPanel body = column();
        JPanel problem = panel(level);
        problem.add(label(m.level() == Level.WILL_FAIL ? "WHAT'S WRONG" : "LAST CRASH", font(Font.BOLD, 12), level));
        JPanel list = column();
        for (int i = 0; i < m.problems().size(); i++) {
            list.add(label(m.problems().get(i), font(Font.BOLD, i == 0 ? 15 : 14), TEXT));
            if (i == 0) m.details().forEach(dl -> list.add(label(dl, font(Font.PLAIN, 14), DIM)));
        }
        problem.add(fit(list));
        body.add(problem);
        if (!m.changed().isEmpty()) {
            body.add(Box.createVerticalStrut(6));
            JPanel changed = panel(AMBER);
            changed.add(label("CHANGED SINCE IT LAST WORKED", font(Font.BOLD, 12), AMBER));
            JPanel lines = column();
            m.changed().forEach(c -> lines.add(label(c, font(Font.PLAIN, 14), TEXT)));
            changed.add(fit(lines));
            body.add(changed);
        }
        if (!m.fixes().isEmpty()) {
            body.add(Box.createVerticalStrut(6));
            JPanel fix = panel(GREEN);
            fix.add(label("FIX", font(Font.BOLD, 12), GREEN));
            JPanel lines = column();
            m.fixes().forEach(f -> lines.add(label(m.fixes().size() > 1 ? "2022 " + f : f, font(Font.PLAIN, 14), TEXT)));
            fix.add(fit(lines));
            body.add(fix);
        }
        root.add(body, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        buttons.add(button("QUIT", RED, () -> {
            choice.set(Choice.QUIT);
            d.dispose();
        }));
        buttons.add(button("LOAD ANYWAY", new Color(0x3A3D45), d::dispose));
        JButton full = button("SHOW FULL ERROR", new Color(0x3A3D45), () -> openReport(m));
        full.setEnabled(m.report() != null);
        buttons.add(full);
        root.add(buttons, BorderLayout.SOUTH);

        d.setContentPane(root);
        d.pack();
        d.setMinimumSize(new Dimension(560, d.getHeight()));
        d.setResizable(false);
        d.setLocationRelativeTo(null);
        Timer blink = new Timer(550, e -> light.toggle());
        blink.start();
        d.setVisible(true); // blocks until closed
        blink.stop();
        return choice.get();
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
        b.setFont(font(Font.BOLD, 14));
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
