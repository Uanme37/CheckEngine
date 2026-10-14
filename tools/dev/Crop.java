import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Crops a screenshot: java Crop.java in.png out.png x y w h */
public class Crop {
    public static void main(String[] a) throws Exception {
        BufferedImage in = ImageIO.read(new File(a[0]));
        int x = Integer.parseInt(a[2]), y = Integer.parseInt(a[3]), w = Integer.parseInt(a[4]), h = Integer.parseInt(a[5]);
        ImageIO.write(in.getSubimage(x, y, Math.min(w, in.getWidth() - x), Math.min(h, in.getHeight() - y)), "png",
                new File(a[1]));
    }
}
