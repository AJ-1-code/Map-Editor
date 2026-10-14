import java.awt.Color;
import java.awt.image.BufferedImage;

public class Tile {

    public BufferedImage subImage;
    public int id;
    public Color avg;

    public Tile(BufferedImage subImage, int id) {
        this.subImage = subImage;
        this.id = id;
        this.avg = computeAverage(subImage);
    }

    public static BufferedImage getTileFromFile(BufferedImage full, int size, int col, int row) {
        return full.getSubimage(col * size, row * size, size, size);
    }

    private static Color computeAverage(BufferedImage img) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                if (((argb >> 24) & 0xFF) < 40) continue;
                r += (argb >> 16) & 0xFF;
                g += (argb >> 8) & 0xFF;
                b += argb & 0xFF;
                n++;
            }
        }
        if (n == 0) return null;
        return new Color((int) (r / n), (int) (g / n), (int) (b / n));
    }
}