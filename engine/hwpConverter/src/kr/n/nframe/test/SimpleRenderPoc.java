package kr.n.nframe.test;

import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

public class SimpleRenderPoc {
    public static void main(String[] args) throws Exception {
        // 매우 단순한 HTML
        String html = "<html><head><style>body{font-family:sans-serif;background:#fff;color:#000;padding:10px;}h1{color:#000}</style></head><body>"
                + "<h1>Hello World</h1>"
                + "<p>This is a test paragraph with some content.</p>"
                + "<table border='1'><tr><td>cell1</td><td>cell2</td></tr></table>"
                + "</body></html>";

        // 1) printAll
        try {
            BufferedImage img = render(html, 800, 600, false);
            ImageIO.write(img, "PNG", new File("build/v1411/poc/simple_paint.png"));
            System.out.println("simple_paint.png");
        } catch (Exception e) { e.printStackTrace(); }

        // 2) different approach
        try {
            BufferedImage img = render(html, 800, 600, true);
            ImageIO.write(img, "PNG", new File("build/v1411/poc/simple_print.png"));
            System.out.println("simple_print.png");
        } catch (Exception e) { e.printStackTrace(); }

        // 3) pure approach
        JEditorPane pane = new JEditorPane();
        pane.setContentType("text/html");
        pane.setEditorKit(new HTMLEditorKit());
        pane.setText(html);
        pane.setSize(800, 600);
        pane.validate();
        System.out.println("bg=" + pane.getBackground() + " fg=" + pane.getForeground());
        System.out.println("preferred=" + pane.getPreferredSize());
    }

    static BufferedImage render(String html, int w, int h, boolean useFrame) {
        JEditorPane pane = new JEditorPane();
        pane.setContentType("text/html");
        pane.setEditorKit(new HTMLEditorKit());
        pane.setText(html);
        pane.setOpaque(true);
        pane.setBackground(Color.WHITE);
        pane.setForeground(Color.BLACK);
        pane.setEditable(false);
        pane.setSize(w, h);
        if (useFrame) {
            JFrame f = new JFrame();
            f.setUndecorated(true);
            f.add(pane);
            f.pack();
            pane.setSize(w, h);
            f.setSize(w, h);
        }
        pane.validate();
        pane.doLayout();

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        if (useFrame) pane.printAll(g);
        else pane.paint(g);
        g.dispose();
        return img;
    }
}
