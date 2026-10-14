import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Draws the Check Engine lamp (like the window's Bezel) for each colour, lit and unlit, into one strip. */
public class LampAtlas {
    public static void main(String[] a) throws Exception {
        BufferedImage logo = ImageIO.read(new File(a[0]));
        Color[] tones = {new Color(0xFF4B3E), new Color(0xFF9A1F), new Color(0x3DDC4A), new Color(0x3B82F6),
                new Color(0x9A9CA3)};
        int s = 128;
        BufferedImage out = new BufferedImage(s * tones.length * 2, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        for (int t = 0; t < tones.length; t++) {
            for (int on = 0; on < 2; on++) {
                Graphics2D c = (Graphics2D) g.create();
                c.translate((t * 2 + on) * s, 0);
                lamp(c, s, tones[t], on == 0, logo);
                c.dispose();
            }
        }
        g.dispose();
        for (String f : a[1].split(",")) ImageIO.write(out, "png", new File(f));
    }

    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /** Same shapes as Popup.Bezel (designed at 92px), scaled to {@code s}. */
    static void lamp(Graphics2D g2, int s, Color tone, boolean lit, BufferedImage logo) {
        float k = s / 92f, c = s / 2f;
        if (lit) {
            g2.setPaint(new RadialGradientPaint(c, c, c, new float[] {0.7f, 1f},
                    new Color[] {alpha(tone, 110), alpha(tone, 0)}));
            g2.fill(new Ellipse2D.Float(0, 0, 2 * c, 2 * c));
        }
        float r = 37 * k;
        g2.setPaint(new GradientPaint(c - r, c - r, new Color(0x7A7D84), c + r, c + r, new Color(0x222428)));
        g2.fill(new Ellipse2D.Float(c - r, c - r, 2 * r, 2 * r));
        g2.setPaint(new GradientPaint(c - r, c + r, new Color(0x5C5F66), c + r, c - r, new Color(0x2A2C30)));
        g2.fill(new Ellipse2D.Float(c - r + 4 * k, c - r + 4 * k, 2 * r - 8 * k, 2 * r - 8 * k));
        float l = 30 * k;
        g2.setColor(new Color(0x0D0D0F));
        g2.fill(new Ellipse2D.Float(c - l, c - l, 2 * l, 2 * l));
        g2.setPaint(new RadialGradientPaint(c, c, l, new float[] {0.6f, 1f},
                new Color[] {alpha(tone, 0), alpha(tone, lit ? 150 : 50)}));
        g2.fill(new Ellipse2D.Float(c - l, c - l, 2 * l, 2 * l));
        g2.setColor(lit ? tone : tone.darker().darker());
        g2.setStroke(new BasicStroke(2.6f * k));
        g2.draw(new Ellipse2D.Float(c - l, c - l, 2 * l, 2 * l));
        int inner = Math.round(48 * k);
        BufferedImage img = new BufferedImage(inner, inner, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gi = img.createGraphics();
        gi.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gi.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        gi.fill(new Ellipse2D.Float(0, 0, inner, inner));
        gi.setComposite(AlphaComposite.SrcIn);
        int zoom = inner / 8;
        gi.drawImage(logo, -zoom, -zoom, inner + 2 * zoom, inner + 2 * zoom, null);
        gi.dispose();
        g2.drawImage(img, Math.round(c - inner / 2f), Math.round(c - inner / 2f), null);
    }
}
