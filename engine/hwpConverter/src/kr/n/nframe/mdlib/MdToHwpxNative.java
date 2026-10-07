package kr.n.nframe.mdlib;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * v14.12: MD → HWPX (네이티브 컨텐츠).
 *
 * <p>이미지 렌더링 방식 (v14.11) 의 Swing 렌더링 한계를 우회하고, 한/글이 직접
 * 렌더링하는 네이티브 HWPX 컨텐츠를 생성한다. 한/글의 자체 폰트/표/이미지
 * 렌더링 엔진이 사용되므로 VSCode preview 수준의 깔끔한 시각 결과를 보장.
 *
 * <h3>지원 컨텐츠</h3>
 * <ul>
 *   <li>헤딩 → charShape 크기별 (template 의 H1~H6 ID 활용)</li>
 *   <li>본문 paragraph + 인라인 마크업 (bold/italic) → charShape 변화</li>
 *   <li>HTML &lt;table&gt; → 네이티브 &lt;hp:tbl&gt; 컨트롤 + cells</li>
 *   <li>!이미지](path) → 네이티브 &lt;hp:pic&gt; + BinData 임베드</li>
 *   <li>리스트 → 들여쓰기 paragraph</li>
 * </ul>
 */
public class MdToHwpxNative {

    // 템플릿 charShape (case1_50p.hwpx 기준)
    private static final int CHARPR_NORMAL = 0;
    private static final int CHARPR_H1     = 7;
    private static final int CHARPR_H2     = 5;
    private static final int CHARPR_H3     = 12;
    private static final int CHARPR_H4     = 16;
    private static final int CHARPR_H5     = 6;
    private static final int CHARPR_H6     = 0;
    private static final int PARAPR_DEFAULT = 0;

    private static final String TEMPLATE_BASE = "/kr/n/nframe/resources/hwpx-template/";
    private static final String[] TEMPLATE_FILES = {
            "mimetype", "version.xml", "settings.xml",
            "Contents/header.xml",
            "META-INF/container.xml", "META-INF/container.rdf", "META-INF/manifest.xml",
    };

    private static final int A4_W_HU = 59528;
    private static final int A4_H_HU = 84188;
    private static final int BODY_W_HU = 41954;

    private Path mdParent;
    private List<EmbeddedImage> embeddedImages = new ArrayList<>();

    /** 디버깅용 토글 — 표 / 이미지 / heading charShape 변경을 끌 수 있음 */
    public boolean enableTables = true;
    public boolean enableImages = true;
    public boolean enableHeadingShapes = true;

    static class EmbeddedImage {
        String binId;
        String href;        // BinData/imageN.<ext>
        String mediaType;
        byte[] bytes;
        int origWidthPx;
        int origHeightPx;
    }

    public void convert(String mdPath, String outHwpx) throws Exception {
        System.out.println("[MdToHwpxNative] 시작 (네이티브 HWPX 생성)");
        System.out.println("[MdToHwpxNative] Input : " + mdPath);
        System.out.println("[MdToHwpxNative] Output: " + outHwpx);

        Path md = Paths.get(mdPath).toAbsolutePath();
        this.mdParent = md.getParent();
        String text = new String(Files.readAllBytes(md), StandardCharsets.UTF_8);

        List<Block> blocks = parseMarkdown(text);
        int hCount=0, pCount=0, tCount=0, iCount=0, bCount=0;
        for (Block b : blocks) {
            if (b instanceof Heading) hCount++;
            else if (b instanceof MdParagraph) pCount++;
            else if (b instanceof Table) tCount++;
            else if (b instanceof Image) iCount++;
            else if (b instanceof Blank) bCount++;
        }
        System.out.println("[MdToHwpxNative] 파싱: heading=" + hCount + ", paragraph=" + pCount
                + ", table=" + tCount + ", image=" + iCount + ", blank=" + bCount);

        String sectionXml = buildSection0Xml(blocks);
        writeHwpxZip(outHwpx, sectionXml);
        System.out.println("[MdToHwpxNative] HWPX 저장 완료: " + outHwpx
                + " (" + new File(outHwpx).length() + " bytes, 임베드 이미지=" + embeddedImages.size() + ")");
    }

    // =====================================================
    //  파서
    // =====================================================

    static abstract class Block {}
    static class Heading extends Block { int level; String text; Heading(int lv, String t){level=lv; text=t;} }
    static class MdParagraph extends Block { String text; MdParagraph(String t){text=t;} }
    static class Table extends Block { List<List<String>> rows = new ArrayList<>(); }
    static class Image extends Block { String alt; String path; Image(String a, String p){alt=a; path=p;} }
    static class Blank extends Block {}

    private List<Block> parseMarkdown(String text) {
        text = text.replaceAll("(?s)<!--.*?-->", "");
        // 멀티라인 이미지 마크다운 → placeholder 후 처리
        List<String[]> imageRefs = new ArrayList<>();
        Matcher imgM = Pattern.compile("(?s)!\\[([^\\]]*?)\\]\\(([^)\\s]+(?:\\s[^)]*)?)\\)").matcher(text);
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (imgM.find()) {
            String alt = imgM.group(1).replaceAll("\\s+", " ").trim();
            String path = imgM.group(2).replaceAll("\\s+", " ").trim();
            imageRefs.add(new String[]{alt, path});
            imgM.appendReplacement(sb, Matcher.quoteReplacement("\n[[IMG#" + idx + "]]\n"));
            idx++;
        }
        imgM.appendTail(sb);
        text = sb.toString();

        String[] lines = text.split("\\r?\\n", -1);
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trim = line.trim();

            if (trim.isEmpty()) { blocks.add(new Blank()); i++; continue; }

            Matcher imM = Pattern.compile("^\\[\\[IMG#(\\d+)\\]\\]$").matcher(trim);
            if (imM.matches()) {
                int ix = Integer.parseInt(imM.group(1));
                if (ix < imageRefs.size()) {
                    String[] ref = imageRefs.get(ix);
                    blocks.add(new Image(ref[0], ref[1]));
                }
                i++; continue;
            }

            // ATX heading
            Matcher hm = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$").matcher(trim);
            if (hm.matches()) {
                blocks.add(new Heading(hm.group(1).length(), stripInlineMarkup(hm.group(2))));
                i++; continue;
            }

            // 수평선
            if (trim.matches("-{3,}|\\*{3,}|_{3,}")) {
                blocks.add(new Blank()); i++; continue;
            }

            // HTML <table> 블록
            if (trim.toLowerCase().startsWith("<table")) {
                StringBuilder buf = new StringBuilder(line).append('\n');
                int depth = 1;
                i++;
                while (i < lines.length && depth > 0) {
                    String l = lines[i];
                    String lLow = l.toLowerCase();
                    int opens = countOcc(lLow, "<table");
                    int closes = countOcc(lLow, "</table");
                    depth += opens - closes;
                    buf.append(l).append('\n');
                    i++;
                    if (depth <= 0) break;
                }
                Table t = parseHtmlTable(buf.toString());
                if (!t.rows.isEmpty()) blocks.add(t);
                continue;
            }

            // 그 외 단독 HTML
            if (trim.startsWith("<")) {
                String plain = stripHtmlTags(line);
                if (!plain.trim().isEmpty()) {
                    blocks.add(new MdParagraph(stripInlineMarkup(plain.trim())));
                }
                i++; continue;
            }

            // 단순 라인 paragraph (들여쓰기/리스트 마커 보존)
            blocks.add(new MdParagraph(stripInlineMarkup(line)));
            i++;
        }
        return blocks;
    }

    private static int countOcc(String h, String n) {
        int c=0,i=0; while ((i=h.indexOf(n,i))>=0){c++; i+=n.length();} return c;
    }

    private static Table parseHtmlTable(String html) {
        Table t = new Table();
        Matcher trM = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(html);
        Pattern cellPat = Pattern.compile("<(t[hd])[^>]*>(.*?)</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        while (trM.find()) {
            String trC = trM.group(1);
            List<String> row = new ArrayList<>();
            Matcher cM = cellPat.matcher(trC);
            while (cM.find()) {
                String cellHtml = cM.group(2).replaceAll("(?i)<br\\s*/?\\s*>", " / ");
                String plain = stripHtmlTags(cellHtml).trim();
                row.add(stripInlineMarkup(plain));
            }
            if (!row.isEmpty()) t.rows.add(row);
        }
        return t;
    }

    private static String stripInlineMarkup(String s) {
        if (s == null || s.isEmpty()) return "";
        s = s.replaceAll("!\\[([^\\]]*)\\]\\(([^\\)]*)\\)", "$1");
        s = s.replaceAll("\\[([^\\]]*)\\]\\(([^\\)]*)\\)", "$1");
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        s = s.replaceAll("__([^_]+)__", "$1");
        s = s.replaceAll("(?<!\\*)\\*(?!\\*)([^*]+)(?<!\\*)\\*(?!\\*)", "$1");
        s = s.replaceAll("(?<!_)_(?!_)([^_]+)(?<!_)_(?!_)", "$1");
        s = s.replaceAll("~~([^~]+)~~", "$1");
        s = s.replaceAll("`([^`]+)`", "$1");
        s = s.replaceAll("\\\\([*_\\[\\]#])", "$1");
        s = s.replaceAll("<[^>]+>", "");
        s = s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&nbsp;", " ");
        return s;
    }

    private static String stripHtmlTags(String s) {
        return s.replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&nbsp;", " ");
    }

    // =====================================================
    //  Section XML 생성
    // =====================================================

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

    private String buildSection0Xml(List<Block> blocks) {
        StringBuilder out = new StringBuilder(65536);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>");
        out.append("<hs:sec").append(NS).append(">");

        boolean first = true;
        for (Block b : blocks) {
            appendBlockXml(out, b, first);
            first = false;
        }

        out.append("</hs:sec>");
        return out.toString();
    }

    private void appendBlockXml(StringBuilder out, Block b, boolean first) {
        if (b instanceof Heading) {
            Heading h = (Heading) b;
            int charPr = enableHeadingShapes ? charPrForHeading(h.level) : CHARPR_NORMAL;
            out.append(openParagraph());
            out.append("<hp:run charPrIDRef=\"").append(charPr).append("\">");
            if (first) out.append(SECTION_PR);
            String prefix = enableHeadingShapes ? "" : repeat("#", h.level) + " ";
            out.append("<hp:t>").append(xmlEscape(prefix + h.text)).append("</hp:t>");
            out.append("</hp:run>");
            out.append(LINE_SEG);
            out.append("</hp:p>");
        } else if (b instanceof MdParagraph) {
            MdParagraph p = (MdParagraph) b;
            out.append(openParagraph());
            out.append("<hp:run charPrIDRef=\"").append(CHARPR_NORMAL).append("\">");
            if (first) out.append(SECTION_PR);
            out.append("<hp:t>").append(xmlEscape(p.text)).append("</hp:t>");
            out.append("</hp:run>");
            out.append(LINE_SEG);
            out.append("</hp:p>");
        } else if (b instanceof Table) {
            Table t = (Table) b;
            if (enableTables) {
                out.append(openParagraph());
                out.append("<hp:run charPrIDRef=\"").append(CHARPR_NORMAL).append("\">");
                if (first) out.append(SECTION_PR);
                out.append(buildTableXml(t));
                out.append("</hp:run>");
                out.append(LINE_SEG);
                out.append("</hp:p>");
            } else {
                // 표를 텍스트로 fall back (각 행을 별도 paragraph 로)
                List<String> lines = renderTableAsText(t);
                for (int i = 0; i < lines.size(); i++) {
                    out.append(openParagraph());
                    out.append("<hp:run charPrIDRef=\"").append(CHARPR_NORMAL).append("\">");
                    if (first && i == 0) out.append(SECTION_PR);
                    out.append("<hp:t>").append(xmlEscape(lines.get(i))).append("</hp:t>");
                    out.append("</hp:run>");
                    out.append(LINE_SEG);
                    out.append("</hp:p>");
                }
            }
        } else if (b instanceof Image) {
            Image im = (Image) b;
            EmbeddedImage emb = enableImages ? registerImage(im.path) : null;
            out.append(openParagraph());
            out.append("<hp:run charPrIDRef=\"").append(CHARPR_NORMAL).append("\">");
            if (first) out.append(SECTION_PR);
            if (emb != null) {
                out.append(buildPicXml(emb));
            } else {
                out.append("<hp:t>").append(xmlEscape("[이미지: " + im.path + "]")).append("</hp:t>");
            }
            out.append("</hp:run>");
            out.append(LINE_SEG);
            out.append("</hp:p>");
        } else if (b instanceof Blank) {
            out.append(openParagraph());
            out.append("<hp:run charPrIDRef=\"").append(CHARPR_NORMAL).append("\">");
            if (first) out.append(SECTION_PR);
            out.append("</hp:run>");
            out.append(LINE_SEG);
            out.append("</hp:p>");
        }
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static List<String> renderTableAsText(Table t) {
        List<String> out = new ArrayList<>();
        for (List<String> row : t.rows) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) sb.append(" | ");
                sb.append(row.get(c));
            }
            out.add(sb.toString());
        }
        return out;
    }

    private String openParagraph() {
        return "<hp:p id=\"0\" paraPrIDRef=\"" + PARAPR_DEFAULT
                + "\" styleIDRef=\"0\" pageBreak=\"0\" columnBreak=\"0\" merged=\"0\">";
    }

    private int charPrForHeading(int level) {
        switch (level) {
            case 1: return CHARPR_H1;
            case 2: return CHARPR_H2;
            case 3: return CHARPR_H3;
            case 4: return CHARPR_H4;
            case 5: return CHARPR_H5;
            default: return CHARPR_H6;
        }
    }

    /** HWPX &lt;hp:tbl&gt; XML 생성 (격자 표). */
    private String buildTableXml(Table t) {
        int rowCnt = t.rows.size();
        int colCnt = 0;
        for (List<String> r : t.rows) colCnt = Math.max(colCnt, r.size());
        if (colCnt == 0) colCnt = 1;

        // 표 폭/높이 (HWPUNIT)
        int tableW = BODY_W_HU; // 본문 폭
        int colW = tableW / colCnt;
        int rowH = 1500; // 셀 높이 (자동 늘어남)
        int tableH = rowH * rowCnt;

        StringBuilder s = new StringBuilder();
        s.append("<hp:tbl id=\"").append(java.util.concurrent.ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE))
         .append("\" zOrder=\"0\" numberingType=\"TABLE\" textWrap=\"TOP_AND_BOTTOM\" textFlow=\"BOTH_SIDES\""
                + " lock=\"0\" dropcapstyle=\"None\" pageBreak=\"TABLE\" repeatHeader=\"1\" rowCnt=\"")
         .append(rowCnt).append("\" colCnt=\"").append(colCnt)
         .append("\" cellSpacing=\"0\" borderFillIDRef=\"1\" noAdjust=\"0\">");
        s.append("<hp:sz width=\"").append(tableW).append("\" widthRelTo=\"ABSOLUTE\" height=\"")
         .append(tableH).append("\" heightRelTo=\"ABSOLUTE\" protect=\"0\"/>");
        s.append("<hp:pos treatAsChar=\"1\" affectLSpacing=\"0\" flowWithText=\"1\" allowOverlap=\"0\""
                + " holdAnchorAndSO=\"0\" vertRelTo=\"PARA\" horzRelTo=\"COLUMN\" vertAlign=\"TOP\""
                + " horzAlign=\"LEFT\" vertOffset=\"0\" horzOffset=\"0\"/>");
        s.append("<hp:outMargin left=\"140\" right=\"140\" top=\"140\" bottom=\"140\"/>");
        s.append("<hp:inMargin left=\"510\" right=\"510\" top=\"141\" bottom=\"141\"/>");

        for (int r = 0; r < rowCnt; r++) {
            s.append("<hp:tr>");
            List<String> row = t.rows.get(r);
            for (int c = 0; c < colCnt; c++) {
                String cell = c < row.size() ? row.get(c) : "";
                s.append("<hp:tc header=\"").append(r == 0 ? "1" : "0")
                 .append("\" hasMargin=\"0\" protect=\"0\" editable=\"0\" dirty=\"0\" borderFillIDRef=\"1\">");
                s.append("<hp:subList id=\"\" textDirection=\"HORIZONTAL\" lineWrap=\"BREAK\" vertAlign=\"CENTER\""
                        + " linkListIDRef=\"0\" linkListNextIDRef=\"0\" textWidth=\"").append(colW - 1022)
                 .append("\" textHeight=\"0\" hasTextRef=\"0\" hasNumRef=\"0\">");
                // cell paragraph
                s.append("<hp:p id=\"0\" paraPrIDRef=\"0\" styleIDRef=\"0\" pageBreak=\"0\""
                        + " columnBreak=\"0\" merged=\"0\">");
                s.append("<hp:run charPrIDRef=\"").append(r == 0 ? CHARPR_H6 : CHARPR_NORMAL).append("\">");
                s.append("<hp:t>").append(xmlEscape(cell)).append("</hp:t>");
                s.append("</hp:run>");
                s.append("<hp:linesegarray>")
                 .append("<hp:lineseg textpos=\"0\" vertpos=\"0\" vertsize=\"1000\" textheight=\"1000\"")
                 .append(" baseline=\"850\" spacing=\"600\" horzpos=\"0\" horzsize=\"")
                 .append(colW - 1022).append("\" flags=\"393216\"/>")
                 .append("</hp:linesegarray>");
                s.append("</hp:p>");
                s.append("</hp:subList>");
                s.append("<hp:cellAddr colAddr=\"").append(c).append("\" rowAddr=\"").append(r).append("\"/>");
                s.append("<hp:cellSpan colSpan=\"1\" rowSpan=\"1\"/>");
                s.append("<hp:cellSz width=\"").append(colW).append("\" height=\"").append(rowH).append("\"/>");
                s.append("<hp:cellMargin left=\"141\" right=\"141\" top=\"141\" bottom=\"141\"/>");
                s.append("</hp:tc>");
            }
            s.append("</hp:tr>");
        }
        s.append("</hp:tbl>");
        return s.toString();
    }

    /** 이미지를 BinData 로 등록하고 EmbeddedImage 객체 반환. */
    private EmbeddedImage registerImage(String relPath) {
        if (mdParent == null) return null;
        try {
            Path p = mdParent.resolve(relPath).toAbsolutePath();
            if (!Files.exists(p)) {
                // 다른 경로 시도
                p = Paths.get(relPath).toAbsolutePath();
                if (!Files.exists(p)) return null;
            }
            byte[] bytes = Files.readAllBytes(p);
            String fname = p.getFileName().toString().toLowerCase();
            String mime;
            if (fname.endsWith(".png")) mime = "image/png";
            else if (fname.endsWith(".jpg") || fname.endsWith(".jpeg")) mime = "image/jpeg";
            else if (fname.endsWith(".bmp")) mime = "image/bmp";
            else if (fname.endsWith(".gif")) mime = "image/gif";
            else mime = "application/octet-stream";

            EmbeddedImage e = new EmbeddedImage();
            e.binId = "image" + (embeddedImages.size() + 1);
            String ext = fname.substring(fname.lastIndexOf('.'));
            e.href = "BinData/" + e.binId + ext;
            e.mediaType = mime;
            e.bytes = bytes;
            // 이미지 크기 측정
            try {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
                if (img != null) {
                    e.origWidthPx = img.getWidth();
                    e.origHeightPx = img.getHeight();
                }
            } catch (Throwable t) { /* ignore */ }
            if (e.origWidthPx <= 0) e.origWidthPx = 600;
            if (e.origHeightPx <= 0) e.origHeightPx = 400;
            embeddedImages.add(e);
            return e;
        } catch (IOException ioe) {
            return null;
        }
    }

    /** &lt;hp:pic&gt; XML — 한글 .hwp 가 만드는 실제 구조와 동일한 element 순서 사용. */
    private String buildPicXml(EmbeddedImage emb) {
        int maxW = (int) (BODY_W_HU * 0.9);
        int picW = emb.origWidthPx * 60;
        int picH = emb.origHeightPx * 60;
        if (picW > maxW) {
            double scale = (double) maxW / picW;
            picW = maxW;
            picH = (int) (picH * scale);
        }
        if (picH < 100) picH = 100;
        if (picW < 100) picW = 100;

        int instId = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);

        StringBuilder sb = new StringBuilder();
        sb.append("<hp:pic id=\"").append(instId)
          .append("\" zOrder=\"0\" numberingType=\"PICTURE\" textWrap=\"TOP_AND_BOTTOM\" textFlow=\"BOTH_SIDES\""
                + " lock=\"0\" dropcapstyle=\"None\" href=\"\" groupLevel=\"0\" instid=\"").append(instId)
          .append("\" reverse=\"0\">");
        // 1. offset, orgSz, curSz, flip, rotationInfo, renderingInfo
        sb.append("<hp:offset x=\"0\" y=\"0\"/>");
        sb.append("<hp:orgSz width=\"").append(picW).append("\" height=\"").append(picH).append("\"/>");
        sb.append("<hp:curSz width=\"").append(picW).append("\" height=\"").append(picH).append("\"/>");
        sb.append("<hp:flip horizontal=\"0\" vertical=\"0\"/>");
        sb.append("<hp:rotationInfo angle=\"0\" centerX=\"").append(picW/2)
          .append("\" centerY=\"").append(picH/2).append("\" rotateimage=\"1\"/>");
        sb.append("<hp:renderingInfo>");
        sb.append("<hc:transMatrix e1=\"1\" e2=\"0\" e3=\"0\" e4=\"0\" e5=\"1\" e6=\"0\"/>");
        sb.append("<hc:scaMatrix e1=\"1\" e2=\"0\" e3=\"0\" e4=\"0\" e5=\"1\" e6=\"0\"/>");
        sb.append("<hc:rotMatrix e1=\"1\" e2=\"0\" e3=\"0\" e4=\"0\" e5=\"1\" e6=\"0\"/>");
        sb.append("</hp:renderingInfo>");
        // 2. imgRect, imgClip(left right top bottom 순서!), inMargin, imgDim
        sb.append("<hp:imgRect>");
        sb.append("<hc:pt0 x=\"0\" y=\"0\"/>");
        sb.append("<hc:pt1 x=\"").append(picW).append("\" y=\"0\"/>");
        sb.append("<hc:pt2 x=\"").append(picW).append("\" y=\"").append(picH).append("\"/>");
        sb.append("<hc:pt3 x=\"0\" y=\"").append(picH).append("\"/>");
        sb.append("</hp:imgRect>");
        sb.append("<hp:imgClip left=\"0\" right=\"0\" top=\"0\" bottom=\"0\"/>");
        sb.append("<hp:inMargin left=\"0\" right=\"0\" top=\"0\" bottom=\"0\"/>");
        sb.append("<hp:imgDim dimwidth=\"").append(picW).append("\" dimheight=\"").append(picH).append("\"/>");
        // 3. img (NOTE: hc:img not hp:img!)
        sb.append("<hc:img binaryItemIDRef=\"").append(emb.binId)
          .append("\" bright=\"0\" contrast=\"0\" effect=\"REAL_PIC\" alpha=\"0\"/>");
        sb.append("<hp:effects/>");
        // 4. sz, pos, outMargin (끝에!)
        sb.append("<hp:sz width=\"").append(picW).append("\" widthRelTo=\"ABSOLUTE\" height=\"")
          .append(picH).append("\" heightRelTo=\"ABSOLUTE\" protect=\"0\"/>");
        sb.append("<hp:pos treatAsChar=\"1\" affectLSpacing=\"0\" flowWithText=\"1\" allowOverlap=\"0\""
                + " holdAnchorAndSO=\"0\" vertRelTo=\"PARA\" horzRelTo=\"COLUMN\" vertAlign=\"TOP\""
                + " horzAlign=\"LEFT\" vertOffset=\"0\" horzOffset=\"0\"/>");
        sb.append("<hp:outMargin left=\"0\" right=\"0\" top=\"0\" bottom=\"0\"/>");
        sb.append("</hp:pic>");
        return sb.toString();
    }

    private static final String LINE_SEG =
            "<hp:linesegarray><hp:lineseg textpos=\"0\" vertpos=\"0\" vertsize=\"1000\""
            + " textheight=\"1000\" baseline=\"850\" spacing=\"600\" horzpos=\"0\""
            + " horzsize=\"" + BODY_W_HU + "\" flags=\"393216\"/></hp:linesegarray>";

    private static final String SECTION_PR =
            "<hp:secPr id=\"\" textDirection=\"HORIZONTAL\" spaceColumns=\"1134\" tabStop=\"8000\""
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

    private static String xmlEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                        // 제어문자 제거
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // =====================================================
    //  HWPX zip 작성
    // =====================================================

    private void writeHwpxZip(String outputPath, String sectionXml) throws IOException {
        Path out = Paths.get(outputPath).toAbsolutePath();
        Files.createDirectories(out.getParent());
        try (OutputStream os = Files.newOutputStream(out);
             ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {

            byte[] mimetype = loadTemplate("mimetype");
            ZipEntry mime = new ZipEntry("mimetype");
            mime.setMethod(ZipEntry.STORED);
            mime.setSize(mimetype.length);
            mime.setCompressedSize(mimetype.length);
            CRC32 crc = new CRC32(); crc.update(mimetype);
            mime.setCrc(crc.getValue());
            zip.putNextEntry(mime); zip.write(mimetype); zip.closeEntry();

            for (String name : TEMPLATE_FILES) {
                if ("mimetype".equals(name)) continue;
                byte[] data = loadTemplate(name);
                // header.xml 은 그대로 (binDataList 주입 안 함 — 실제 한글 형식이 그렇지 않음)
                zip.putNextEntry(new ZipEntry(name));
                zip.write(data);
                zip.closeEntry();
            }

            // content.hpf — Scripts/* 제거 + 이미지 binItem 추가 (isEmbeded 속성 포함)
            // 주의: media-type 값에 `/` 가 들어있어서 [^/]* regex 가 잘못 매칭됨.
            //       대신 `<opf:item ...href="Scripts/...".../>` 전체 끝까지 non-greedy 로 매칭.
            String hpf = new String(loadTemplate("Contents/content.hpf"), StandardCharsets.UTF_8);
            hpf = hpf.replaceAll("(?s)<opf:item\\b[^>]*href=\"Scripts/[^\"]+\"[^>]*/>", "");
            hpf = hpf.replaceAll("<opf:itemref\\s+idref=\"headersc\"[^>]*/>", "");
            hpf = hpf.replaceAll("<opf:itemref\\s+idref=\"sourcesc\"[^>]*/>", "");
            // 이미지 binItem 추가 (manifest 끝에)
            if (!embeddedImages.isEmpty()) {
                StringBuilder items = new StringBuilder();
                for (EmbeddedImage e : embeddedImages) {
                    items.append("<opf:item id=\"").append(e.binId)
                         .append("\" href=\"").append(e.href)
                         .append("\" media-type=\"").append(e.mediaType)
                         .append("\" isEmbeded=\"1\"/>");
                }
                int idx = hpf.indexOf("</opf:manifest>");
                if (idx >= 0) {
                    hpf = hpf.substring(0, idx) + items.toString() + hpf.substring(idx);
                }
            }
            zip.putNextEntry(new ZipEntry("Contents/content.hpf"));
            zip.write(hpf.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            // section0.xml
            zip.putNextEntry(new ZipEntry("Contents/section0.xml"));
            zip.write(sectionXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            // BinData/*
            for (EmbeddedImage e : embeddedImages) {
                zip.putNextEntry(new ZipEntry(e.href));
                zip.write(e.bytes);
                zip.closeEntry();
            }
        }
    }

    private byte[] loadTemplate(String relPath) throws IOException {
        try (InputStream in = MdToHwpxNative.class.getResourceAsStream(TEMPLATE_BASE + relPath)) {
            if (in == null) throw new IOException("Template missing: " + relPath);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }
}
