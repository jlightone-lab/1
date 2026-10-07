package kr.n.nframe.newfeature;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v15.29-newfeature : Markdown(중간표현) → ODT(.odt) 작성기.
 *
 * <p>HwpMdConverter 가 출력하는 Markdown 형식을 입력으로 받아 LibreOffice
 * Writer 가 열 수 있는 OpenDocument Text(.odt) ZIP 패키지를 작성한다.
 *
 * <p>입력 MD 가 가질 수 있는 마크업 (HwpMdConverter 분석 결과):
 * <ul>
 *   <li># ~ ###### 헤딩</li>
 *   <li>GFM 파이프 표 ( | a | b | … ) — 단순 표</li>
 *   <li>HTML &lt;table&gt; — 복잡/병합 표 (셀 텍스트만 추출)</li>
 *   <li>이미지 ![alt](file:///… or data:image/…;base64,…)</li>
 *   <li>정렬 &lt;div align="center|right"&gt;…&lt;/div&gt;</li>
 *   <li>리스트 "- " / "1. "</li>
 *   <li>강조 **bold** / *italic* / &lt;u&gt;…&lt;/u&gt; / ~~strike~~</li>
 *   <li>코드 펜스 ``` … ```</li>
 * </ul>
 *
 * <p>설계: 신규 의존성을 늘리지 않기 위해 javax.xml / java.util.zip 만으로
 * ODT 패키지를 생성한다. ODT 의 모든 디테일을 보존하지는 않지만 헤딩 위계,
 * 표, 이미지, 정렬, 강조 서식의 시각 요소를 LibreOffice 화면에 노출한다.
 */
public class MdToOdtWriter {

    private static final String NS_OFFICE  = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    private static final String NS_STYLE   = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    private static final String NS_TEXT    = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    private static final String NS_TABLE   = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    private static final String NS_DRAW    = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0";
    private static final String NS_FO      = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";
    private static final String NS_XLINK   = "http://www.w3.org/1999/xlink";
    private static final String NS_SVG     = "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0";
    private static final String NS_MANI    = "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0";

    /** 이미지 entry 누적: Pictures/<name> → bytes */
    private final Map<String, byte[]> pictures = new LinkedHashMap<>();
    private int pictureCounter = 0;
    /** 단락/셀 컨텐츠 누적. write() 마치면 content.xml 에 직렬화. */
    private final StringBuilder body = new StringBuilder();

    // =========================================================
    //  공개 API
    // =========================================================

    /**
     * Markdown 파일을 읽어 ODT 파일로 작성한다.
     *
     * @param mdPath  입력 .md (UTF-8) 경로
     * @param odtPath 출력 .odt 경로
     */
    public void write(String mdPath, String odtPath) throws IOException {
        if (mdPath == null || odtPath == null) throw new IllegalArgumentException("paths must not be null");
        Path in = Paths.get(mdPath);
        if (!Files.exists(in)) throw new IOException("MD 파일이 존재하지 않습니다: " + mdPath);

        String md = new String(Files.readAllBytes(in), StandardCharsets.UTF_8);
        parseAndEmit(md);

        Path out = Paths.get(odtPath);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        writeOdtPackage(out);
    }

    // =========================================================
    //  Markdown 파싱 / ODT XML emit
    // =========================================================

    private void parseAndEmit(String md) throws IOException {
        // 라인 단위 처리. CRLF → LF 정규화.
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        boolean inCode = false;
        StringBuilder codeBuf = new StringBuilder();

        while (i < lines.length) {
            String line = lines[i];

            // ----- 코드 펜스 -----
            if (line.startsWith("```")) {
                if (!inCode) {
                    inCode = true; codeBuf.setLength(0);
                } else {
                    emitCodeBlock(codeBuf.toString());
                    inCode = false;
                }
                i++; continue;
            }
            if (inCode) {
                if (codeBuf.length() > 0) codeBuf.append('\n');
                codeBuf.append(line);
                i++; continue;
            }

            // ----- 빈 줄 -----
            if (line.trim().isEmpty()) { i++; continue; }

            // ----- HTML 표 (HwpMdConverter 의 복잡표 출력) -----
            if (line.trim().toLowerCase().startsWith("<table")) {
                StringBuilder tbl = new StringBuilder();
                while (i < lines.length) {
                    tbl.append(lines[i]).append('\n');
                    if (lines[i].toLowerCase().contains("</table>")) { i++; break; }
                    i++;
                }
                emitHtmlTable(tbl.toString());
                continue;
            }

            // ----- 정렬 div -----
            Matcher md1 = ALIGN_DIV_OPEN.matcher(line);
            if (md1.find()) {
                String align = md1.group(1).toLowerCase();
                StringBuilder content = new StringBuilder();
                // div 가 한 줄이면 같은 줄 안에서 종료
                String inline = md1.group(2);
                if (inline != null && inline.toLowerCase().contains("</div>")) {
                    content.append(inline.replaceAll("(?i)</div>.*$", ""));
                    i++;
                } else {
                    if (inline != null) content.append(inline);
                    i++;
                    while (i < lines.length && !lines[i].toLowerCase().contains("</div>")) {
                        content.append('\n').append(lines[i]); i++;
                    }
                    if (i < lines.length) {
                        content.append('\n').append(lines[i].replaceAll("(?i)</div>.*$", ""));
                        i++;
                    }
                }
                emitAlignedParagraph(content.toString().trim(), align);
                continue;
            }

            // ----- HTML 헤딩 (예: <h1 align="center">목차</h1>) -----
            Matcher mhh = HTML_HEADING.matcher(line.trim());
            if (mhh.matches()) {
                int level = Integer.parseInt(mhh.group(1));
                String inner = mhh.group(2);
                // align 속성 확인 (간단 추출)
                Matcher mah = HTML_ALIGN_HEADING.matcher(line);
                if (mah.find()) {
                    String al = mah.group(2).toLowerCase();
                    String style = "left".equals(al) ? "Default" : ("right".equals(al) ? "Right" : "Center");
                    // 정렬 헤딩: heading-X 스타일 + 정렬은 별도 단락 스타일 합성 곤란 →
                    // 일반 단락으로 두되 큰 글자 효과를 살리기 위해 span 으로 감쌈
                    body.append("<text:p text:style-name=\"").append(style).append("\">")
                        .append("<text:span text:style-name=\"H").append(level).append("Inline\">")
                        .append(renderInline(inner))
                        .append("</text:span></text:p>\n");
                } else {
                    emitHeading(level, inner);
                }
                i++; continue;
            }

            // ----- 헤딩 -----
            Matcher mh = HEADING.matcher(line);
            if (mh.find()) {
                int level = mh.group(1).length();
                emitHeading(level, mh.group(2));
                i++; continue;
            }

            // ----- GFM 표 -----
            if (looksLikeGfmTableRow(line)
                    && i + 1 < lines.length
                    && looksLikeGfmTableSeparator(lines[i + 1])) {
                List<String> tbl = new ArrayList<>();
                tbl.add(line);
                i += 2; // header + separator 다음부터
                while (i < lines.length && looksLikeGfmTableRow(lines[i])) {
                    tbl.add(lines[i]); i++;
                }
                emitGfmTable(tbl);
                continue;
            }

            // ----- 리스트 -----
            if (line.matches("^\\s*[-*+] .+") || line.matches("^\\s*\\d+\\. .+")) {
                List<String> items = new ArrayList<>();
                boolean ordered = line.matches("^\\s*\\d+\\. .+");
                while (i < lines.length
                        && (lines[i].matches("^\\s*[-*+] .+") || lines[i].matches("^\\s*\\d+\\. .+"))) {
                    items.add(lines[i]); i++;
                }
                emitList(items, ordered);
                continue;
            }

            // ----- 이미지(단독 줄) -----
            Matcher mi = IMG_ONLY.matcher(line.trim());
            if (mi.matches()) {
                emitParagraph(line.trim(), "Default");
                i++; continue;
            }

            // ----- 일반 단락 (연속 줄을 묶음) -----
            StringBuilder para = new StringBuilder(line);
            i++;
            while (i < lines.length
                    && !lines[i].trim().isEmpty()
                    && !HEADING.matcher(lines[i]).find()
                    && !lines[i].startsWith("```")
                    && !looksLikeGfmTableRow(lines[i])
                    && !lines[i].matches("^\\s*[-*+] .+")
                    && !lines[i].matches("^\\s*\\d+\\. .+")
                    && !lines[i].trim().toLowerCase().startsWith("<table")
                    && !ALIGN_DIV_OPEN.matcher(lines[i]).find()) {
                para.append(' ').append(lines[i].trim());
                i++;
            }
            emitParagraph(para.toString(), "Default");
        }

        if (inCode && codeBuf.length() > 0) emitCodeBlock(codeBuf.toString());
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern HTML_HEADING =
            Pattern.compile("(?is)^<h([1-6])[^>]*>(.+?)</h\\1>\\s*$");
    private static final Pattern HTML_ALIGN_HEADING =
            Pattern.compile("(?is)<h([1-6])[^>]*\\salign=\"(center|right|left)\"[^>]*>(.+?)</h\\1>");
    private static final Pattern ALIGN_DIV_OPEN =
            Pattern.compile("(?i)<div\\s+align=\"(center|right|left)\"\\s*>(.*)?");
    private static final Pattern IMG_ONLY =
            Pattern.compile("^!\\[[^\\]]*\\]\\(([^)]+)\\)\\s*$");
    private static final Pattern IMG_INLINE =
            Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final Pattern HTML_IMG =
            Pattern.compile("(?is)<img\\s+[^>]*src=\"([^\"]+)\"[^>]*/?>");
    private static final Pattern BOLD =
            Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC =
            Pattern.compile("(?<!\\*)\\*([^*\\s][^*]*)\\*(?!\\*)");
    private static final Pattern UNDERLINE =
            Pattern.compile("(?i)<u>(.*?)</u>");
    private static final Pattern STRIKE =
            Pattern.compile("~~(.+?)~~");
    private static final Pattern SENTINEL_F =
            Pattern.compile("❮F[CH]#[0-9A-Fa-f]{6}❯|❮/F❯");

    // =========================================================
    //  emit helpers
    // =========================================================

    private void emitHeading(int level, String text) throws IOException {
        body.append("<text:h text:style-name=\"Heading_20_").append(level)
            .append("\" text:outline-level=\"").append(level).append("\">");
        body.append(renderInline(text));
        body.append("</text:h>\n");
    }

    private void emitParagraph(String text, String styleName) throws IOException {
        body.append("<text:p text:style-name=\"").append(styleName).append("\">");
        body.append(renderInline(text));
        body.append("</text:p>\n");
    }

    private void emitAlignedParagraph(String text, String align) throws IOException {
        String style = "left".equals(align) ? "Default"
                     : ("right".equals(align) ? "Right" : "Center");
        // 정렬 안에 여러 줄이 있으면 줄단위로 단락 emit
        String[] parts = text.split("\\n");
        for (String p : parts) {
            if (p.trim().isEmpty()) continue;
            emitParagraph(p.trim(), style);
        }
    }

    private void emitCodeBlock(String code) {
        // 모노스페이스 단락(들). 줄단위로 분리하여 emit.
        for (String line : code.split("\n", -1)) {
            body.append("<text:p text:style-name=\"Code\">")
                .append(xmlEscape(line))
                .append("</text:p>\n");
        }
    }

    private void emitList(List<String> items, boolean ordered) throws IOException {
        body.append(ordered ? "<text:list text:style-name=\"OL\">\n"
                            : "<text:list text:style-name=\"UL\">\n");
        for (String it : items) {
            String content = it.replaceFirst("^\\s*(?:[-*+]|\\d+\\.)\\s+", "");
            body.append("<text:list-item><text:p text:style-name=\"Default\">")
                .append(renderInline(content))
                .append("</text:p></text:list-item>\n");
        }
        body.append("</text:list>\n");
    }

    private void emitGfmTable(List<String> rows) throws IOException {
        // 각 행을 파이프로 split. 앞뒤 빈 셀 제거.
        List<String[]> cells = new ArrayList<>();
        int maxCols = 0;
        for (String r : rows) {
            String t = r.trim();
            if (t.startsWith("|")) t = t.substring(1);
            if (t.endsWith("|"))   t = t.substring(0, t.length() - 1);
            String[] cs = t.split("\\|", -1);
            for (int k = 0; k < cs.length; k++) cs[k] = cs[k].trim();
            cells.add(cs);
            if (cs.length > maxCols) maxCols = cs.length;
        }
        emitTable(cells, maxCols);
    }

    private void emitHtmlTable(String tableHtml) throws IOException {
        // 매우 단순한 HTML 표 파서 — <tr>/<td>/<th> 만 추출, 텍스트 평탄화.
        List<String[]> cells = new ArrayList<>();
        int maxCols = 0;
        Matcher rowM = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(tableHtml);
        while (rowM.find()) {
            List<String> rowCells = new ArrayList<>();
            Matcher cellM = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>").matcher(rowM.group(1));
            while (cellM.find()) {
                String c = cellM.group(1);
                // 셀 안의 <br>는 줄바꿈으로
                c = c.replaceAll("(?i)<br\\s*/?>", "\n");
                // 셀 안의 다른 태그는 전부 제거 (텍스트만)
                c = c.replaceAll("(?is)<[^>]+>", "");
                c = unescapeHtml(c).trim();
                rowCells.add(c);
            }
            if (!rowCells.isEmpty()) {
                cells.add(rowCells.toArray(new String[0]));
                if (rowCells.size() > maxCols) maxCols = rowCells.size();
            }
        }
        if (!cells.isEmpty()) emitTable(cells, maxCols);
    }

    private void emitTable(List<String[]> cells, int maxCols) throws IOException {
        if (maxCols == 0) return;
        body.append("<table:table table:style-name=\"Table\">\n");
        body.append("<table:table-column table:number-columns-repeated=\"")
            .append(maxCols).append("\"/>\n");
        for (String[] row : cells) {
            body.append("<table:table-row>\n");
            for (int c = 0; c < maxCols; c++) {
                String txt = c < row.length ? row[c] : "";
                body.append("<table:table-cell office:value-type=\"string\">");
                if (txt.isEmpty()) {
                    body.append("<text:p text:style-name=\"Default\"/>");
                } else {
                    // 셀 안에 여러 줄이 있을 수 있음
                    for (String p : txt.split("\\n")) {
                        body.append("<text:p text:style-name=\"Default\">")
                            .append(renderInline(p)).append("</text:p>");
                    }
                }
                body.append("</table:table-cell>\n");
            }
            body.append("</table:table-row>\n");
        }
        body.append("</table:table>\n");
    }

    // =========================================================
    //  인라인 렌더 (이미지·강조·sentinel)
    // =========================================================

    private String renderInline(String text) throws IOException {
        if (text == null) return "";
        // 1) sentinel 제거 (색상/하이라이트 마커 — 우선 단순 제거)
        text = SENTINEL_F.matcher(text).replaceAll("");
        // 2) 이미지 토큰 추출 → 자리표시자
        Map<String, String> placeholders = new LinkedHashMap<>();
        Matcher mi = IMG_INLINE.matcher(text);
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (mi.find()) {
            String alt = mi.group(1);
            String url = mi.group(2);
            String picName = registerImage(alt, url);
            String key = "IMG" + (idx++) + "";
            placeholders.put(key, renderImageFrame(alt, picName));
            mi.appendReplacement(sb, Matcher.quoteReplacement(key));
        }
        mi.appendTail(sb);
        text = sb.toString();

        // 2-b) HTML <img src="..."> 추출 (escape 전에 잡아야 함)
        {
            Matcher mi2 = HTML_IMG.matcher(text);
            StringBuffer sb2 = new StringBuffer();
            while (mi2.find()) {
                String url = mi2.group(1);
                String picName = registerImage("", url);
                String key = "\u0001HIMG" + (idx++) + "\u0001";
                placeholders.put(key, renderImageFrame("", picName));
                mi2.appendReplacement(sb2, Matcher.quoteReplacement(key));
            }
            mi2.appendTail(sb2);
            text = sb2.toString();
        }

        // 3) XML escape 먼저
        text = xmlEscape(text);

        // 4) 강조 마크업 → span (escape 후라서 < > 가 &lt;&gt;로 바뀐 것에 주의)
        //    <u>...</u> 는 escape 되어 &lt;u&gt;...&lt;/u&gt; 가 되므로 별도 처리
        text = text.replaceAll("(?i)&lt;u&gt;(.*?)&lt;/u&gt;",
                "<text:span text:style-name=\"Underline\">$1</text:span>");
        text = text.replaceAll("(?i)&lt;strong&gt;(.*?)&lt;/strong&gt;",
                "<text:span text:style-name=\"Bold\">$1</text:span>");
        text = text.replaceAll("(?i)&lt;b&gt;(.*?)&lt;/b&gt;",
                "<text:span text:style-name=\"Bold\">$1</text:span>");
        text = text.replaceAll("(?i)&lt;em&gt;(.*?)&lt;/em&gt;",
                "<text:span text:style-name=\"Italic\">$1</text:span>");
        text = text.replaceAll("(?i)&lt;i&gt;(.*?)&lt;/i&gt;",
                "<text:span text:style-name=\"Italic\">$1</text:span>");
        text = text.replaceAll("(?i)&lt;s&gt;(.*?)&lt;/s&gt;",
                "<text:span text:style-name=\"Strike\">$1</text:span>");
        text = text.replaceAll("(?i)&lt;strike&gt;(.*?)&lt;/strike&gt;",
                "<text:span text:style-name=\"Strike\">$1</text:span>");
        text = text.replaceAll("(?i)&lt;del&gt;(.*?)&lt;/del&gt;",
                "<text:span text:style-name=\"Strike\">$1</text:span>");
        text = text.replaceAll("(?i)&lt;sup&gt;(.*?)&lt;/sup&gt;",
                "<text:span text:style-name=\"Sup\">$1</text:span>");
        text = text.replaceAll("(?i)&lt;sub&gt;(.*?)&lt;/sub&gt;",
                "<text:span text:style-name=\"Sub\">$1</text:span>");
        text = text.replaceAll("(?i)&lt;mark&gt;(.*?)&lt;/mark&gt;",
                "<text:span text:style-name=\"Mark\">$1</text:span>");
        text = text.replaceAll("(?i)&lt;br\\s*/?&gt;", "<text:line-break/>");
        text = STRIKE.matcher(text).replaceAll(
                "<text:span text:style-name=\"Strike\">$1</text:span>");
        text = BOLD.matcher(text).replaceAll(
                "<text:span text:style-name=\"Bold\">$1</text:span>");
        text = ITALIC.matcher(text).replaceAll(
                "<text:span text:style-name=\"Italic\">$1</text:span>");

        // 잔존 span/div/p 래퍼 태그 (속성 포함) 제거 - 내용은 보존
        // attribute value 안의 &quot; / &amp; 를 허용하기 위해 lazy match 사용
        text = text.replaceAll("(?is)&lt;span\\b.*?&gt;", "");
        text = text.replaceAll("(?is)&lt;/span&gt;", "");
        text = text.replaceAll("(?is)&lt;div\\b.*?&gt;", "");
        text = text.replaceAll("(?is)&lt;/div&gt;", "");
        text = text.replaceAll("(?is)&lt;p\\b.*?&gt;", "");
        text = text.replaceAll("(?is)&lt;/p&gt;", "");
        // 표 관련 escape 잔여 태그 제거 (nested HTML 케이스)
        text = text.replaceAll("(?is)&lt;table\\b.*?&gt;", "");
        text = text.replaceAll("(?is)&lt;/table&gt;", "");
        text = text.replaceAll("(?is)&lt;thead\\b.*?&gt;", "");
        text = text.replaceAll("(?is)&lt;/thead&gt;", "");
        text = text.replaceAll("(?is)&lt;tbody\\b.*?&gt;", "");
        text = text.replaceAll("(?is)&lt;/tbody&gt;", "");
        text = text.replaceAll("(?is)&lt;tr\\b.*?&gt;", "");
        text = text.replaceAll("(?is)&lt;/tr&gt;", "");
        text = text.replaceAll("(?is)&lt;t[dh]\\b.*?&gt;", "");
        text = text.replaceAll("(?is)&lt;/t[dh]&gt;", "");

        // 5) 이미지 자리표시자 복원
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            text = text.replace(e.getKey(), e.getValue());
        }
        return text;
    }

    private String renderImageFrame(String alt, String picName) {
        // 인라인 이미지 frame. 크기는 anchor=as-char 로 표준 inch 단위 사용.
        return "<draw:frame draw:name=\"" + xmlEscape(picName)
                + "\" text:anchor-type=\"as-char\""
                + " svg:width=\"3in\" svg:height=\"2in\">"
                + "<draw:image xlink:href=\"Pictures/" + xmlEscape(picName)
                + "\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/>"
                + "</draw:frame>";
    }

    /** 이미지 URL을 디코드/로드해서 Pictures/ 엔트리로 등록하고 entry 이름 반환. */
    private String registerImage(String alt, String url) throws IOException {
        byte[] bytes;
        String ext = "png";
        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            if (comma < 0) return registerPlaceholder();
            String meta = url.substring(5, comma); // image/png;base64
            int slash = meta.indexOf('/');
            int semi  = meta.indexOf(';');
            if (slash > 0) {
                String e = (semi > 0 ? meta.substring(slash + 1, semi)
                                      : meta.substring(slash + 1)).toLowerCase();
                if (!e.isEmpty()) ext = e;
            }
            bytes = Base64.getDecoder().decode(url.substring(comma + 1));
        } else if (url.startsWith("file:")) {
            try {
                Path p = Paths.get(URI.create(url));
                bytes = Files.readAllBytes(p);
                ext = guessExt(p.getFileName().toString(), ext);
            } catch (Exception e) {
                return registerPlaceholder();
            }
        } else {
            // 상대/절대 파일 경로로 추정
            File f = new File(url);
            if (!f.isFile()) return registerPlaceholder();
            bytes = Files.readAllBytes(f.toPath());
            ext = guessExt(f.getName(), ext);
        }
        String name = "image" + (++pictureCounter) + "." + ext;
        pictures.put(name, bytes);
        return name;
    }

    /** 이미지 로드 실패 시 1×1 투명 PNG placeholder 를 등록. */
    private String registerPlaceholder() {
        // 1x1 투명 PNG (89byte) — 외부 의존성 없이 사용.
        byte[] tinyPng = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");
        String name = "image" + (++pictureCounter) + ".png";
        pictures.put(name, tinyPng);
        return name;
    }

    private static String guessExt(String fname, String fallback) {
        int dot = fname.lastIndexOf('.');
        if (dot >= 0 && dot < fname.length() - 1) return fname.substring(dot + 1).toLowerCase();
        return fallback;
    }

    // =========================================================
    //  ODT ZIP 패키지 작성
    // =========================================================

    private void writeOdtPackage(Path odt) throws IOException {
        String content = buildContentXml();
        String styles  = buildStylesXml();
        String mani    = buildManifestXml();

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(odt))) {
            // mimetype : ODT 규격상 첫 entry, STORED(압축 X)
            byte[] mime = "application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.US_ASCII);
            ZipEntry me = new ZipEntry("mimetype");
            me.setMethod(ZipEntry.STORED);
            me.setSize(mime.length);
            me.setCompressedSize(mime.length);
            CRC32 crc = new CRC32(); crc.update(mime); me.setCrc(crc.getValue());
            zos.putNextEntry(me); zos.write(mime); zos.closeEntry();

            putDeflated(zos, "content.xml", content.getBytes(StandardCharsets.UTF_8));
            putDeflated(zos, "styles.xml",  styles.getBytes(StandardCharsets.UTF_8));
            putDeflated(zos, "META-INF/manifest.xml", mani.getBytes(StandardCharsets.UTF_8));

            for (Map.Entry<String, byte[]> e : pictures.entrySet()) {
                putDeflated(zos, "Pictures/" + e.getKey(), e.getValue());
            }
        }
    }

    private static void putDeflated(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry ze = new ZipEntry(name);
        ze.setMethod(ZipEntry.DEFLATED);
        zos.putNextEntry(ze);
        zos.write(data);
        zos.closeEntry();
    }

    private String buildContentXml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<office:document-content")
          .append(" xmlns:office=\"").append(NS_OFFICE).append("\"")
          .append(" xmlns:style=\"").append(NS_STYLE).append("\"")
          .append(" xmlns:text=\"").append(NS_TEXT).append("\"")
          .append(" xmlns:table=\"").append(NS_TABLE).append("\"")
          .append(" xmlns:draw=\"").append(NS_DRAW).append("\"")
          .append(" xmlns:fo=\"").append(NS_FO).append("\"")
          .append(" xmlns:xlink=\"").append(NS_XLINK).append("\"")
          .append(" xmlns:svg=\"").append(NS_SVG).append("\"")
          .append(" office:version=\"1.2\">\n");
        sb.append("<office:automatic-styles>\n");
        // 정렬용 단락 스타일
        sb.append("<style:style style:name=\"Center\" style:family=\"paragraph\" style:parent-style-name=\"Default\">")
          .append("<style:paragraph-properties fo:text-align=\"center\"/></style:style>\n");
        sb.append("<style:style style:name=\"Right\" style:family=\"paragraph\" style:parent-style-name=\"Default\">")
          .append("<style:paragraph-properties fo:text-align=\"end\"/></style:style>\n");
        sb.append("<style:style style:name=\"Code\" style:family=\"paragraph\" style:parent-style-name=\"Default\">")
          .append("<style:text-properties style:font-name=\"Courier New\" fo:font-size=\"10pt\"/></style:style>\n");
        // 표 스타일
        sb.append("<style:style style:name=\"Table\" style:family=\"table\">")
          .append("<style:table-properties table:align=\"margins\" fo:margin-left=\"0in\" style:width=\"6.5in\"/></style:style>\n");
        sb.append("</office:automatic-styles>\n");

        sb.append("<office:body><office:text>\n");
        sb.append(body);
        sb.append("</office:text></office:body>\n");
        sb.append("</office:document-content>\n");
        return sb.toString();
    }

    private String buildStylesXml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<office:document-styles")
          .append(" xmlns:office=\"").append(NS_OFFICE).append("\"")
          .append(" xmlns:style=\"").append(NS_STYLE).append("\"")
          .append(" xmlns:text=\"").append(NS_TEXT).append("\"")
          .append(" xmlns:fo=\"").append(NS_FO).append("\"")
          .append(" office:version=\"1.2\">\n");
        sb.append("<office:styles>\n");
        // 기본 단락 스타일
        sb.append("<style:default-style style:family=\"paragraph\">")
          .append("<style:paragraph-properties fo:text-align=\"start\"/>")
          .append("<style:text-properties fo:font-size=\"11pt\" fo:language=\"ko\" fo:country=\"KR\"/></style:default-style>\n");
        sb.append("<style:style style:name=\"Default\" style:family=\"paragraph\"/>\n");
        // 헤딩 (level 1~6)
        int[] sizes = {22, 18, 16, 14, 12, 11};
        for (int lv = 1; lv <= 6; lv++) {
            sb.append("<style:style style:name=\"Heading_20_").append(lv)
              .append("\" style:display-name=\"Heading ").append(lv)
              .append("\" style:family=\"paragraph\" style:parent-style-name=\"Default\">")
              .append("<style:paragraph-properties fo:margin-top=\"0.16in\" fo:margin-bottom=\"0.08in\"/>")
              .append("<style:text-properties fo:font-size=\"").append(sizes[lv - 1])
              .append("pt\" fo:font-weight=\"bold\"/></style:style>\n");
        }
        // 강조 span 스타일
        sb.append("<style:style style:name=\"Bold\" style:family=\"text\">")
          .append("<style:text-properties fo:font-weight=\"bold\"/></style:style>\n");
        sb.append("<style:style style:name=\"Italic\" style:family=\"text\">")
          .append("<style:text-properties fo:font-style=\"italic\"/></style:style>\n");
        sb.append("<style:style style:name=\"Underline\" style:family=\"text\">")
          .append("<style:text-properties style:text-underline-style=\"solid\" style:text-underline-width=\"auto\" style:text-underline-color=\"font-color\"/></style:style>\n");
        sb.append("<style:style style:name=\"Strike\" style:family=\"text\">")
          .append("<style:text-properties style:text-line-through-style=\"solid\"/></style:style>\n");
        sb.append("<style:style style:name=\"Sup\" style:family=\"text\">")
          .append("<style:text-properties style:text-position=\"super 58%\"/></style:style>\n");
        sb.append("<style:style style:name=\"Sub\" style:family=\"text\">")
          .append("<style:text-properties style:text-position=\"sub 58%\"/></style:style>\n");
        sb.append("<style:style style:name=\"Mark\" style:family=\"text\">")
          .append("<style:text-properties fo:background-color=\"#FFFF00\"/></style:style>\n");
        // H1~H6 inline 스타일 (정렬 속성 있는 HTML heading 처리용)
        int[] hSizes = {22, 18, 16, 14, 12, 11};
        for (int lv = 1; lv <= 6; lv++) {
            sb.append("<style:style style:name=\"H").append(lv).append("Inline\" style:family=\"text\">")
              .append("<style:text-properties fo:font-size=\"").append(hSizes[lv - 1])
              .append("pt\" fo:font-weight=\"bold\"/></style:style>\n");
        }
        sb.append("</office:styles>\n");
        sb.append("</office:document-styles>\n");
        return sb.toString();
    }

    private String buildManifestXml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<manifest:manifest xmlns:manifest=\"").append(NS_MANI).append("\" manifest:version=\"1.2\">\n");
        sb.append("<manifest:file-entry manifest:full-path=\"/\" manifest:version=\"1.2\"")
          .append(" manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>\n");
        sb.append("<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>\n");
        sb.append("<manifest:file-entry manifest:full-path=\"styles.xml\"  manifest:media-type=\"text/xml\"/>\n");
        for (String n : pictures.keySet()) {
            sb.append("<manifest:file-entry manifest:full-path=\"Pictures/").append(xmlEscape(n))
              .append("\" manifest:media-type=\"").append(mimeOf(n)).append("\"/>\n");
        }
        sb.append("</manifest:manifest>\n");
        return sb.toString();
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png"))  return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif"))  return "image/gif";
        if (n.endsWith(".bmp"))  return "image/bmp";
        if (n.endsWith(".svg"))  return "image/svg+xml";
        return "application/octet-stream";
    }

    private static boolean looksLikeGfmTableRow(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.startsWith("|") && t.endsWith("|") && t.length() >= 3;
    }

    private static boolean looksLikeGfmTableSeparator(String s) {
        if (!looksLikeGfmTableRow(s)) return false;
        // | --- | :---: | --- | …
        String t = s.trim();
        return t.replace(":", "").matches("\\|\\s*-{3,}\\s*(\\|\\s*-{3,}\\s*)+\\|");
    }

    private static String xmlEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '&':  sb.append("&amp;");  break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    if (c == '\t' || c == '\n' || c == '\r' || c == '\u0001' || c >= 0x20) sb.append(c);
                    // 그 외 제어문자는 drop (ODT XML 1.0 호환)
            }
        }
        return sb.toString();
    }

    private static String unescapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }
}
