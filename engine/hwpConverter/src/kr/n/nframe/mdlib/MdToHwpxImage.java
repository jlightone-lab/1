package kr.n.nframe.mdlib;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;
import javax.swing.JEditorPane;
import javax.swing.text.html.HTMLEditorKit;

/**
 * v14.11: MD → HTML → PNG → HWPX (이미지 임베드).
 *
 * <p>VSCode preview 와 시각적으로 동일한 결과물을 만들기 위해 기존
 * 텍스트 변환 방식이 아닌 다른 접근을 사용한다.
 *
 * <pre>
 *   MD → HTML (CSS 스타일) → Swing HTMLEditorKit 렌더링 →
 *   BufferedImage → A4 페이지 단위 PNG split → HWPX 에 BinData 로 임베드
 * </pre>
 *
 * <p>기존 hwpx-template (case1_50p 추출본) 의 mimetype/settings/content.hpf
 * /header.xml 을 그대로 활용하고, 우리는 binDataList(이미지 등록) +
 * section0.xml(픽처 컨트롤) 만 동적 생성. 이렇게 하면 hwpxlib/한/글이
 * 수용하는 정확한 XML 형식을 보장.
 */
public class MdToHwpxImage {

    private static final int PAGE_W_PX = 1100;
    private static final int PAGE_H_PX = 1500;

    private static final int A4_W_HU = 59528;
    private static final int A4_H_HU = 84188;
    private static final int BODY_W_HU = 41954;
    private static final int BODY_H_HU = 65488;

    public void convert(String mdPath, String outHwpx) throws Exception {
        System.out.println("[MdToHwpxImage] 시작 (MD → HTML → PNG → HWPX 이미지 임베드, 품질 개선)");
        System.out.println("[MdToHwpxImage] Input : " + mdPath);
        System.out.println("[MdToHwpxImage] Output: " + outHwpx);

        String md = new String(Files.readAllBytes(Paths.get(mdPath)), StandardCharsets.UTF_8);
        Path mdParent = Paths.get(mdPath).toAbsolutePath().getParent();

        String html = mdToHtml(md, mdParent);
        System.out.println("[MdToHwpxImage] HTML 생성: " + html.length() + " chars");

        BufferedImage fullImg = renderHtmlToImage(html, PAGE_W_PX, mdParent);
        System.out.println("[MdToHwpxImage] 렌더 이미지: " + fullImg.getWidth() + "×" + fullImg.getHeight());

        // 디버그용 dump
        Path outP = Paths.get(outHwpx).toAbsolutePath();
        Path dbgPng = outP.resolveSibling("dbg_full.png");
        Path dbgHtml = outP.resolveSibling("dbg.html");
        ImageIO.write(fullImg, "PNG", dbgPng.toFile());
        Files.write(dbgHtml, html.getBytes(StandardCharsets.UTF_8));
        System.out.println("[debug] full PNG: " + dbgPng + " (" + Files.size(dbgPng) + " bytes)");

        List<byte[]> pagePngs = splitToPages(fullImg);
        System.out.println("[MdToHwpxImage] 페이지 분할: " + pagePngs.size() + "개");

        writeHwpxZip(outHwpx, pagePngs);
        System.out.println("[MdToHwpxImage] HWPX 저장 완료: " + outHwpx
                + " (" + new java.io.File(outHwpx).length() + " bytes)");
    }

    // ============================================================
    //  MD → HTML
    // ============================================================

    private String mdToHtml(String md, Path parentDir) {
        StringBuilder sb = new StringBuilder(md.length() * 2);
        sb.append("<html><head><style>")
          .append("body{font-family:'Malgun Gothic','Dotum',sans-serif;font-size:16px;color:#222;background:#fff;line-height:1.6;padding:18px 24px;}")
          .append("h1{font-size:32px;border-bottom:2px solid #ccc;padding-bottom:8px;margin:22px 0 12px;font-weight:bold;}")
          .append("h2{font-size:25px;border-bottom:1px solid #ddd;padding-bottom:5px;margin:18px 0 10px;font-weight:bold;}")
          .append("h3{font-size:20px;margin:16px 0 8px;font-weight:bold;}")
          .append("h4{font-size:17px;margin:14px 0 6px;font-weight:bold;}")
          .append("h5,h6{font-size:16px;margin:12px 0 4px;font-weight:bold;}")
          .append("table{border-collapse:collapse;margin:10px 0;}")
          .append("td,th{border:1px solid #888;padding:6px 10px;vertical-align:top;}")
          .append("th{background:#f0f0f0;font-weight:bold;}")
          .append("img{max-width:700px;}")
          .append("ul,ol{margin:5px 0;padding-left:28px;}")
          .append("li{margin:3px 0;}")
          .append("p{margin:6px 0;}")
          .append("strong,b{font-weight:bold;}")
          .append("em,i{font-style:italic;}")
          .append("code{font-family:Consolas,monospace;background:#f5f5f5;padding:0 4px;}")
          .append("blockquote{border-left:3px solid #aaa;padding-left:12px;color:#555;margin:8px 0;}")
          .append("</style></head><body>");

        // 멀티라인 이미지 마크다운 → <img>
        md = md.replaceAll("(?s)!\\[([^\\]]*?)\\]\\(([^)\\s]+(?:\\s[^)]*)?)\\)",
                "<img src=\"$2\"/>");

        String[] lines = md.split("\\r?\\n", -1);
        boolean inCodeBlock = false;
        boolean inHtmlBlock = false; // <table> 등 multiline HTML 블록 안인지
        StringBuilder codeBuf = new StringBuilder();
        for (String line : lines) {
            String trim = line.trim();
            String tLow = trim.toLowerCase();

            if (inCodeBlock) {
                if (trim.startsWith("```")) {
                    sb.append("<pre><code>").append(escape(codeBuf.toString())).append("</code></pre>\n");
                    codeBuf.setLength(0);
                    inCodeBlock = false;
                } else {
                    codeBuf.append(line).append('\n');
                }
                continue;
            }
            if (trim.startsWith("```")) {
                inCodeBlock = true;
                continue;
            }

            // HTML 블록 내부면 그대로 출력
            if (inHtmlBlock) {
                sb.append(line).append('\n');
                if (tLow.contains("</table>")) inHtmlBlock = false;
                continue;
            }
            // HTML 블록 시작
            if (tLow.startsWith("<table")) {
                inHtmlBlock = true;
                sb.append(line).append('\n');
                if (tLow.contains("</table>")) inHtmlBlock = false;
                continue;
            }
            // 단독 HTML 라인 (table 외 다른 요소)
            if (tLow.matches("^</?(tbody|thead|tr|td|th|ul|ol|li|div|p|h[1-6]|br|hr|img)\\b.*")) {
                sb.append(line).append('\n');
                continue;
            }
            if (trim.startsWith("<img")) {
                sb.append(trim).append('\n');
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
                int lv = Math.min(level, 6);
                sb.append("<h").append(lv).append(">")
                  .append(processMdContent(t))
                  .append("</h").append(lv).append(">\n");
                continue;
            }
            // 리스트 (단순)
            if (trim.matches("^[-*+]\\s+.+")) {
                String t = trim.substring(1).trim();
                sb.append("<div style='margin-left:24px'>• ")
                  .append(processMdContent(t)).append("</div>\n");
                continue;
            }
            // 들여쓰기 리스트
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            if (indent >= 2 && line.substring(indent).matches("[-*+]\\s+.+")) {
                String t = line.substring(indent + 1).trim();
                sb.append("<div style='margin-left:").append(24 + indent * 8).append("px'>◦ ")
                  .append(processMdContent(t)).append("</div>\n");
                continue;
            }
            sb.append("<p>").append(processMdContent(trim)).append("</p>\n");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * MD 컨텐츠를 HTML 로 변환 — 인라인 HTML 태그 (<strong>, <em>, <br>, <u>, <s>, <sub>, <sup>)
     * 는 그대로 보존하고, 나머지 plain 문자만 escape, 이후 markdown markers (*** ~~ ` 등) 처리.
     */
    private String processMdContent(String s) {
        if (s == null || s.isEmpty()) return "";
        // 1) 알려진 인라인 HTML 태그를 placeholder 로 추출 (escape 영향 받지 않게)
        java.util.List<String> tags = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "</?(?:strong|em|b|i|u|s|br|sub|sup|span|small|big|mark|del|ins|code|kbd)\\b[^>]*/?>",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            tags.add(m.group());
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("ZzPlace" + (tags.size() - 1) + "ZzEnd"));
        }
        m.appendTail(sb);
        String text = sb.toString();
        // 2) escape
        text = escape(text);
        // 3) markdown inline markers
        text = text.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
        text = text.replaceAll("__([^_]+)__", "<b>$1</b>");
        text = text.replaceAll("(?<!\\*)\\*(?!\\*)([^*]+)(?<!\\*)\\*(?!\\*)", "<i>$1</i>");
        text = text.replaceAll("(?<!_)_(?!_)([^_]+)(?<!_)_(?!_)", "<i>$1</i>");
        text = text.replaceAll("~~([^~]+)~~", "<s>$1</s>");
        text = text.replaceAll("`([^`]+)`", "<code>$1</code>");
        // 링크 [text](url) — 텍스트만 노출
        text = text.replaceAll("\\[([^\\]]*)\\]\\(([^\\)]*)\\)", "$1");
        // 4) placeholder 복원
        for (int i = 0; i < tags.size(); i++) {
            text = text.replace("ZzPlace" + i + "ZzEnd", tags.get(i));
        }
        return text;
    }

    private String processInline(String s) {
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
        s = s.replaceAll("__([^_]+)__", "<b>$1</b>");
        s = s.replaceAll("(?<!\\*)\\*(?!\\*)([^*]+)(?<!\\*)\\*(?!\\*)", "<i>$1</i>");
        s = s.replaceAll("(?<!_)_(?!_)([^_]+)(?<!_)_(?!_)", "<i>$1</i>");
        s = s.replaceAll("~~([^~]+)~~", "<s>$1</s>");
        s = s.replaceAll("`([^`]+)`", "<code>$1</code>");
        s = s.replaceAll("\\[([^\\]]*)\\]\\(([^\\)]*)\\)", "$1");
        return s;
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    // ============================================================
    //  HTML → BufferedImage
    // ============================================================

    private BufferedImage renderHtmlToImage(String html, int width, Path baseDir) throws Exception {
        // 이미지 base URL 을 HTML 안에 직접 주입
        if (baseDir != null) {
            String baseHref = "<base href=\"" + baseDir.toUri().toString() + "\"/>";
            int headStart = html.indexOf("<head>");
            if (headStart >= 0) {
                int after = headStart + "<head>".length();
                html = html.substring(0, after) + baseHref + html.substring(after);
            }
        }

        JEditorPane pane = new JEditorPane();
        HTMLEditorKit kit = new HTMLEditorKit();
        pane.setEditorKit(kit);
        pane.setText(html);

        // 핵심: editable=false + setOpaque + 명시적 BG/FG + Caret 제거
        pane.setEditable(false);
        pane.setOpaque(true);
        pane.setBackground(Color.WHITE);
        pane.setForeground(Color.BLACK);
        // Caret 자체를 null 로 (선택 영역 그리기 차단)
        pane.setCaret(new javax.swing.text.DefaultCaret() {
            @Override public void paint(Graphics g) { /* no-op */ }
        });
        // 선택 색을 흰색으로 (BLUE 회피)
        pane.setSelectionColor(Color.WHITE);
        pane.setSelectedTextColor(Color.BLACK);
        pane.setDisabledTextColor(Color.BLACK);

        pane.setSize(width, Short.MAX_VALUE);
        pane.validate();
        Dimension preferred = pane.getPreferredSize();
        int height = Math.max(800, preferred.height);
        pane.setSize(width, height);
        pane.validate();
        pane.doLayout();

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

    private List<byte[]> splitToPages(BufferedImage full) throws IOException {
        int totalH = full.getHeight();
        int width = full.getWidth();
        List<byte[]> pages = new ArrayList<>();
        int y = 0;
        while (y < totalH) {
            int h = Math.min(PAGE_H_PX, totalH - y);
            BufferedImage page = new BufferedImage(width, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = page.createGraphics();
            g.drawImage(full,
                    0, 0, width, h,
                    0, y, width, y + h, null);
            g.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(page, "PNG", baos);
            pages.add(baos.toByteArray());
            y += h;
        }
        return pages;
    }

    // ============================================================
    //  HWPX zip 작성 (기존 hwpx-template 활용)
    // ============================================================

    private static final String TEMPLATE_BASE = "/kr/n/nframe/resources/hwpx-template/";

    private void writeHwpxZip(String outPath, List<byte[]> pagePngs) throws IOException {
        Path out = Paths.get(outPath).toAbsolutePath();
        Files.createDirectories(out.getParent());

        try (OutputStream os = Files.newOutputStream(out);
             ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {

            // mimetype (STORED)
            byte[] mimetype = loadTemplate("mimetype");
            ZipEntry mime = new ZipEntry("mimetype");
            mime.setMethod(ZipEntry.STORED);
            mime.setSize(mimetype.length);
            mime.setCompressedSize(mimetype.length);
            CRC32 crc = new CRC32(); crc.update(mimetype);
            mime.setCrc(crc.getValue());
            zip.putNextEntry(mime); zip.write(mimetype); zip.closeEntry();

            // version, settings, META-INF (template 그대로)
            putBin(zip, "version.xml", loadTemplate("version.xml"));
            putBin(zip, "settings.xml", loadTemplate("settings.xml"));
            putBin(zip, "META-INF/container.xml", loadTemplate("META-INF/container.xml"));
            putBin(zip, "META-INF/container.rdf", loadTemplate("META-INF/container.rdf"));
            putBin(zip, "META-INF/manifest.xml", loadTemplate("META-INF/manifest.xml"));

            // content.hpf — 템플릿에 image binItem 추가
            String contentHpf = new String(loadTemplate("Contents/content.hpf"), StandardCharsets.UTF_8);
            contentHpf = injectBinItemsIntoContentHpf(contentHpf, pagePngs.size());
            putBin(zip, "Contents/content.hpf", contentHpf.getBytes(StandardCharsets.UTF_8));

            // header.xml — 템플릿에 binDataList 주입
            String headerXml = new String(loadTemplate("Contents/header.xml"), StandardCharsets.UTF_8);
            headerXml = injectBinDataListIntoHeader(headerXml, pagePngs.size());
            putBin(zip, "Contents/header.xml", headerXml.getBytes(StandardCharsets.UTF_8));

            // section0.xml — 새로 생성 (각 페이지에 hp:pic)
            String sectionXml = buildSection0Xml(pagePngs);
            putBin(zip, "Contents/section0.xml", sectionXml.getBytes(StandardCharsets.UTF_8));

            // BinData/imageN.png
            for (int i = 0; i < pagePngs.size(); i++) {
                String name = "BinData/image" + (i + 1) + ".png";
                putBin(zip, name, pagePngs.get(i));
            }
        }
    }

    private static void putBin(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static byte[] loadTemplate(String relPath) throws IOException {
        try (InputStream in = MdToHwpxImage.class.getResourceAsStream(TEMPLATE_BASE + relPath)) {
            if (in == null) throw new IOException("Template missing: " + relPath);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    /** content.hpf 의 manifest 에서 zip 에 없는 자원 (Scripts/*.js) 참조 제거.
     *  이미지 파일 참조는 manifest 에 추가하지 않음 — header.xml binDataList 만으로 충분. */
    private String injectBinItemsIntoContentHpf(String hpf, int count) {
        hpf = hpf.replaceAll("<opf:item\\s+[^/]*href=\"Scripts/[^\"]+\"[^/]*/\\s*>", "");
        hpf = hpf.replaceAll("<opf:itemref\\s+idref=\"headersc\"[^/]*/\\s*>", "");
        hpf = hpf.replaceAll("<opf:itemref\\s+idref=\"sourcesc\"[^/]*/\\s*>", "");
        return hpf;
    }

    /** header.xml 에 binDataList 추가. </hh:refList> 직전에 주입. */
    private String injectBinDataListIntoHeader(String header, int count) {
        StringBuilder bin = new StringBuilder();
        bin.append("<hh:binDataList itemCnt=\"").append(count).append("\">");
        for (int i = 0; i < count; i++) {
            bin.append("<hh:binItem id=\"image").append(i + 1)
               .append("\" type=\"embedding\" href=\"BinData/image").append(i + 1)
               .append(".png\" mediaType=\"image/png\"/>");
        }
        bin.append("</hh:binDataList>");
        // 기존 binDataList 가 있는지 확인 (있으면 교체)
        String tagOpen = "<hh:binDataList";
        int s = header.indexOf(tagOpen);
        if (s >= 0) {
            // 닫는 태그까지 찾기
            int closeIdx = header.indexOf("</hh:binDataList>", s);
            if (closeIdx >= 0) {
                int endTag = closeIdx + "</hh:binDataList>".length();
                return header.substring(0, s) + bin.toString() + header.substring(endTag);
            }
            // self-closing <hh:binDataList itemCnt="0"/>
            int selfClose = header.indexOf("/>", s);
            if (selfClose >= 0) {
                int endTag = selfClose + 2;
                return header.substring(0, s) + bin.toString() + header.substring(endTag);
            }
        }
        // 없으면 </hh:refList> 직전에 추가
        int idx = header.indexOf("</hh:refList>");
        if (idx < 0) return header;
        return header.substring(0, idx) + bin.toString() + header.substring(idx);
    }

    private static final String NS =
            " xmlns:ha=\"http://www.hancom.co.kr/hwpml/2011/app\""
            + " xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\""
            + " xmlns:hp10=\"http://www.hancom.co.kr/hwpml/2016/paragraph\""
            + " xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\""
            + " xmlns:hc=\"http://www.hancom.co.kr/hwpml/2011/core\""
            + " xmlns:hh=\"http://www.hancom.co.kr/hwpml/2011/head\""
            + " xmlns:hhs=\"http://www.hancom.co.kr/hwpml/2011/history\""
            + " xmlns:hm=\"http://www.hancom.co.kr/hwpml/2011/master-page\""
            + " xmlns:hpf=\"http://www.hancom.co.kr/schema/2011/hpf\""
            + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
            + " xmlns:opf=\"http://www.idpf.org/2007/opf/\"";

    private String buildSection0Xml(List<byte[]> pagePngs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>");
        sb.append("<hs:sec").append(NS).append(">");

        for (int i = 0; i < pagePngs.size(); i++) {
            int pageBreak = i == 0 ? 0 : 1;
            sb.append("<hp:p id=\"").append(i + 1)
              .append("\" paraPrIDRef=\"0\" styleIDRef=\"0\" pageBreak=\"")
              .append(pageBreak)
              .append("\" columnBreak=\"0\" merged=\"0\">");
            sb.append("<hp:run charPrIDRef=\"0\">");
            if (i == 0) {
                sb.append(buildSecPr());
            }
            sb.append("<hp:ctrl>");
            sb.append(buildPicXml(i + 1));
            sb.append("</hp:ctrl>");
            sb.append("</hp:run>");
            sb.append("<hp:linesegarray>");
            sb.append("<hp:lineseg textpos=\"0\" vertpos=\"0\" vertsize=\"1000\""
                    + " textheight=\"1000\" baseline=\"850\" spacing=\"600\" horzpos=\"0\""
                    + " horzsize=\"").append(BODY_W_HU).append("\" flags=\"393216\"/>");
            sb.append("</hp:linesegarray>");
            sb.append("</hp:p>");
        }

        sb.append("</hs:sec>");
        return sb.toString();
    }

    private String buildSecPr() {
        return "<hp:secPr id=\"\" textDirection=\"HORIZONTAL\" spaceColumns=\"1134\" tabStop=\"8000\""
                + " tabStopVal=\"4000\" tabStopUnit=\"HWPUNIT\" outlineShapeIDRef=\"0\" memoShapeIDRef=\"0\""
                + " textVerticalWidthHead=\"0\" masterPageCnt=\"0\">"
                + "<hp:grid lineGrid=\"0\" charGrid=\"0\" wonggojiFormat=\"0\"/>"
                + "<hp:startNum pageStartsOn=\"BOTH\" page=\"0\" pic=\"0\" tbl=\"0\" equation=\"0\"/>"
                + "<hp:visibility hideFirstHeader=\"0\" hideFirstFooter=\"0\" hideFirstMasterPage=\"0\""
                + " border=\"SHOW_ALL\" fill=\"SHOW_ALL\" hideFirstPageNum=\"0\" hideFirstEmptyLine=\"0\" showLineNumber=\"0\"/>"
                + "<hp:lineNumberShape restartType=\"0\" countBy=\"0\" distance=\"0\" startNumber=\"0\"/>"
                + "<hp:pagePr landscape=\"WIDELY\" width=\"" + A4_W_HU + "\" height=\"" + A4_H_HU + "\" gutterType=\"LEFT_ONLY\">"
                + "<hp:margin header=\"4251\" footer=\"4251\" gutter=\"0\" left=\"5669\" right=\"5669\" top=\"4251\" bottom=\"4251\"/>"
                + "</hp:pagePr>"
                + "<hp:footNotePr>"
                + "<hp:autoNumFormat type=\"DIGIT\" userChar=\"\" prefixChar=\"\" suffixChar=\")\" supscript=\"0\"/>"
                + "<hp:noteLine length=\"-1\" type=\"SOLID\" width=\"0.12 mm\" color=\"#000000\"/>"
                + "<hp:noteSpacing betweenNotes=\"283\" belowLine=\"567\" aboveLine=\"850\"/>"
                + "<hp:numbering type=\"CONTINUOUS\" newNum=\"1\"/>"
                + "<hp:placement place=\"EACH_COLUMN\" beneathText=\"0\"/>"
                + "</hp:footNotePr>"
                + "<hp:endNotePr>"
                + "<hp:autoNumFormat type=\"DIGIT\" userChar=\"\" prefixChar=\"\" suffixChar=\")\" supscript=\"0\"/>"
                + "<hp:noteLine length=\"14692344\" type=\"SOLID\" width=\"0.12 mm\" color=\"#000000\"/>"
                + "<hp:noteSpacing betweenNotes=\"0\" belowLine=\"567\" aboveLine=\"850\"/>"
                + "<hp:numbering type=\"CONTINUOUS\" newNum=\"1\"/>"
                + "<hp:placement place=\"END_OF_DOCUMENT\" beneathText=\"0\"/>"
                + "</hp:endNotePr>"
                + "</hp:secPr>";
    }

    private String buildPicXml(int imageId) {
        int picW = BODY_W_HU;
        int picH = BODY_H_HU;
        StringBuilder sb = new StringBuilder();
        sb.append("<hp:pic id=\"").append(imageId)
          .append("\" zOrder=\"0\" textWrap=\"TOP_AND_BOTTOM\" textFlow=\"BOTH_SIDES\""
                + " lock=\"0\" dropcapstyle=\"None\" numberingType=\"PICTURE\" lineWrap=\"BREAK\""
                + " href=\"\" pageBreak=\"0\" pgRetrieve=\"0\" reverse=\"0\" version=\"0\">");
        sb.append("<hp:offset x=\"0\" y=\"0\"/>");
        sb.append("<hp:orgSz width=\"").append(picW).append("\" height=\"").append(picH).append("\"/>");
        sb.append("<hp:curSz width=\"").append(picW).append("\" height=\"").append(picH).append("\"/>");
        sb.append("<hp:flip horizontal=\"0\" vertical=\"0\"/>");
        sb.append("<hp:rotationInfo angle=\"0\" centerX=\"").append(picW / 2)
          .append("\" centerY=\"").append(picH / 2).append("\"/>");
        sb.append("<hp:renderingInfo>");
        sb.append("<hc:transMatrix e1=\"1.0\" e2=\"0.0\" e3=\"0.0\" e4=\"0.0\" e5=\"1.0\" e6=\"0.0\"/>");
        sb.append("<hc:scaMatrix e1=\"1.0\" e2=\"0.0\" e3=\"0.0\" e4=\"0.0\" e5=\"1.0\" e6=\"0.0\"/>");
        sb.append("<hc:rotMatrix e1=\"1.0\" e2=\"0.0\" e3=\"0.0\" e4=\"0.0\" e5=\"1.0\" e6=\"0.0\"/>");
        sb.append("</hp:renderingInfo>");
        sb.append("<hp:imgRect>");
        sb.append("<hc:pt0 x=\"0\" y=\"0\"/>");
        sb.append("<hc:pt1 x=\"").append(picW).append("\" y=\"0\"/>");
        sb.append("<hc:pt2 x=\"").append(picW).append("\" y=\"").append(picH).append("\"/>");
        sb.append("<hc:pt3 x=\"0\" y=\"").append(picH).append("\"/>");
        sb.append("</hp:imgRect>");
        sb.append("<hp:imgClip left=\"0\" top=\"0\" right=\"0\" bottom=\"0\"/>");
        sb.append("<hp:inMargin left=\"0\" right=\"0\" top=\"0\" bottom=\"0\"/>");
        sb.append("<hp:img binaryItemIDRef=\"image").append(imageId)
          .append("\" bright=\"0\" contrast=\"0\" effect=\"REAL_PIC\"/>");
        sb.append("<hp:effects/>");
        sb.append("</hp:pic>");
        return sb.toString();
    }
}
