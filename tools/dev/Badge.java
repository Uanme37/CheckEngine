import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** "Works with Check Engine" badges: A pill and B seal, from the real lamp and UAN mark. Args: lamp.png uan.png outDir */
public class Badge {
    static final Color BG = new Color(0x0F172A), GREEN = new Color(0x22C55E), TEXT = new Color(0xF1F5F9),
            MUTED = new Color(0x94A3B8);
    static BufferedImage lamp, uan;

    static Font font(String name, int style, float size) {
        Font f = new Font(name, style, Math.round(size));
        return f.getFamily().equals(Font.DIALOG) ? new Font("Bahnschrift", style, Math.round(size)) : f;
    }

    static Graphics2D g(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    /** A: 440x88 pill (shown at half size: 220x44). */
    static BufferedImage pill() {
        int w = 440, h = 88;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        g.setColor(BG);
        g.fill(new RoundRectangle2D.Float(2, 2, w - 4, h - 4, h - 4, h - 4));
        g.setColor(GREEN);
        g.setStroke(new BasicStroke(4));
        g.draw(new RoundRectangle2D.Float(2, 2, w - 4, h - 4, h - 4, h - 4));
        g.drawImage(lamp, 4, 4, 80, 80, null);
        g.setColor(MUTED);
        g.setFont(font("Bahnschrift", Font.PLAIN, 19));
        g.drawString("WORKS WITH", 94, 38);
        g.setColor(TEXT);
        g.setFont(font("Bahnschrift", Font.BOLD, 30));
        g.drawString("CHECK ENGINE", 92, 70);
        g.setColor(new Color(0x334155));
        g.fillRect(338, 22, 2, 44);
        g.drawImage(uan, 354, 18, 52, 52, null);
        g.dispose();
        return img;
    }

    /** B: 512x512 seal, the UAN icon frame (outline, top stripe) with the lamp and the UAN mark. */
    static BufferedImage seal() {
        int s = 512;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = g(img);
        g.setColor(BG);
        g.fill(new RoundRectangle2D.Float(12, 12, s - 24, s - 24, 192, 192));
        g.setColor(GREEN);
        g.setStroke(new BasicStroke(24));
        g.draw(new RoundRectangle2D.Float(12, 12, s - 24, s - 24, 192, 192));
        g.fillRoundRect(146, 59, 220, 18, 18, 18); // the brand's top stripe
        g.setColor(MUTED);
        g.setFont(font("Bahnschrift", Font.BOLD, 34));
        center(g, "WORKS WITH", s, 128);
        g.drawImage(lamp, 146, 140, 220, 220, null);
        g.setColor(TEXT);
        g.setFont(font("Bahnschrift", Font.BOLD, 50));
        center(g, "CHECK ENGINE", s, 404);
        g.drawImage(uan, 226, 420, 60, 60, null);
        g.dispose();
        return img;
    }

    static void center(Graphics2D g, String t, int w, int y) {
        g.drawString(t, (w - g.getFontMetrics().stringWidth(t)) / 2, y);
    }

    public static void main(String[] a) throws Exception {
        BufferedImage atlas = ImageIO.read(new File(a[0]));
        lamp = atlas.getSubimage(2 * 2 * 128, 0, 128, 128); // green, lit
        uan = ImageIO.read(new File(a[1]));
        BufferedImage p = pill(), s = seal();
        ImageIO.write(p, "png", new File(a[2], "badge-works-with.png"));
        ImageIO.write(s, "png", new File(a[2], "badge-works-with-seal.png"));

        // Preview: where they go. Left: a pack's description with A. Right: the guide header with B.
        BufferedImage page = new BufferedImage(1000, 470, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = g(page);
        g.setColor(new Color(0x16181C));
        g.fillRect(0, 0, 1000, 470);
        g.setColor(new Color(0x23262B));
        g.fillRoundRect(20, 20, 470, 430, 16, 16);
        g.fillRoundRect(510, 20, 470, 430, 16, 16);
        g.setColor(MUTED);
        g.setFont(font("Bahnschrift", Font.PLAIN, 15));
        g.drawString("A. On a pack's CurseForge page (description)", 40, 50);
        g.drawString("B. Pack maker guide header + Check Engine's page", 530, 50);
        g.setColor(TEXT);
        g.setFont(font("Bahnschrift", Font.BOLD, 26));
        g.drawString("Beyond the Crank", 40, 100);
        g.setColor(MUTED);
        g.setFont(font("Bahnschrift", Font.PLAIN, 16));
        g.drawString("A Create tech pack: 390 mods, quests and a story.", 40, 128);
        g.drawImage(p, 40, 150, 220, 44, null);
        g.drawString("If the pack won't start or crashes, Check Engine", 40, 230);
        g.drawString("says why and how to fix it.", 40, 252);
        g.setColor(new Color(0x2F3338));
        for (int i = 0; i < 4; i++) g.fillRoundRect(40, 290 + i * 34, 410 - i * 60, 14, 7, 7);
        g.drawImage(s, 535, 75, 150, 150, null);
        g.setColor(TEXT);
        g.setFont(font("Bahnschrift", Font.BOLD, 24));
        g.drawString("Make your pack", 705, 130);
        g.drawString("Check Engine ready", 705, 160);
        g.setColor(MUTED);
        g.setFont(font("Bahnschrift", Font.PLAIN, 16));
        g.drawString("2 minutes, 5 lines in one config file.", 705, 190);
        g.setColor(new Color(0x2F3338));
        for (int i = 0; i < 5; i++) g.fillRoundRect(535, 260 + i * 34, 410 - (i % 2) * 90, 14, 7, 7);
        g.dispose();
        ImageIO.write(page, "png", new File(a[2], "badge-preview.png"));
    }
}
