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
import com.guiltypotato.checkengine.core.crash.CrashHelp;
import com.guiltypotato.checkengine.core.ui.EngineIcon;
import com.guiltypotato.checkengine.core.ui.ScreenHelp;
import com.guiltypotato.checkengine.core.ui.UiText;
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
import javax.swing.JLayeredPane;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * The Check Engine window, in the same zones as the in-game screens: the engine light and headline, the fault code and
 * date small and grey, a dark list area, then Help, a divider, the choices and Quit, in a free pixel font (Pixel
 * Operator, CC0). Plain
 * Swing, so it can open before Minecraft (and its window) exists. Every page is drawn in the same frame ({@link Page}):
 * features only decide the text and buttons.
 */
final class Popup {
    private static final Color TEXT = new Color(0xF0F0F0);
    private static final Color DIM = new Color(0xA8A8A8);
    private static final Color RED = new Color(0xFF5555);
    private static final Color AMBER = new Color(0xFFAA00);
    private static final Color GREEN = new Color(0x55FF55);
    private static final Color BLUE = new Color(0x55AAFF);
    private static final Color WINDOW = new Color(0x1C1C1C);
    private static final Color LIST = new Color(0x101010);
    private static final Color RULE = new Color(0x5A5A5A);
    private static final Color SHADOW = new Color(0x2A2A2A);
    /** Lines a panel shows before it scrolls (the window keeps its size). */
    private static final int VISIBLE_LINES = 8;
    private static final String ISSUES = "https://github.com/Uanme37/CheckEngine/issues";
    /** The first two characters of a line that starts with the check mark, drawn in pixels (the font has none). */
    private static final String TICK = "✓ ";
    private static final String[] TICK_SHAPE = {".....#", "....#.", "#..#..", ".#.#..", "..#..."};
    private static final int WIDTH = 640;
    /** The tallest page at normal size, with the title bar. */
    private static final int TALLEST = 500;
    /** The pixel font's own size: sharp only at this size. */
    private static final int TEXT_SIZE = 16;
    private static final int READING_SIZE = 12;
    private static Font PLAIN_FONT = load("/checkengine_pixel.ttf", Font.PLAIN);
    private static Font BOLD_FONT = load("/checkengine_pixel_bold.ttf", Font.BOLD);
    /** The window's zoom: half as big again on a normal screen, as it is when that won't fit. */
    private static double zoom = 1;
    private static int lastLight = EngineIcon.GREEN;
    /** The page showing now, so a help page can find its window. */
    private static final AtomicReference<JPanel> CURRENT = new AtomicReference<>();

    private Popup() {}

    /** Called once there's a screen: sizes everything for it. */
    private static void sizeForScreen() {
        if (zoom != 1) return;
        int height = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getBounds().height;
        if (height < px(TALLEST) * 3 / 2) return;
        zoom = 1.5;
        // The pixel font is only sharp at whole sizes and tiring at this one: the system's own reading font instead
        PLAIN_FONT = new Font("Segoe UI", Font.PLAIN, px(READING_SIZE));
        BOLD_FONT = new Font("Segoe UI", Font.BOLD, px(READING_SIZE));
    }

    /** Text: smooth with the reading font; the pixel font stays crisp and gets its shadow. */
    private static void drawText(Graphics2D g2, String text, int x, int y, Color color) {
        if (zoom == 1) {
            g2.setColor(SHADOW);
            g2.drawString(text, x + 2, y + 2);
        } else {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        }
        g2.setColor(color);
        g2.drawString(text, x, y);
    }

    /** A size at this zoom; thin lines stay one pixel. */
    private static int px(int n) {
        return (int) (n * zoom);
    }

    private static EmptyBorder pad(int top, int left, int bottom, int right) {
        return new EmptyBorder(px(top), px(left), px(bottom), px(right));
    }

    private static MatteBorder rule(int top, int left, int bottom, int right) {
        return new MatteBorder(top, left, bottom, right, RULE);
    }

    static Choice show(Message m, byte[] logoPng) throws Exception {
        return show(m, logoPng, new PackInfo(null, null));
    }

    static Choice show(Message m, byte[] logoPng, PackInfo pack) throws Exception {
        System.setProperty("java.awt.headless", "false");
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("no screen");
        sizeForScreen();
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        AtomicReference<Choice> choice = new AtomicReference<>(Choice.CONTINUE);

        JDialog d = new JDialog((java.awt.Frame) null, "Check Engine", true);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.setAlwaysOnTop(true);
        Image logo = logoPng == null ? null : ImageIO.read(new ByteArrayInputStream(logoPng));
        if (logo != null) d.setIconImage(logo);
        Frame frame = new Frame(d, logo, image("/checkengine_uan.png"), new ArrayList<>(), pack);

        AtomicReference<JPanel> main = new AtomicReference<>();
        Runnable back = () -> frame.swap(main.get());
        main.set(frame.render(mainPage(m, pack, choice, d,
                () -> frame.swap(frame.render(stripMinePage(m, false, choice, frame, back))),
                () -> frame.swap(frame.render(goBackPage(m, choice, frame, back))))));

        d.setContentPane(main.get());
        d.pack();
        d.setResizable(false);
        d.setLocationRelativeTo(null);
        d.setVisible(true); // blocks until closed
        return choice.get();
    }

    /** A launcher setting that will hurt the pack (memory, Java): a blue window with the fix and "Don't ask again". */
    static Choice showSetting(SetupCheck.Warning w, byte[] logoPng, Path checkEngineFolder) throws Exception {
        System.setProperty("java.awt.headless", "false");
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("no screen");
        sizeForScreen();
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        AtomicReference<Choice> choice = new AtomicReference<>(Choice.CONTINUE);
        JDialog d = new JDialog((java.awt.Frame) null, "Check Engine", true);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.setAlwaysOnTop(true);
        Image logo = logoPng == null ? null : ImageIO.read(new ByteArrayInputStream(logoPng));
        if (logo != null) d.setIconImage(logo);
        Frame frame = new Frame(d, logo, image("/checkengine_uan.png"), new ArrayList<>(),
                PackInfo.load(checkEngineFolder.getParent()));

        Action quit = new Action("Quit", Tone.GREY, () -> {
            choice.set(Choice.QUIT);
            d.dispose();
        });
        Action go = new Action(w.quitFirst() ? "Load anyway" : "Continue", Tone.GREY, d::dispose);
        Action never = new Action("Don't ask again", Tone.GREY, () -> {
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
        d.setVisible(true);
        return choice.get();
    }

    /** The first page: what's wrong, what changed, the fix. */
    static Page mainPage(Message m, PackInfo pack, AtomicReference<Choice> choice, JDialog d, Runnable openStripMine,
                         Runnable openGoBack) {
        // Green only when Strip Mine named one culprit: narrowed down or gave up still needs the player
        boolean named = m.problems().size() == 1 && m.problems().get(0).endsWith(" causes the crash");
        Tone tone = m.level() == Level.WILL_FAIL ? Tone.RED : m.level() == Level.FOUND && named ? Tone.GREEN : Tone.AMBER;
        List<Section> sections = new ArrayList<>();
        List<Line> problem = new ArrayList<>();
        for (int i = 0; i < m.problems().size(); i++) {
            problem.add(Line.strong(m.problems().get(i)));
            if (i == 0) m.details().forEach(dl -> problem.add(Line.dim(dl)));
        }
        sections.add(new Section(m.level() == Level.WILL_FAIL ? "WHAT'S WRONG"
                : m.level() == Level.FOUND ? (!named ? "WHAT IT FOUND" : m.stripMineConfigs() ? "THE BAD CONFIG" : "THE FAULTY MOD") : "LAST CRASH", tone,
                m.level() == Level.FOUND ? Icon.CHECK : Icon.ALERT, problem));
        if (!m.changed().isEmpty()) {
            sections.add(new Section(m.changedSince() == null ? "CHANGED SINCE IT LAST WORKED"
                    : "Changed since it last worked (" + m.changedSince() + ")", Tone.AMBER, Icon.CHANGED,
                    m.changed().stream().map(Line::text).toList()));
        }
        if (!m.fixes().isEmpty()) {
            sections.add(new Section("FIX", Tone.GREEN, Icon.CHECK, m.fixes().stream()
                    .map(f -> Line.text(m.fixes().size() > 1 ? "• " + f : f)).toList()));
        }
        List<Action> actions = new ArrayList<>();
        if (m.quitFix() != null) actions.add(new Action("Quit and fix it", Tone.GREEN, () -> {
            m.quitFix().run();
            choice.set(Choice.QUIT);
            d.dispose();
        }));
        if (m.goBackRun() != null) actions.add(new Action("Go back to when it worked", Tone.AMBER, openGoBack));
        if (m.stripMine() != null) actions.add(new Action("Strip Mine", Tone.AMBER, openStripMine));
        actions.add(new Action("Load anyway", Tone.GREY, d::dispose));
        if (m.report() != null) actions.add(new Action("Full error", Tone.GREY, () -> openReport(m)));
        actions.add(new Action("Quit", Tone.RED, () -> {
            choice.set(Choice.QUIT);
            d.dispose();
        }));
        String[] fault = faultCode(m).split("·");
        return new Page(tone, headline(m, pack), null, fault[0].strip(), null, sections, actions, null, null);
    }

    /** "TEST PACK WON'T START" when the pack's name is known (and short enough), else the plain headline. */
    static String headline(Message m, PackInfo pack) {
        String name = pack.headlineName();
        if (name == null) return m.headline();
        return switch (m.level()) {
            case WILL_FAIL -> name + " won't start";
            case MAY_FAIL -> name + " will probably crash again";
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
                new Action("Start Strip Mine", Tone.AMBER, () -> {
                    choice.set(Choice.STRIP_MINE);
                    frame.dialog().dispose();
                }),
                new Action("Cancel", Tone.GREY, back));
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
            Action all = goBackAction("Mods and configs", Tone.GREY, true, m,
                    choice, frame);
            Action mods = goBackAction("Mods only", Tone.GREY, false, m, choice,
                    frame);
            actions.add(offer.configsFirst() ? all : mods);
            actions.add(offer.configsFirst() ? mods : all);
        } else {
            actions.add(goBackAction("Go back", Tone.GREY, true, m, choice, frame));
        }
        actions.add(new Action("Cancel", Tone.GREY, back)); // not "Back": beside "Go back" it reads like the same button
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
                new Action("Quit", Tone.RED, () -> {
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
                new Action("Start Strip Mine", Tone.AMBER, () -> {
                    if (m.isStripMineLocked()) { // checked again: the app saves the switch right away
                        frame.swap(frame.render(stripMinePage(m, true, choice, frame, back)));
                        return;
                    }
                    choice.set(Choice.STRIP_MINE);
                    frame.dialog().dispose();
                }),
                new Action("Cancel", Tone.GREY, back));
        return new Page(Tone.AMBER, "STRIP MINE: FIND THE FAULTY MOD", m.stripMineMods() > 1
                ? "About " + m.stripMineRounds() + " launches, fewer if you changed mods lately" : null, null, null,
                sections, actions, null, m.stripMineMods() > 1 ? m.stripMineMods() + " MODS" : null);
    }

    /** The window and what every page in it shares. */
    private record Frame(JDialog dialog, Image logo, Image uan, List<Object> lamps, PackInfo pack) {
        /** Shows another page in the same window, sized to fit it and kept centred where it was. */
        void swap(JPanel page) {
            JDialog d = dialog;
            java.awt.Point centre = new java.awt.Point(d.getX() + d.getWidth() / 2, d.getY() + d.getHeight() / 2);
            d.setContentPane(page);
            d.pack();
            d.setLocation(centre.x - d.getWidth() / 2, centre.y - d.getHeight() / 2);
            d.repaint();
        }

        /** Help's menu, over the Help button. */
        void openHelp(Page p, JPanel current, JComponent button) {
            JLayeredPane layers = dialog.getLayeredPane();
            JPanel menu = column();
            menu.setOpaque(true);
            menu.setBackground(WINDOW);
            menu.setBorder(rule(1, 1, 1, 1));
            // A click anywhere else closes it
            JComponent outside = new JPanel();
            outside.setOpaque(false);
            outside.setBounds(0, 0, layers.getWidth(), layers.getHeight());
            Runnable close = () -> {
                layers.remove(menu);
                layers.remove(outside);
                layers.repaint();
            };
            outside.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    close.run();
                }
            });
            List<Action> rows = new ArrayList<>();
            boolean helpPage = "WHAT IS THIS SCREEN?".equals(p.headline()) || "COPIED FOR HELP".equals(p.headline());
            if (!helpPage) rows.add(new Action("What is this screen?", Tone.GREY, () -> swap(render(explain(p, current)))));
            if (!helpPage) rows.add(new Action("Copy for help", Tone.GREY, () -> swap(render(copied(p, current)))));
            if (pack.supportLink() != null) {
                rows.add(new Action(UiText.helpLinkLabel(pack.headlineName(), pack.supportLink()), Tone.GREY,
                        () -> browse(pack.supportLink())));
            }
            rows.add(new Action("Report to Check Engine", Tone.GREY, () -> browse(ISSUES)));
            for (Action a : rows) {
                McButton row = new McButton(new Action(a.label(), a.tone(), () -> {
                    close.run();
                    a.run().run();
                }), false);
                row.setAlignmentX(0f);
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
                menu.add(row);
            }
            Dimension size = menu.getPreferredSize();
            java.awt.Point at = javax.swing.SwingUtilities.convertPoint(button, 0, 0, layers);
            menu.setBounds(at.x, at.y - size.height, size.width, size.height);
            layers.add(outside, JLayeredPane.POPUP_LAYER);
            layers.add(menu, Integer.valueOf(JLayeredPane.POPUP_LAYER + 1));
            layers.revalidate();
            layers.repaint();
        }

        /** Draws a page: header line, dark list, then Help, a divider, the choices and what closes the window. */
        JPanel render(Page p) {
            Color tone = color(p.tone());
            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(WINDOW);

            // Header: the light (red, amber or green), "Check Engine: ...", code and date small and grey at the right
            // Help's pages change nothing about the pack: the light stays as it was
            boolean helpPage = "WHAT IS THIS SCREEN?".equals(p.headline()) || "COPIED FOR HELP".equals(p.headline());
            int light = helpPage ? lastLight
                    : p.tone() == Tone.RED ? EngineIcon.RED : p.tone() == Tone.AMBER ? EngineIcon.AMBER : EngineIcon.GREEN;
            lastLight = light;
            JPanel header = new JPanel(new BorderLayout(px(12), 0));
            header.setOpaque(false);
            header.setBorder(new CompoundBorder(rule(0, 0, 1, 0), pad(9, 12, 9, 12)));
            JPanel title = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            title.setOpaque(false);
            title.add(new EngineLight(light));
            title.add(label("Check Engine: ", PLAIN_FONT, TEXT));
            title.add(label(UiText.afterName(p.headline()), PLAIN_FONT, tone));
            header.add(title, BorderLayout.WEST);
            String lastWorked = p.subline() != null && p.subline().startsWith("Last worked") ? p.subline() : null;
            String meta = p.code() != null ? p.code() + " · " + UiText.now() : lastWorked;
            if (meta != null) header.add(label(meta, PLAIN_FONT, DIM), BorderLayout.EAST);
            root.add(header, BorderLayout.NORTH);

            JPanel body = column();
            body.setOpaque(true);
            body.setBackground(LIST);
            body.setBorder(pad(10, 16, 10, 16));
            if (p.subline() != null && lastWorked == null) {
                body.add(label(p.subline(), PLAIN_FONT, DIM));
                body.add(Box.createVerticalStrut(px(8)));
            }
            for (int i = 0; i < p.sections().size(); i++) {
                if (i > 0) body.add(Box.createVerticalStrut(px(10)));
                body.add(section(p.sections().get(i)));
            }
            root.add(body, BorderLayout.CENTER);

            JPanel bottom = column();
            bottom.setBorder(rule(1, 0, 0, 0));
            if (p.help() != null) {
                JLabel help = label(p.help(), PLAIN_FONT, DIM);
                help.setBorder(pad(8, 16, 0, 16));
                bottom.add(help);
            }
            // Help, a divider, the main button first and outlined, the other choices; what closes the window far right
            JPanel buttons = new JPanel();
            buttons.setOpaque(false);
            buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
            buttons.setBorder(pad(8, 10, 8, 10));
            buttons.setAlignmentX(0f);
            McButton[] help = new McButton[1];
            help[0] = new McButton(new Action("Help", Tone.GREY, () -> openHelp(p, root, help[0])), false);
            buttons.add(help[0]);
            buttons.add(Box.createHorizontalStrut(px(8)));
            buttons.add(new Divider());
            buttons.add(Box.createHorizontalStrut(px(8)));
            boolean first = true;
            for (Action a : p.actions()) {
                if (UiText.closes(a.label())) continue;
                if (!first) buttons.add(Box.createHorizontalStrut(px(6)));
                // Load anyway is a choice like any other, never the one to press
                buttons.add(new McButton(a, first && !"Load anyway".equals(a.label())));
                first = false;
            }
            buttons.add(Box.createHorizontalGlue());
            for (Action a : p.actions()) {
                if (!UiText.closes(a.label())) continue;
                buttons.add(Box.createHorizontalStrut(px(6)));
                buttons.add(new McButton(a, false));
            }
            bottom.add(buttons);
            root.add(bottom, BorderLayout.SOUTH);
            root.setPreferredSize(new Dimension(Math.max(px(WIDTH), root.getPreferredSize().width), root.getPreferredSize().height));
            root.addHierarchyListener(e -> {
                if (root.isShowing()) CURRENT.set(root);
            });
            return root;
        }
    }

    // ---- pieces of the frame ----

    /** The engine light from the same texture as the game, its halo included, at twice the size. */
    private static final class EngineLight extends JComponent {
        private final int cell;

        EngineLight(int cell) {
            this.cell = cell;
            setPreferredSize(new Dimension((EngineIcon.WIDTH + 2 * EngineIcon.MARGIN) * px(2),
                    (EngineIcon.HEIGHT + 2 * EngineIcon.MARGIN) * px(2)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Image all = image("/checkengine_engine.png");
            if (all == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            int top = cell * EngineIcon.CELL_H;
            g2.drawImage(all, 0, 0, getWidth(), getHeight(), 0, top, EngineIcon.CELL_W, top + EngineIcon.CELL_H, null);
            g2.dispose();
        }
    }

    /** The thin line between Help and the choices. */
    private static final class Divider extends JComponent {
        Divider() {
            setPreferredSize(new Dimension(1, px(24)));
            setMaximumSize(new Dimension(1, Integer.MAX_VALUE));
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(RULE);
            g.fillRect(0, 0, 1, getHeight());
        }
    }

    /** One block of the list: "! What's wrong" in its colour, then the lines. */
    private static JComponent section(Section s) {
        Color tone = color(s.tone());
        JPanel p = column();
        String mark = switch (s.icon()) {
            case ALERT -> "! ";
            case CHANGED -> "± ";
            case CHECK -> TICK;
            case INFO -> "i ";
            case NEXT -> "» ";
        };
        p.add(label(mark + UiText.sentence(s.title()), PLAIN_FONT, tone));
        JPanel lines = column();
        for (Line l : s.lines()) {
            Font f = l.style() == Style.STRONG ? BOLD_FONT : PLAIN_FONT;
            // long sentences wrap instead of stretching the window
            for (String part : wrap(l.text(), f, px(MAX_LINE))) lines.add(label(part, f, l.style() == Style.DIM ? DIM : TEXT));
        }
        p.add(lines.getComponentCount() > VISIBLE_LINES ? scroll(lines) : lines);
        return p;
    }

    /** A Minecraft-style button. The main one has a white outline. */
    private static final class McButton extends JButton {
        private final boolean main;

        McButton(Action a, boolean main) {
            super(a.label());
            this.main = main;
            setFont(PLAIN_FONT);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setBorder(pad(6, 12, 6, 12));
            setMaximumSize(getPreferredSize());
            addActionListener(e -> a.run().run());
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth(), h = getHeight();
            boolean hover = getModel().isRollover();
            g2.setColor(main ? Color.WHITE : Color.BLACK);
            g2.fillRect(0, 0, w, h);
            int p = px(1);
            g2.setPaint(new GradientPaint(0, p, hover ? new Color(0x8C8C9E) : new Color(0x7A7A7A), 0, h - p,
                    hover ? new Color(0x72728A) : new Color(0x646464)));
            g2.fillRect(p, p, w - 2 * p, h - 2 * p);
            g2.setColor(hover ? new Color(0xB8B8D0) : new Color(0xA8A8A8));
            g2.fillRect(p, p, w - 2 * p, p);
            g2.fillRect(p, p, p, h - 2 * p);
            g2.setColor(new Color(0x4A4A4A));
            g2.fillRect(p, h - 3 * p, w - 2 * p, 2 * p);
            g2.fillRect(w - 2 * p, p, p, h - 2 * p);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int x = (w - fm.stringWidth(getText())) / 2, y = (h - 2 * p - fm.getHeight()) / 2 + fm.getAscent();
            drawText(g2, getText(), x, y, hover ? new Color(0xFFFFA0) : Color.WHITE);
            g2.dispose();
        }
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

    private static Image image(String resource) {
        try (java.io.InputStream in = Popup.class.getResourceAsStream(resource)) {
            return in == null ? null : ImageIO.read(in);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Help > What is this screen?: plain words, from inside the jar. */
    private static Page explain(Page p, JPanel back) {
        List<Line> lines = ScreenHelp.explain(ScreenHelp.place(p.headline()), null, p.code()).stream().map(Line::text).toList();
        return new Page(Tone.BLUE, "WHAT IS THIS SCREEN?", null, p.code(), null,
                List.of(new Section("IN PLAIN WORDS", Tone.BLUE, Icon.INFO, lines)),
                List.of(new Action("Cancel", Tone.GREY, () -> swapTo(back))), null, null);
    }

    /** Help > Copy for help: what this page says and the versions, on the clipboard; shows what was copied. */
    private static Page copied(Page p, JPanel back) {
        List<String> text = new ArrayList<>();
        for (Section s : p.sections()) {
            text.add(UiText.sentence(s.title()));
            s.lines().forEach(l -> text.add(l.text()));
        }
        String message = CrashHelp.forScreen(UiText.afterName(p.headline()), p.code(), text, null, null, null);
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(message), null);
        return new Page(Tone.BLUE, "COPIED FOR HELP", null, p.code(), null, List.of(
                new Section("PASTE IT IN YOUR HELP CHANNEL", Tone.BLUE, Icon.INFO, List.of(
                        Line.text("Press Ctrl+V in your pack's Discord or in a GitHub issue."),
                        Line.dim("Your user folder and IP are blanked out. Nothing was uploaded."))),
                new Section("WHAT WAS COPIED", Tone.GREY, Icon.INFO,
                        java.util.Arrays.stream(message.split("\n")).map(Line::dim).toList())),
                List.of(new Action("Cancel", Tone.GREY, () -> swapTo(back))), null, null);
    }

    /** Back to a page that's already drawn. */
    private static void swapTo(JPanel page) {
        if (javax.swing.SwingUtilities.getWindowAncestor(CURRENT.get()) instanceof JDialog d) {
            d.setContentPane(page);
            d.pack();
            d.repaint();
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
        sp.setPreferredSize(new Dimension(list.getPreferredSize().width + px(14), row * (VISIBLE_LINES + 1) + row / 2));
        JScrollBar bar = sp.getVerticalScrollBar();
        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(px(8), 0));
        bar.setUnitIncrement(row);
        bar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = new Color(0x5E6168);
                trackColor = LIST;
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
    private static final int MAX_LINE = 600;

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

    /** A line of text with Minecraft-style drop shadow. */
    private static JLabel label(String text, Font f, Color c) {
        boolean tick = text.startsWith(TICK);
        String words = (tick ? text.substring(TICK.length()) : text).replace("→", "-");
        JLabel l = new JLabel(tick ? "++ " + words : text) { // the same width as the mark and its space
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int y = getInsets().top + fm.getAscent(), x = tick ? px(16) : 0;
                drawText(g2, words, x, y, getForeground());
                g2.setColor(getForeground());
                if (tick) {
                    int top = y - px(11);
                    for (int r = 0; r < TICK_SHAPE.length; r++) {
                        for (int k = 0; k < TICK_SHAPE[r].length(); k++) {
                            if (TICK_SHAPE[r].charAt(k) == '#') g2.fillRect(k * px(2), top + r * px(2), px(2), px(2));
                        }
                    }
                }
                g2.dispose();
            }
        };
        l.setFont(f.canDisplayUpTo(words) == -1 ? f : new Font(Font.SANS_SERIF, f.getStyle(), px(14)));
        l.setForeground(c);
        l.setAlignmentX(0f);
        l.setBorder(pad(1, 0, 3, 2));
        return l;
    }

    /** The bundled pixel font (Pixel Operator, CC0), or the system font if it cannot be read. */
    private static Font load(String resource, int style) {
        try (java.io.InputStream in = Popup.class.getResourceAsStream(resource)) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont((float) TEXT_SIZE);
        } catch (Exception e) {
            // the system font below still reads fine
        }
        return new Font(Font.SANS_SERIF, style, 14);
    }
}
