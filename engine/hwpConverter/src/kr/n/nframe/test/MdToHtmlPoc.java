package kr.n.nframe.test;

import javax.swing.JEditorPane;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.HTMLDocument;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.imageio.ImageIO;

/**
 * v14.11 PoC: MD → HTML → PNG 변환을 Java SE Swing 으로 검증.
 * 외부 의존성 없이 표준 라이브러리만 사용.
 */
public class MdToHtmlPoc {
    public static void main(String[] args) throws Exception {
        String md = args.length > 0 ? args[0] : "hwp/0427/dummy_file/05.단건_hwp_md/case1.md";
        String outPng = args.length > 1 ? args[1] : "build/v1411/poc/case1.png";

        Files.createDirectories(Paths.get(outPng).toAbsolutePath().getParent());

        String mdText = new String(Files.readAllBytes(Paths.get(md)), StandardCharsets.UTF_8);
        // 매우 단순 MD→HTML: 헤딩, 단락, 표 (HTML 표 그대로), 이미지 참조
        String html = mdToBasicHtml(mdText, Paths.get(md).toAbsolutePath().getParent());

        // 디버그용: HTML 저장
        Path htmlPath = Paths.get(outPng).resolveSibling("case1_debug.html");
        Files.write(htmlPath, html.getBytes(StandardCharsets.UTF_8));
        System.out.println("[debug] HTML 저장: " + htmlPath + " (" + html.length() + " chars)");

        // HTML → BufferedImage
        BufferedImage img = renderHtmlToImage(html, 900);
        System.out.println("[ok] image size: " + img.getWidth() + "×" + img.getHeight());

        ImageIO.write(img, "PNG", new File(outPng));
        System.out.println("[ok] PNG 저장: " + outPng);
    }

    static String mdToBasicHtml(String md, Path parentDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><style>")
          .append("body{font-family:Malgun Gothic,Dotum,sans-serif;font-size:14px;padding:20px;color:#222;background:#fff;line-height:1.55;}")
          .append("h1{font-size:26px;border-bottom:2px solid #ccc;padding-bottom:6px;margin-top:20px;font-weight:bold;}")
          .append("h2{font-size:22px;border-bottom:1px solid #ccc;padding-bottom:4px;margin-top:18px;font-weight:bold;}")
          .append("h3{font-size:18px;margin-top:16px;font-weight:bold;}")
          .append("h4{font-size:16px;margin-top:14px;font-weight:bold;}")
          .append("table{border-collapse:collapse;margin:8px 0;}")
          .append("td,th{border:1px solid #888;padding:5px 8px;vertical-align:top;}")
          .append("th{background:#f0f0f0;font-weight:bold;}")
          .append("img{max-width:600px;}")
          .append("ul,ol{margin:4px 0;padding-left:24px;}")
          .append("p{margin:4px 0;}")
          .append("</style></head><body>");
        // 멀티라인 image 마크다운 → <img>
        md = md.replaceAll("(?s)!\\[([^\\]]*?)\\]\\(([^)\\s]+(?:\\s[^)]*)?)\\)",
                "<img src=\"$2\" alt=\"$1\"/>");
        // 단락 처리 (간단)
        String[] lines = md.split("\\r?\\n", -1);
        boolean inHtmlBlock = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trim = line.trim();
            // HTML 블록 (<table> 등) 그대로
            if (trim.toLowerCase().startsWith("<table") || trim.toLowerCase().startsWith("<tbody")
                    || trim.toLowerCase().startsWith("<tr") || trim.toLowerCase().startsWith("<td")
                    || trim.toLowerCase().startsWith("<th") || trim.toLowerCase().startsWith("</table")
                    || trim.toLowerCase().startsWith("</tbody") || trim.toLowerCase().startsWith("</tr")
                    || trim.toLowerCase().startsWith("</td") || trim.toLowerCase().startsWith("</th")) {
                sb.append(line).append('\n');
                continue;
            }
            if (trim.isEmpty()) {
                sb.append("<br/>\n");
                continue;
            }
            // ATX heading
            if (trim.matches("^#{1,6}\\s+.+")) {
                int level = 0;
                while (level < trim.length() && trim.charAt(level) == '#') level++;
                String t = trim.substring(level).trim();
                sb.append("<h").append(Math.min(level, 6)).append(">").append(escape(t))
                        .append("</h").append(Math.min(level, 6)).append(">\n");
                continue;
            }
            // 이미지 라인
            if (trim.startsWith("<img")) {
                sb.append(trim).append('\n');
                continue;
            }
            // 그 외 plain
            sb.append("<p>").append(escape(trim)).append("</p>\n");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    static BufferedImage renderHtmlToImage(String html, int width) throws Exception {
        JEditorPane pane = new JEditorPane();
        HTMLEditorKit kit = new HTMLEditorKit();
        pane.setEditorKit(kit);
        pane.setText(html);
        pane.setSize(width, Integer.MAX_VALUE);
        pane.validate();
        Dimension preferred = pane.getPreferredSize();
        int height = Math.max(800, preferred.height);
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        pane.paint(g);
        g.dispose();
        return img;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
