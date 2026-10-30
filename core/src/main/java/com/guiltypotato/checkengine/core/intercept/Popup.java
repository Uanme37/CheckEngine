package com.guiltypotato.checkengine.core.intercept;

import com.guiltypotato.checkengine.core.intercept.LaunchIntercept.Choice;
import com.guiltypotato.checkengine.core.intercept.LaunchIntercept.Level;
import com.guiltypotato.checkengine.core.intercept.LaunchIntercept.Message;
import com.guiltypotato.checkengine.core.intercept.Page.Action;
import com.guiltypotato.checkengine.core.intercept.Page.Icon;
import com.guiltypotato.checkengine.core.intercept.Page.Line;
import com.guiltypotato.checkengine.core.intercept.Page.Section;
import com.guiltypotato.checkengine.core.intercept.Page.Style;
import com.guiltypotato.checkengine.core.intercept.Page.Tone;
import com.guiltypotato.checkengine.core.scan.PackInfo;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Polygon;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * The Check Engine window, styled like a car dashboard: a hazard stripe, the check-engine lamp in a metal bezel, the
 * fault code in a lit readout, screwed-on panels and switch-style buttons. Plain Swing, so it can open before
 * Minecraft (and its window) exists. Every page is drawn in the same frame ({@link Page}): features only decide the
 * panels and buttons.
 */
final class Popup {
    private static final Color TEXT = new Color(0xE8E8EA);
    private static final Color DIM = new Color(0x9A9CA3);
    private static final Color FAINT = new Color(0x6E7077);
    private static final Color RED = new Color(0xFF4B3E);
    private static final Color AMBER = new Color(0xFF9A1F);
    private static final Color GREEN = new Color(0x3DDC4A);
    private static final Color BLUE = new Color(0x3B82F6);
    private static final Color PANEL_TOP = new Color(0x26272B);
    private static final Color PANEL_BOTTOM = new Color(0x212226);
    private static final Color EDGE = new Color(0x34363B);
    /** Lines a panel shows before it scrolls (the window keeps its size). */
    private static final int VISIBLE_LINES = 4;
    private static final int WIDTH = 600;

    private Popup() {}

    static Choice show(Message m, byte[] logoPng) throws Exception {
        return show(m, logoPng, new PackInfo(null, null));
    }

    static Choice show(Message m, byte[] logoPng, PackInfo pack) throws Exception {
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
        main.set(frame.render(mainPage(m, pack, choice, d,
                () -> frame.swap(frame.render(stripMinePage(m, false, choice, frame, back))),
                () -> frame.swap(frame.render(goBackPage(m, choice, frame, back))))));

        d.setContentPane(main.get());
        d.pack();
        d.setResizable(false);
        d.setLocationRelativeTo(null);
        Timer blink = new Timer(550, e -> frame.lamps().forEach(Bezel::toggle));
        blink.start();
        d.setVisible(true); // blocks until closed
        blink.stop();
        return choice.get();
    }

    /** A launcher setting that will hurt the pack (memory, Java): a blue window with the fix and "Don't ask again". */
    static Choice showSetting(SetupCheck.Warning w, byte[] logoPng, Path checkEngineFolder) throws Exception {
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

        Action quit = new Action("QUIT", w.quitFirst() ? Tone.BLUE : Tone.GREY, () -> {
            choice.set(Choice.QUIT);
            d.dispose();
        });
        Action go = new Action(w.quitFirst() ? "LOAD ANYWAY" : "CONTINUE", w.quitFirst() ? Tone.STEEL : Tone.BLUE,
                d::dispose);
        Action never = new Action("DON'T ASK AGAIN", Tone.GREY, () -> {
            SetupCheck.ignore(checkEngineFolder, w.key());
            d.dispose();
        });
        List<Section> sections = List.of(
                new Section(w.panel(), Tone.BLUE, Icon.INFO, List.of(Line.strong(w.problem()), Line.dim(w.detail()))),
                new Section("FIX", Tone.GREEN, Icon.CHECK, w.fixNote() == null ? List.of(Line.text(w.fix()))
                        : java.util.stream.Stream.concat(java.util.stream.Stream.of(Line.text(w.fix())),
                                java.util.Arrays.stream(w.fixNote().split("\n")).map(Line::dim)).toList()));
        Page page = new Page(Tone.BLUE, w.headline(), null, w.code(), w.codeLabel(), sections,
                w.quitFirst() ? List.of(quit, go, never) : List.of(go, quit, never), null, null);
        d.setContentPane(frame.render(page));
        d.pack();
        d.setResizable(false);
        d.setLocationRelativeTo(null);
        Timer blink = new Timer(550, e -> frame.lamps().forEach(Bezel::toggle));
        blink.start();
        d.setVisible(true);
        blink.stop();
        return choice.get();
    }

    /** The first page: what's wrong, what changed, the fix. */
    static Page mainPage(Message m, PackInfo pack, AtomicReference<Choice> choice, JDialog d, Runnable openStripMine,
                         Runnable openGoBack) {
        Tone tone = m.level() == Level.WILL_FAIL ? Tone.RED : m.level() == Level.FOUND ? Tone.GREEN : Tone.AMBER;
        List<Section> sections = new ArrayList<>();
        List<Line> problem = new ArrayList<>();
        for (int i = 0; i < m.problems().size(); i++) {
            problem.add(Line.strong(m.problems().get(i)));
            if (i == 0) m.details().forEach(dl -> problem.add(Line.dim(dl)));
        }
        sections.add(new Section(m.level() == Level.WILL_FAIL ? "WHAT'S WRONG"
                : m.level() == Level.FOUND ? (m.stripMineConfigs() ? "THE BAD CONFIG" : "THE FAULTY MOD") : "LAST CRASH", tone,
                m.level() == Level.FOUND ? Icon.CHECK : Icon.ALERT, problem));
        if (!m.changed().isEmpty()) {
            sections.add(new Section("CHANGED SINCE IT LAST WORKED", Tone.AMBER, Icon.CHANGED,
                    m.changed().stream().map(Line::text).toList()));
        }
        if (!m.fixes().isEmpty()) {
            sections.add(new Section("FIX", Tone.GREEN, Icon.CHECK, m.fixes().stream()
                    .map(f -> Line.text(m.fixes().size() > 1 ? "• " + f : f)).toList()));
        }
        List<Action> actions = new ArrayList<>();
        actions.add(new Action(m.quitFix() != null ? "QUIT AND FIX IT" : "QUIT", m.quitFix() != null ? Tone.GREEN : Tone.RED, () -> {
            if (m.quitFix() != null) m.quitFix().run();
            choice.set(Choice.QUIT);
            d.dispose();
        }));
        if (m.goBackRun() != null) actions.add(new Action("GO BACK", Tone.AMBER, openGoBack));
        if (m.stripMine() != null) actions.add(new Action("STRIP MINE", Tone.AMBER, openStripMine));
        // big when loading anyway is a real choice; after "won't start" it only ends in the loader's error
        actions.add(new Action("LOAD ANYWAY", m.level() == Level.MAY_FAIL ? Tone.STEEL : Tone.GREY, d::dispose));
        if (m.report() != null) actions.add(new Action("FULL ERROR", Tone.GREY, () -> openReport(m)));
        if (pack.supportLink() != null) actions.add(new Action("GET HELP", Tone.GREY, () -> browse(pack.supportLink())));
        String[] fault = faultCode(m).split("·");
        return new Page(tone, headline(m, pack), null, fault[0].strip(), fault.length > 1 ? fault[1].strip() : null,
                sections, actions, null, m.stripMineMods() > 1 ? m.stripMineMods()
                + (m.stripMineConfigs() ? " CONFIGS" : " MODS") : null);
    }

    /** "TEST PACK WON'T START" when the pack's name is known (and short enough), else the plain headline. */
    static String headline(Message m, PackInfo pack) {
        String name = pack.headlineName();
        if (name == null) return m.headline();
        return switch (m.level()) {
            case WILL_FAIL -> name + " WON'T START";
            case MAY_FAIL -> name + " WILL PROBABLY CRASH AGAIN";
            default -> m.headline();
        };
    }

    private static void browse(String link) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(link));
        } catch (Exception ignored) {
            // no browser: the link is in the pack's config file
        }
    }

    /** Strip Mine over config files: no mod changed since the pack last worked, but several configs did. */
    private static Page stripMineConfigsPage(Message m, AtomicReference<Choice> choice, Frame frame, Runnable back) {
        int n = m.stripMineMods();
        List<Section> sections = List.of(
                new Section("HOW IT WORKS", Tone.AMBER, Icon.INFO, List.of(
                        Line.text("No mod changed since the pack last worked, but " + n + " config files did."),
                        Line.text("Check Engine puts half of them back as they were then, and you launch."),
                        Line.text("Keep pressing Play until it says which file it is."))),
                new Section("SAFE", Tone.GREEN, Icon.CHECK, List.of(
                        Line.text("Your own versions wait in checkengine/config-strip and all come back at the end."),
                        Line.text("You can stop any time from the title screen."))),
                new Section("WHAT HAPPENS NOW", Tone.GREY, Icon.NEXT, List.of(
                        Line.text("The game closes. Press Play again to start round 1."))));
        List<Action> actions = List.of(
                new Action("START STRIP MINE", Tone.AMBER, () -> {
                    choice.set(Choice.STRIP_MINE);
                    frame.dialog().dispose();
                }),
                new Action("BACK", Tone.GREY, back));
        return new Page(Tone.AMBER, "STRIP MINE: FIND THE BAD CONFIG", "About " + m.stripMineRounds() + " launches",
                null, null, sections, actions, null, n + " CONFIGS");
    }

    /** "Go back to when it worked?": everything that changes, in player words, that it's safe, then GO BACK or CANCEL. */
    private static Page goBackPage(Message m, AtomicReference<Choice> choice, Frame frame, Runnable back) {
        List<String> lines = m.goBack().subList(1, m.goBack().size());
        List<Line> list = new ArrayList<>();
        lines.stream().limit(10).forEach(l -> list.add(l.contains("can't put it back") || l.startsWith("Can't keep")
                ? Line.dim("• " + l) : Line.text("• " + l)));
        if (lines.size() > 10) list.add(Line.dim("...and " + (lines.size() - 10) + " more"));
        List<Section> sections = new ArrayList<>();
        if (m.isStripMineLocked()) {
            sections.add(new Section("FIRST: UNLOCK THE PACK", Tone.RED, Icon.ALERT, List.of(
                    Line.text("CurseForge has this pack locked, so it would put switched-off mods straight back."),
                    Line.text("In the CurseForge app: three dots next to Play > Profile options >"),
                    Line.text("turn on \"Allow content management\". Then come back here."))));
        }
        LaunchIntercept.GoBackOffer offer = m.goBackRun();
        list.add(0, Line.dim(m.goBack().get(0) + "."));
        sections.add(new Section("WHAT CHANGES", Tone.AMBER, Icon.CHANGED, list));
        if (offer.choice()) {
            sections.add(new Section("YOUR CONFIG EDITS?", Tone.BLUE, Icon.INFO, List.of(
                    Line.text("Mods only: your configs stay as they are now (your own pack work is kept)."),
                    Line.text("Mods and configs: configs go back to when it worked too."))));
        }
        sections.add(new Section("SAFE", Tone.GREEN, Icon.CHECK, List.of(
                Line.text("Nothing is deleted: mods are renamed to .disabled, your configs move to checkengine."),
                Line.dim("Changed your mind? Check Pack.bat (goback putback) puts it all back."))));
        sections.add(new Section("WHAT HAPPENS NOW", Tone.GREY, Icon.NEXT, List.of(
                Line.text("The game closes. Press Play again: the pack is back to when it worked."),
                Line.dim("The CurseForge app may still list the newer versions: that's only its own list."))));
        List<Action> actions = new ArrayList<>();
        if (offer.choice()) {
            Action all = goBackAction("MODS AND CONFIGS", offer.configsFirst() ? Tone.GREEN : Tone.STEEL, true, m,
                    choice, frame);
            Action mods = goBackAction("MODS ONLY", offer.configsFirst() ? Tone.STEEL : Tone.GREEN, false, m, choice,
                    frame);
            actions.add(offer.configsFirst() ? all : mods);
            actions.add(offer.configsFirst() ? mods : all);
        } else {
            actions.add(goBackAction("GO BACK", Tone.GREEN, true, m, choice, frame));
        }
        actions.add(new Action("CANCEL", Tone.GREY, back)); // not "Back": beside "Go back" it reads like the same button
        return new Page(Tone.AMBER, "GO BACK TO WHEN IT WORKED?", offer.lastWorked(), null, null, sections, actions,
                null, null);
    }

    /** One Go back button: does it, then the game closes; if it fails, says so instead of closing. */
    private static Action goBackAction(String label, Tone tone, boolean configs, Message m,
                                       AtomicReference<Choice> choice, Frame frame) {
        return new Action(label, tone, () -> {
            try {
                m.goBackRun().run().accept(configs);
                choice.set(Choice.QUIT);
                frame.dialog().dispose();
            } catch (RuntimeException e) {
                frame.swap(frame.render(goBackFailedPage(e, choice, frame)));
            }
        });
    }

    private static Page goBackFailedPage(RuntimeException e, AtomicReference<Choice> choice, Frame frame) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        List<Section> sections = List.of(
                new Section("WHAT HAPPENED", Tone.RED, Icon.ALERT, List.of(
                        Line.text("Go back stopped halfway: " + cause.getClass().getSimpleName()
                                + (cause.getMessage() != null ? ": " + cause.getMessage() : "")),
                        Line.dim("Often another program has a mod file open. Close it and try again."))),
                new Section("SAFE", Tone.GREEN, Icon.CHECK, List.of(
                        Line.text("Nothing is deleted. Check Pack.bat (goback putback) undoes what was done."))));
        return new Page(Tone.RED, "COULDN'T GO BACK", null, null, null, sections, List.of(
                new Action("QUIT", Tone.RED, () -> {
                    choice.set(Choice.QUIT);
                    frame.dialog().dispose();
                })), null, null);
    }

    /** What Strip Mine is, before it starts, with Start and Back. */
    private static Page stripMinePage(Message m, boolean stillLocked, AtomicReference<Choice> choice, Frame frame,
                                      Runnable back) {
        if (m.stripMineConfigs()) return stripMineConfigsPage(m, choice, frame, back);
        List<Section> sections = new ArrayList<>();
        // CurseForge-installed packs are locked: the app would download the switched-off mods again.
        if (m.isStripMineLocked()) {
            List<Line> unlock = new ArrayList<>(List.of(
                    Line.text("CurseForge has this pack locked, so it would put switched-off mods straight back."),
                    Line.text("In the CurseForge app: three dots next to Play > Profile options >"),
                    Line.text("turn on \"Allow content management\". Then press Start.")));
            if (stillLocked) unlock.add(Line.strong("Still locked: turn on \"Allow content management\" first."));
            sections.add(new Section("FIRST: LET STRIP MINE CHANGE THIS PACK", Tone.RED, Icon.ALERT, unlock));
        }
        sections.add(new Section("HOW IT WORKS", Tone.AMBER, Icon.INFO, List.of(
                Line.text("Check Engine switches off half of your mods, and you launch."),
                Line.text("It crashes: the faulty mod is still on. No crash: it's in the half that was off."),
                Line.text("Mods you changed lately are tested first. Keep pressing Play until it says which mod."))));
        sections.add(new Section("SAFE", Tone.GREEN, Icon.CHECK, List.of(
                Line.text("Nothing is deleted: mods are renamed to .disabled and switched back on at the end."),
                Line.text("You can stop any time from the title screen, and every mod goes back."))));
        sections.add(new Section("WHAT HAPPENS NOW", Tone.GREY, Icon.NEXT, List.of(
                Line.text("The game closes. Press Play again to start round 1."))));
        List<Action> actions = List.of(
                new Action("START STRIP MINE", Tone.AMBER, () -> {
                    if (m.isStripMineLocked()) { // checked again: the app saves the switch right away
                        frame.swap(frame.render(stripMinePage(m, true, choice, frame, back)));
                        return;
                    }
                    choice.set(Choice.STRIP_MINE);
                    frame.dialog().dispose();
                }),
                new Action("BACK", Tone.GREY, back));
        return new Page(Tone.AMBER, "STRIP MINE: FIND THE FAULTY MOD", m.stripMineMods() > 1
                ? "About " + m.stripMineRounds() + " launches, fewer if you changed mods lately" : null, null, null,
                sections, actions, null, m.stripMineMods() > 1 ? m.stripMineMods() + " MODS" : null);
    }

    /** The window and what every page in it shares: the logos and the blinking lamps. */
    private record Frame(JDialog dialog, Image logo, Image uan, List<Bezel> lamps) {
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
            JPanel root = new Dashboard(new BorderLayout());

            JPanel top = column();
            top.add(new Hazard(tone, p.tone() == Tone.RED || p.tone() == Tone.AMBER));
            JPanel header = new JPanel(new BorderLayout(14, 0));
            header.setOpaque(false);
            header.setBorder(new EmptyBorder(10, 12, 8, 20));
            header.setAlignmentX(0f);
            Bezel lamp = new Bezel(logo, tone);
            lamps.add(lamp);
            header.add(lamp, BorderLayout.WEST);
            JPanel titles = column();
            titles.add(Box.createVerticalGlue());
            titles.add(label("CHECK ENGINE", tracked(font(Font.BOLD, 23), 0.1f), TEXT));
            titles.add(new GlowLabel(p.headline(), tracked(font(Font.BOLD, 16), 0.03f), tone));
            if (p.subline() != null) titles.add(label(p.subline(), font(Font.PLAIN, 12), DIM));
            titles.add(Box.createVerticalGlue());
            header.add(titles, BorderLayout.CENTER);
            if (p.code() != null) header.add(readout(p, tone), BorderLayout.EAST);
            top.add(header);
            root.add(top, BorderLayout.NORTH);

            JPanel body = column();
            body.setBorder(new EmptyBorder(0, 18, 0, 18));
            for (int i = 0; i < p.sections().size(); i++) {
                if (i > 0) body.add(Box.createVerticalStrut(9));
                body.add(section(p.sections().get(i)));
            }
            root.add(body, BorderLayout.CENTER);

            JPanel bottom = column();
            if (p.help() != null) {
                JLabel help = label(p.help(), font(Font.PLAIN, 13), DIM);
                help.setBorder(new EmptyBorder(10, 18, 0, 18));
                bottom.add(help);
            }
            JPanel buttons = new JPanel();
            buttons.setOpaque(false);
            buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
            buttons.setBorder(new EmptyBorder(14, 18, 14, 18));
            buttons.setAlignmentX(0f);
            boolean first = true;
            for (Action a : p.actions()) {
                if (a.tone() == Tone.GREY) continue;
                if (!first) buttons.add(Box.createHorizontalStrut(10));
                buttons.add(new SwitchButton(a));
                first = false;
            }
            buttons.add(Box.createHorizontalGlue());
            for (Action a : p.actions()) {
                if (a.tone() != Tone.GREY) continue;
                buttons.add(Box.createHorizontalStrut(8));
                buttons.add(new SmallButton(a));
            }
            bottom.add(buttons);
            bottom.add(footer(uan, p.note()));
            root.add(bottom, BorderLayout.SOUTH);
            root.setPreferredSize(new Dimension(Math.max(WIDTH, root.getPreferredSize().width), root.getPreferredSize().height));
            return root;
        }
    }

    // ---- pieces of the frame ----

    /** Dark dashboard background: a soft top light and faint brushed-metal lines. */
    private static final class Dashboard extends JPanel {
        Dashboard(java.awt.LayoutManager layout) {
            super(layout);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth(), h = getHeight();
            g2.setPaint(new RadialGradientPaint(w / 2f, -h * 0.1f, Math.max(w, h) * 0.8f, new float[] {0f, 1f},
                    new Color[] {new Color(0x2A2B30), new Color(0x131416)}));
            g2.fillRect(0, 0, w, h);
            g2.setColor(new Color(255, 255, 255, 7));
            for (int x = 0; x < w; x += 3) g2.drawLine(x, 0, x, h);
            g2.dispose();
        }
    }

    /** The stripe across the top: hazard tape for warnings, a plain bar for good news and info. */
    private static final class Hazard extends JComponent {
        private final Color tone;
        private final boolean warning;

        Hazard(Color tone, boolean warning) {
            this.tone = tone;
            this.warning = warning;
            int h = warning ? 6 : 4;
            setPreferredSize(new Dimension(WIDTH, h));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
            setAlignmentX(0f);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = getHeight();
            if (!warning) {
                g2.setColor(tone);
                g2.fillRect(0, 0, getWidth(), h);
                g2.dispose();
                return;
            }
            g2.setColor(new Color(0x141414));
            g2.fillRect(0, 0, getWidth(), h);
            g2.setColor(tone);
            for (int x = -h; x < getWidth() + h; x += 20) {
                g2.fill(new Polygon(new int[] {x, x + 10, x + 10 + h, x + h}, new int[] {h, h, 0, 0}, 4));
            }
            g2.dispose();
        }
    }

    /** The Check Engine logo in a round metal bezel, with a lamp ring that blinks like the real dashboard light. */
    private static final class Bezel extends JComponent {
        private final Image logo;
        private final Color tone;
        private boolean on = true;

        Bezel(Image logo, Color tone) {
            this.logo = logo;
            this.tone = tone;
            setPreferredSize(new Dimension(92, 92));
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
            float c = getWidth() / 2f;
            if (on) { // glow around the bezel
                g2.setPaint(new RadialGradientPaint(c, c, c, new float[] {0.7f, 1f},
                        new Color[] {alpha(tone, 110), alpha(tone, 0)}));
                g2.fill(new Ellipse2D.Float(0, 0, 2 * c, 2 * c));
            }
            float r = 37; // metal ring
            g2.setPaint(new GradientPaint(c - r, c - r, new Color(0x7A7D84), c + r, c + r, new Color(0x222428)));
            g2.fill(new Ellipse2D.Float(c - r, c - r, 2 * r, 2 * r));
            g2.setPaint(new GradientPaint(c - r, c + r, new Color(0x5C5F66), c + r, c - r, new Color(0x2A2C30)));
            g2.fill(new Ellipse2D.Float(c - r + 4, c - r + 4, 2 * r - 8, 2 * r - 8));
            float l = 30; // the lamp
            g2.setColor(new Color(0x0D0D0F));
            g2.fill(new Ellipse2D.Float(c - l, c - l, 2 * l, 2 * l));
            g2.setPaint(new RadialGradientPaint(c, c, l, new float[] {0.6f, 1f},
                    new Color[] {alpha(tone, 0), alpha(tone, on ? 150 : 50)}));
            g2.fill(new Ellipse2D.Float(c - l, c - l, 2 * l, 2 * l));
            g2.setColor(on ? tone : tone.darker().darker());
            g2.setStroke(new BasicStroke(2f));
            g2.draw(new Ellipse2D.Float(c - l, c - l, 2 * l, 2 * l));
            if (logo != null) {
                // The logo is square with a dark background: cut it to a circle, zoomed in so the block fills it.
                int inner = 48;
                BufferedImage img = new BufferedImage(inner, inner, BufferedImage.TYPE_INT_ARGB);
                Graphics2D gi = img.createGraphics();
                gi.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                gi.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                gi.fill(new Ellipse2D.Float(0, 0, inner, inner));
                gi.setComposite(java.awt.AlphaComposite.SrcIn);
                int zoom = inner / 8;
                gi.drawImage(logo, -zoom, -zoom, inner + 2 * zoom, inner + 2 * zoom, null);
                gi.dispose();
                g2.drawImage(img, Math.round(c - inner / 2f), Math.round(c - inner / 2f), null);
            }
            g2.dispose();
        }
    }

    /** The fault code in a lit LCD readout, with what it means and when. */
    private static JComponent readout(Page p, Color tone) {
        JPanel col = column();
        Lcd lcd = new Lcd(p.code(), tone);
        lcd.setAlignmentX(1f);
        col.add(lcd);
        col.add(Box.createVerticalStrut(5));
        if (p.codeLabel() != null) {
            JLabel what = label(p.codeLabel(), tracked(font(Font.PLAIN, 11), 0.08f), DIM);
            what.setAlignmentX(1f);
            col.add(what);
        }
        JLabel when = label(DateTimeNow.now().toUpperCase(), font(Font.PLAIN, 10), FAINT);
        when.setAlignmentX(1f);
        col.add(when);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(new EmptyBorder(8, 0, 0, 0));
        wrap.add(col, BorderLayout.NORTH);
        return wrap;
    }

    private static final class Lcd extends JComponent {
        private final String text;
        private final Color tone;
        private final Font font;

        Lcd(String text, Color tone) {
            this.text = text;
            this.tone = tone;
            String mono = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                    .contains("Consolas") ? "Consolas" : Font.MONOSPACED;
            this.font = tracked(new Font(mono, Font.BOLD, 22), 0.12f);
            FontMetrics fm = getFontMetrics(font);
            Dimension size = new Dimension(fm.stringWidth(text) + 24, fm.getHeight() + 6);
            setPreferredSize(size);
            setMaximumSize(size);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(new Color(0x0A0B0C));
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, 8, 8));
            g2.setColor(new Color(0x33353A));
            g2.draw(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, 8, 8));
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            int x = (w - fm.stringWidth(text)) / 2, y = (h - fm.getHeight()) / 2 + fm.getAscent();
            glow(g2, text, x, y, tone);
            g2.dispose();
        }
    }

    /** A label whose text glows a little in its colour, like a backlit gauge. */
    private static final class GlowLabel extends JLabel {
        GlowLabel(String text, Font f, Color c) {
            super(text);
            setFont(fit(f, text));
            setForeground(c);
            setAlignmentX(0f);
            setBorder(new EmptyBorder(1, 0, 1, 0));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            glow(g2, getText(), 0, getInsets().top + fm.getAscent(), getForeground());
            g2.dispose();
        }
    }

    /** Text with a soft glow behind it. */
    private static void glow(Graphics2D g2, String text, int x, int y, Color c) {
        g2.setColor(alpha(c, 22));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) if (dx != 0 || dy != 0) g2.drawString(text, x + dx, y + dy);
        }
        g2.setColor(c);
        g2.drawString(text, x, y);
    }

    /** A screwed-on panel: round icon badge, coloured title, the lines. */
    private static JComponent section(Section s) {
        Color tone = color(s.tone());
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g2.setColor(new Color(0, 0, 0, 90)); // shadow
                g2.fill(new RoundRectangle2D.Float(0, 2, w, h - 2, 12, 12));
                g2.setPaint(new GradientPaint(0, 0, PANEL_TOP, 0, h, PANEL_BOTTOM));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h - 2, 12, 12));
                g2.setColor(EDGE);
                g2.drawLine(6, 0, w - 7, 0);
                badge(g2, s.icon(), tone, 12, 11);
                screw(g2, w - 13, 6);
                screw(g2, w - 13, h - 15);
                g2.dispose();
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(10, 46, 12, 24));
        p.setAlignmentX(0f);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        p.add(label(s.title(), tracked(font(Font.BOLD, 12), 0.1f), tone));
        JPanel lines = column();
        for (Line l : s.lines()) {
            Font f = font(l.style() == Style.STRONG ? Font.BOLD : Font.PLAIN, l.style() == Style.STRONG ? 15 : 14);
            // long sentences wrap instead of stretching the window
            for (String part : wrap(l.text(), f, MAX_LINE)) {
                lines.add(label(part, f, l.style() == Style.DIM ? DIM : TEXT));
            }
        }
        p.add(lines.getComponentCount() > VISIBLE_LINES ? scroll(lines) : lines);
        return p;
    }

    private static void badge(Graphics2D g2, Icon icon, Color tone, int x, int y) {
        g2.setColor(tone);
        g2.fill(new Ellipse2D.Float(x, y, 22, 22));
        g2.setColor(new Color(0x111111));
        g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float cx = x + 11, cy = y + 11;
        switch (icon) {
            case ALERT -> {
                g2.draw(new Line2D.Float(cx, cy - 6, cx, cy + 1.5f));
                g2.fill(new Ellipse2D.Float(cx - 1.7f, cy + 3.8f, 3.4f, 3.4f));
            }
            case CHECK -> g2.draw(polyline(cx - 5.5f, cy, cx - 1.5f, cy + 4, cx + 6, cy - 4.5f));
            case CHANGED -> {
                g2.draw(new Arc2D.Float(cx - 6, cy - 6, 12, 12, 70, 270, Arc2D.OPEN));
                g2.fill(new Polygon(new int[] {Math.round(cx + 1), Math.round(cx + 7), Math.round(cx + 2)},
                        new int[] {Math.round(cy - 9), Math.round(cy - 6), Math.round(cy - 2)}, 3));
            }
            case INFO -> {
                g2.fill(new Ellipse2D.Float(cx - 1.7f, cy - 7.5f, 3.4f, 3.4f));
                g2.draw(new Line2D.Float(cx, cy - 1, cx, cy + 6));
            }
            case NEXT -> {
                g2.draw(new Line2D.Float(cx - 6, cy, cx + 5, cy));
                g2.draw(polyline(cx + 1, cy - 4.5f, cx + 5.5f, cy, cx + 1, cy + 4.5f));
            }
        }
    }

    private static Path2D polyline(float... xy) {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        return path;
    }

    private static void screw(Graphics2D g2, int x, int y) {
        g2.setPaint(new RadialGradientPaint(x + 2.5f, y + 2.5f, 5, new float[] {0f, 1f},
                new Color[] {new Color(0x8A8D94), new Color(0x3A3C41)}));
        g2.fill(new Ellipse2D.Float(x, y, 7, 7));
        g2.setColor(new Color(0x26282C));
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(x + 2, y + 5, x + 5, y + 2); // the slot
    }

    /** A chunky dashboard switch with an LED, for the main actions. */
    private static final class SwitchButton extends JButton {
        private final Color top, bottom, shade, led;

        SwitchButton(Action a) {
            super(a.label());
            Color[] c = switch (a.tone()) {
                case RED -> new Color[] {new Color(0xFF5F52), new Color(0xD93A2E), new Color(0x8E2119), new Color(0xFFD9D6)};
                case GREEN -> new Color[] {new Color(0x34B045), new Color(0x1F7A2A), new Color(0x0F4416), new Color(0xC8FFD0)};
                case BLUE -> new Color[] {new Color(0x5B97F7), new Color(0x2F6FE0), new Color(0x1B3F86), new Color(0xD6E6FF)};
                case STEEL -> new Color[] {new Color(0x8E99A8), new Color(0x5C6676), new Color(0x2F3540), new Color(0xE2E8F0)};
                default -> new Color[] {new Color(0xC98420), new Color(0x8A5A12), new Color(0x4F3309), new Color(0xFFCF86)};
            };
            top = c[0];
            bottom = c[1];
            shade = c[2];
            led = c[3];
            setFont(fit(tracked(font(Font.BOLD, 14), 0.06f), a.label()));
            setForeground(Color.WHITE);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setBorder(new EmptyBorder(10, 32, 13, 18));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMaximumSize(getPreferredSize());
            addActionListener(e -> a.run().run());
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight() - 3;
            int dy = getModel().isPressed() ? 2 : 0;
            g2.setColor(shade);
            g2.fill(new RoundRectangle2D.Float(0, 3, w, h, 12, 12));
            Color t = getModel().isRollover() ? top.brighter() : top;
            g2.setPaint(new GradientPaint(0, dy, t, 0, h + dy, bottom));
            g2.fill(new RoundRectangle2D.Float(0, dy, w, h, 12, 12));
            g2.setColor(new Color(255, 255, 255, 70));
            g2.drawLine(6, dy + 1, w - 7, dy + 1);
            float lx = 15, ly = h / 2f + dy;
            g2.setPaint(new RadialGradientPaint(lx, ly, 8, new float[] {0f, 1f},
                    new Color[] {alpha(led, 200), alpha(led, 0)}));
            g2.fill(new Ellipse2D.Float(lx - 8, ly - 8, 16, 16));
            g2.setColor(led);
            g2.fill(new Ellipse2D.Float(lx - 4, ly - 4, 8, 8));
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int ty = dy + (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.setColor(new Color(0, 0, 0, 80));
            g2.drawString(getText(), 32, ty + 1);
            g2.setColor(Color.WHITE);
            g2.drawString(getText(), 32, ty);
            g2.dispose();
        }
    }

    /** A small outlined button, for the less common actions. */
    private static final class SmallButton extends JButton {
        SmallButton(Action a) {
            super(a.label());
            setFont(fit(tracked(font(Font.BOLD, 12), 0.05f), a.label()));
            setForeground(new Color(0xC9CBD1));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setBorder(new EmptyBorder(8, 12, 8, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMaximumSize(getPreferredSize());
            addActionListener(e -> a.run().run());
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (getModel().isRollover()) {
                g2.setColor(new Color(255, 255, 255, 14));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 9, 9));
            }
            g2.setColor(getModel().isRollover() ? new Color(0x6A6D74) : new Color(0x3D4046));
            g2.draw(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 9, 9));
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(getForeground());
            g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                    (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** The bottom strip: the UAN mark and "Check Engine", and a small note on the right. */
    private static JComponent footer(Image uan, String note) {
        JPanel f = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(new Color(0x0F1012));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(new Color(0x2A2C30));
                g.drawLine(0, 0, getWidth(), 0);
            }
        };
        f.setBorder(new EmptyBorder(8, 12, 8, 18));
        f.setAlignmentX(0f);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        if (uan != null) left.add(new JLabel(new ImageIcon(uan.getScaledInstance(20, 20, Image.SCALE_SMOOTH))));
        left.add(label("Check Engine", font(Font.BOLD, 12), new Color(0x9A9CA3)));
        f.add(left, BorderLayout.WEST);
        if (note != null) {
            String mono = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                    .contains("Consolas") ? "Consolas" : Font.MONOSPACED;
            f.add(label(note, tracked(new Font(mono, Font.PLAIN, 11), 0.05f), new Color(0x5F6168)), BorderLayout.EAST);
        }
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        return f;
    }

    // ---- helpers ----

    private static Color color(Tone t) {
        return switch (t) {
            case RED -> RED;
            case AMBER -> AMBER;
            case GREEN -> GREEN;
            case BLUE -> BLUE;
            case GREY, STEEL -> DIM;
        };
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
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

    /** A scrolling list that shows about {@link #VISIBLE_LINES} lines, with a thin dashboard-style scrollbar. */
    private static JComponent scroll(JPanel list) {
        JScrollPane sp = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setAlignmentX(0f);
        int row = list.getComponent(0).getPreferredSize().height;
        sp.setPreferredSize(new Dimension(list.getPreferredSize().width + 14, row * (VISIBLE_LINES + 1) + row / 2));
        JScrollBar bar = sp.getVerticalScrollBar();
        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(8, 0));
        bar.setUnitIncrement(row);
        bar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = new Color(0x5E6168);
                trackColor = PANEL_BOTTOM;
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
        p.setAlignmentX(0f);
        return p;
    }

    /** Widest a panel line gets before it wraps (pixels). */
    private static final int MAX_LINE = 640;

    /** Splits {@code text} at spaces into lines no wider than {@code max} pixels in font {@code f}. */
    static List<String> wrap(String text, Font f, int max) {
        java.awt.FontMetrics fm = new JLabel().getFontMetrics(f);
        if (fm.stringWidth(text) <= max) return List.of(text);
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && fm.stringWidth(next) > max) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    private static JLabel label(String text, Font f, Color c) {
        JLabel l = new JLabel(text);
        l.setFont(fit(f, text));
        l.setForeground(c);
        l.setAlignmentX(0f);
        l.setBorder(new EmptyBorder(1, 0, 1, 0));
        return l;
    }

    /** Bahnschrift has no arrows; anything it can't draw falls back to a font that can. */
    private static Font fit(Font f, String text) {
        return f.canDisplayUpTo(text) == -1 ? f : new Font(Font.SANS_SERIF, f.getStyle(), f.getSize());
    }

    /** Bahnschrift is Windows' dashboard-style font; anything else falls back to the system sans-serif. */
    private static Font font(int style, int size) {
        String name = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                .contains("Bahnschrift") ? "Bahnschrift" : "SansSerif";
        return new Font(name, style, size);
    }

    /** The font with wider letter spacing. */
    private static Font tracked(Font f, float amount) {
        return f.deriveFont(Map.of(TextAttribute.TRACKING, amount));
    }

    /** "14 Oct 21:05", for the fault readout. */
    private static final class DateTimeNow {
        static String now() {
            return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM HH:mm"));
        }
    }
}
