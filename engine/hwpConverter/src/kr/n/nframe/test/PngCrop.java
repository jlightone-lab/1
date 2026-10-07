package kr.n.nframe.test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class PngCrop {
    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));
        int y = Integer.parseInt(args[1]);
        int h = Integer.parseInt(args[2]);
        BufferedImage out = src.getSubimage(0, y, src.getWidth(), Math.min(h, src.getHeight() - y));
        ImageIO.write(out, "PNG", new File(args[3]));
        // 색상 분석
        int blue=0, white=0, other=0;
        for (int yy = 0; yy < out.getHeight(); yy += 10) {
            for (int xx = 0; xx < out.getWidth(); xx += 10) {
                int rgb = out.getRGB(xx, yy);
                int r = (rgb>>16)&0xFF, gn = (rgb>>8)&0xFF, b = rgb&0xFF;
                if (b > 200 && r < 50 && gn < 50) blue++;
                else if (r > 240 && gn > 240 && b > 240) white++;
                else other++;
            }
        }
        System.out.println("blue=" + blue + " white=" + white + " other=" + other);
    }
}
