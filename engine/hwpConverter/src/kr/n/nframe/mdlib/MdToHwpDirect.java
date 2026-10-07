package kr.n.nframe.mdlib;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.lineseg.LineSegItem;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * v14.10: MD → HWP 직접 변환 (시각 풍부 + 표/이미지/이모지 지원).
 *
 * <h3>v14.10 변경</h3>
 * <ul>
 *   <li>HTML 표 (&lt;table&gt;...&lt;/table&gt;) 파싱 추가 — 외부 MD 의 표 76개 인식</li>
 *   <li>멀티라인 이미지 마크다운 (![alt 여러줄](path)) 파싱</li>
 *   <li>Heading 시각 강조 — Unicode 밑줄 라인 추가 (═══, ───)</li>
 *   <li>Table 시각 렌더 — Unicode box drawing characters (┌─┬─┐ │ │ └─┴─┘)</li>
 *   <li>Image 플레이스홀더 — [이미지: filename (가로×세로)]</li>
 *   <li>Emoji / Unicode 심볼 (→ ※ ① ② etc) — 네이티브 UTF-16LE 보존</li>
 *   <li>List 항목의 들여쓰기 prefix 보존</li>
 * </ul>
 *
 * <h3>v14.9 핵심 (유지)</h3>
 * hwplib BlankFileMaker 의 helper tool 버그(EmptyParagraphAdder 가 SectionDefine 중복 작성,
 * ParaTextSetter 가 PARA_CHAR_SHAPE 를 size=0 으로 작성) 우회. paragraph 직접 구성.
 *
 * <h3>v14.8 핵심 (유지)</h3>
 * 한/글 정상 .hwp(case1_8p.hwp) 를 OLE2 템플릿으로 사용. 템플릿의 FileHeader/DocInfo
 * /메타 스트림은 byte-for-byte 그대로 두고, Section0 만 hwplib 가 생성한 컨텐츠로 교체.
 */
public class MdToHwpDirect {

    /** MD 파일 경로 (이미지 상대경로 해석에 사용) */
    private Path mdParentDir;

    public void convert(String filePathMd, String filePathHwp) throws Exception {
        System.out.println("[MdToHwpDirect] 변환 시작 (시각 풍부 + 표/이미지/이모지)");
        System.out.println("[MdToHwpDirect] Input : " + filePathMd);
        System.out.println("[MdToHwpDirect] Output: " + filePathHwp);

        Path mdPath = Paths.get(filePathMd).toAbsolutePath();
        this.mdParentDir = mdPath.getParent();

        byte[] mdBytes = Files.readAllBytes(mdPath);
        String mdText = new String(mdBytes, StandardCharsets.UTF_8);

        List<Block> blocks = parseMarkdown(mdText);
        int hCount = 0, pCount = 0, tCount = 0, iCount = 0, bCount = 0;
        for (Block b : blocks) {
            if (b instanceof Heading) hCount++;
            else if (b instanceof MdParagraph) pCount++;
            else if (b instanceof Table) tCount++;
            else if (b instanceof Image) iCount++;
            else if (b instanceof Blank) bCount++;
        }
        System.out.println("[MdToHwpDirect] 파싱 블록: total=" + blocks.size()
                + " (heading=" + hCount + ", paragraph=" + pCount
                + ", table=" + tCount + ", image=" + iCount + ", blank=" + bCount + ")");

        // 1) hwplib 으로 Section0 바이트 생성을 위해 임시 HWPFile 빌드
        HWPFile hwp = BlankFileMaker.make();
        Section sec = hwp.getBodyText().getLastSection();

        int totalChars = 0;
        int addedParas = 0;
        for (Block b : blocks) {
            List<String> lines = renderBlock(b);
            for (String text : lines) {
                if (text.length() > 4000) {
                    int n = (int) Math.ceil(text.length() / 4000.0);
                    for (int i = 0; i < n; i++) {
                        int from = i * 4000;
                        int to = Math.min(from + 4000, text.length());
                        addParagraph(sec, text.substring(from, to));
                        addedParas++;
                    }
                    totalChars += text.length();
                } else {
                    addParagraph(sec, text);
                    addedParas++;
                    totalChars += text.length();
                }
            }
        }
        System.out.println("[MdToHwpDirect] 생성 paragraph: " + addedParas
                + " (전체 section: " + sec.getParagraphCount() + ")"
                + ", 총 텍스트: " + totalChars + " chars");

        // 2) hwplib HWPWriter 로 임시 .hwp 작성 → Section0 바이트 추출
        Path tmpHwp = Files.createTempFile("hwp-section-", ".hwp");
        try {
            HWPWriter.toFile(hwp, tmpHwp.toString());
            byte[] section0Bytes;
            try (POIFSFileSystem poi = new POIFSFileSystem(new FileInputStream(tmpHwp.toFile()))) {
                DirectoryEntry bodyText = (DirectoryEntry) poi.getRoot().getEntry("BodyText");
                section0Bytes = readEntryBytes(bodyText, "Section0");
            }
            System.out.println("[MdToHwpDirect] Section0 압축 바이트: " + section0Bytes.length);

            // 3) 한/글 정상 .hwp 템플릿 → Section0 만 교체
            byte[] templateBytes = loadResource("hwp-streams/template.hwp");
            Path outPath = Paths.get(filePathHwp).toAbsolutePath();
            Files.createDirectories(outPath.getParent());
            try (POIFSFileSystem poi = new POIFSFileSystem(new ByteArrayInputStream(templateBytes))) {
                DirectoryEntry bodyText = (DirectoryEntry) poi.getRoot().getEntry("BodyText");
                for (org.apache.poi.poifs.filesystem.Entry e : bodyText) {
                    if (e.getName().equals("Section0")) { e.delete(); break; }
                }
                bodyText.createDocument("Section0", new ByteArrayInputStream(section0Bytes));
                List<String> toDelete = new ArrayList<>();
                for (org.apache.poi.poifs.filesystem.Entry e : bodyText) {
                    if (e.getName().startsWith("Section") && !e.getName().equals("Section0")) {
                        toDelete.add(e.getName());
                    }
                }
                for (String n : toDelete) {
                    for (org.apache.poi.poifs.filesystem.Entry e : bodyText) {
                        if (e.getName().equals(n)) { e.delete(); break; }
                    }
                }
                try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                    poi.writeFilesystem(fos);
                }
            }
            long finalSize = new java.io.File(outPath.toString()).length();
            System.out.println("[MdToHwpDirect] 최종 .hwp 저장 완료: " + outPath + " (" + finalSize + " bytes)");
        } finally {
            try { Files.deleteIfExists(tmpHwp); } catch (Exception ignored) {}
        }
    }

    // =========================================================
    //  Paragraph 직접 구성 (hwplib helper 버그 우회)
    // =========================================================

    private static void addParagraph(Section sec, String text) throws Exception {
        Paragraph p = sec.addNewParagraph();
        int charCount = (text == null) ? 0 : text.length();
        p.getHeader().setCharacterCount(charCount);
        p.getHeader().setParaShapeId(0);
        p.getHeader().setStyleId((short) 0);
        p.getHeader().setCharShapeCount(1);
        p.getHeader().setLineAlignCount(1);
        p.getHeader().setRangeTagCount(0);
        p.getHeader().setLastInList(false);
        p.getHeader().setInstanceID(0);
        p.getHeader().setIsMergedByTrack(0);
        if (text != null && !text.isEmpty()) {
            p.createText();
            p.getText().addString(text);
        }
        p.createCharShape();
        p.getCharShape().addParaCharShape(0, 0);
        p.createLineSeg();
        LineSegItem seg = p.getLineSeg().addNewLineSegItem();
        seg.setTextStartPosition(0);
        seg.setLineVerticalPosition(0);
        seg.setLineHeight(1000);
        seg.setTextPartHeight(1000);
        seg.setDistanceBaseLineToLineVerticalPosition(800);
        seg.setLineSpace(600);
        seg.setStartPositionFromColumn(0);
        seg.setSegmentWidth(41954);
    }

    /** OLE2 디렉터리에서 지정 이름 스트림의 바이트를 통째로 읽음. */
    private static byte[] readEntryBytes(DirectoryEntry dir, String name) throws Exception {
        DocumentEntry de = (DocumentEntry) dir.getEntry(name);
        byte[] data = new byte[de.getSize()];
        try (DocumentInputStream is = new DocumentInputStream(de)) {
            int t = 0;
            while (t < data.length) {
                int r = is.read(data, t, data.length - t);
                if (r < 0) break;
                t += r;
            }
        }
        return data;
    }

    private static byte[] loadResource(String relPath) throws java.io.IOException {
        String full = "/kr/n/nframe/resources/" + relPath;
        try (InputStream in = MdToHwpDirect.class.getResourceAsStream(full)) {
            if (in == null) throw new java.io.IOException("Resource missing: " + full);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    // =========================================================
    //  Block → 텍스트 라인 (paragraph 단위 분리)
    // =========================================================

    /** 블록을 paragraph 단위 텍스트 라인 list 로 변환. 빈 paragraph 도 별도 라인으로. */
    private List<String> renderBlock(Block b) {
        List<String> out = new ArrayList<>();
        if (b instanceof Heading) {
            Heading h = (Heading) b;
            // 시각적 강조: 큰 # 으로 시작 + 다음 paragraph 에 underline 라인
            String prefix = repeat("#", h.level);
            out.add(prefix + " " + h.text);
            // H1, H2 만 underline 추가 (가독성)
            if (h.level == 1) {
                out.add(repeat("═", visualWidth(h.text) + h.level + 1));
            } else if (h.level == 2) {
                out.add(repeat("─", visualWidth(h.text) + h.level + 1));
            }
        } else if (b instanceof MdParagraph) {
            out.add(((MdParagraph) b).text);
        } else if (b instanceof Table) {
            out.addAll(renderTable((Table) b));
        } else if (b instanceof Image) {
            Image img = (Image) b;
            StringBuilder sb = new StringBuilder("[이미지: ");
            sb.append(img.path);
            if (img.alt != null && !img.alt.isEmpty()) sb.append(" — ").append(img.alt);
            sb.append("]");
            out.add(sb.toString());
        } else if (b instanceof Blank) {
            out.add("");
        }
        return out;
    }

    /** 표를 Unicode box drawing 으로 paragraph 라인 list 화. */
    private static List<String> renderTable(Table t) {
        List<String> out = new ArrayList<>();
        if (t.rows.isEmpty()) return out;
        int colCount = 0;
        for (List<String> row : t.rows) colCount = Math.max(colCount, row.size());
        // 컬럼별 최대 visual width 계산 (한글 = 2폭)
        int[] widths = new int[colCount];
        for (List<String> row : t.rows) {
            for (int c = 0; c < row.size(); c++) {
                widths[c] = Math.max(widths[c], visualWidth(row.get(c)));
            }
        }
        for (int c = 0; c < colCount; c++) widths[c] = Math.max(widths[c], 2);

        // 상단 보더
        out.add(buildBorderLine("┌", "┬", "┐", widths));
        // 첫 행 (header)
        out.add(buildContentLine(t.rows.get(0), widths));
        if (t.rows.size() > 1) {
            // header 와 본문 사이 구분선
            out.add(buildBorderLine("├", "┼", "┤", widths));
        }
        for (int ri = 1; ri < t.rows.size(); ri++) {
            out.add(buildContentLine(t.rows.get(ri), widths));
        }
        out.add(buildBorderLine("└", "┴", "┘", widths));
        return out;
    }

    private static String buildBorderLine(String left, String mid, String right, int[] widths) {
        StringBuilder sb = new StringBuilder(left);
        for (int c = 0; c < widths.length; c++) {
            sb.append(repeat("─", widths[c] + 2));
            sb.append(c == widths.length - 1 ? right : mid);
        }
        return sb.toString();
    }

    private static String buildContentLine(List<String> row, int[] widths) {
        StringBuilder sb = new StringBuilder("│");
        for (int c = 0; c < widths.length; c++) {
            String cell = c < row.size() ? row.get(c) : "";
            sb.append(' ').append(cell);
            int pad = widths[c] - visualWidth(cell);
            for (int i = 0; i < pad; i++) sb.append(' ');
            sb.append(" │");
        }
        return sb.toString();
    }

    private static int visualWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                w += 2; i++; // surrogate pair = 2 width (likely emoji)
            } else if (c >= 0x1100 && (c <= 0x115F || (c >= 0x2E80 && c <= 0x9FFF)
                    || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xF900 && c <= 0xFAFF)
                    || (c >= 0xFE30 && c <= 0xFE4F) || (c >= 0xFF00 && c <= 0xFF60)
                    || (c >= 0xFFE0 && c <= 0xFFE6))) {
                w += 2; // CJK ideograph or fullwidth
            } else {
                w += 1;
            }
        }
        return w;
    }

    private static String repeat(String s, int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    // =========================================================
    //  MD 파서 (HTML 표 + 멀티라인 이미지 + 헤딩 + 일반 문단 + 리스트)
    // =========================================================

    static abstract class Block {}
    static class Heading extends Block {
        int level; String text;
        Heading(int lv, String t) { level = lv; text = t; }
    }
    static class MdParagraph extends Block {
        String text;
        MdParagraph(String t) { text = t; }
    }
    static class Table extends Block {
        List<List<String>> rows = new ArrayList<>();
    }
    static class Image extends Block {
        String alt;
        String path;
        Image(String a, String p) { alt = a; path = p; }
    }
    static class Blank extends Block {}

    private static final Pattern HEADING_RE = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern PIPE_DIV_RE = Pattern.compile("\\|?\\s*:?-{3,}:?(\\s*\\|\\s*:?-{3,}:?)*\\|?");
    /** 멀티라인 이미지: ![ANYTHING](path) 가 여러 줄에 걸쳐 있을 때. */
    private static final Pattern MULTILINE_IMAGE_RE = Pattern.compile(
            "!\\[(?<alt>[^\\]]*?)\\]\\((?<path>[^)\\s]+(?:\\s[^)]*)?)\\)", Pattern.DOTALL);

    private List<Block> parseMarkdown(String text) {
        // HTML 주석 제거
        text = text.replaceAll("(?s)<!--.*?-->", "");

        // 멀티라인 이미지 마크다운을 placeholder 로 치환 (line-based 파싱 전에)
        // [[IMAGE_N]] 마커로 치환 후, 별도 list 에 (alt, path) 보관 → 나중에 Image 블록으로 인식
        List<String[]> imageRefs = new ArrayList<>();
        StringBuilder before = new StringBuilder(text);
        Matcher imgMatcher = MULTILINE_IMAGE_RE.matcher(before.toString());
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (imgMatcher.find()) {
            String alt = imgMatcher.group("alt");
            String path = imgMatcher.group("path").replaceAll("\\s+", " ").trim();
            // alt 의 개행 공백 정리
            alt = alt.replaceAll("\\s+", " ").trim();
            imageRefs.add(new String[]{alt, path});
            imgMatcher.appendReplacement(sb,
                    Matcher.quoteReplacement("\n[[IMG#" + idx + "]]\n"));
            idx++;
        }
        imgMatcher.appendTail(sb);
        text = sb.toString();

        String[] lines = text.split("\\r?\\n", -1);
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                blocks.add(new Blank());
                i++; continue;
            }

            // 이미지 placeholder
            Matcher imgM = Pattern.compile("^\\[\\[IMG#(\\d+)\\]\\]$").matcher(trimmed);
            if (imgM.matches()) {
                int ix = Integer.parseInt(imgM.group(1));
                if (ix < imageRefs.size()) {
                    String[] ref = imageRefs.get(ix);
                    blocks.add(new Image(ref[0], ref[1]));
                }
                i++; continue;
            }

            // ATX heading
            Matcher hm = HEADING_RE.matcher(trimmed);
            if (hm.matches()) {
                blocks.add(new Heading(hm.group(1).length(), stripInlineMarkup(hm.group(2))));
                i++; continue;
            }

            // Setext heading
            if (i + 1 < lines.length) {
                String next = lines[i + 1].trim();
                if (!next.isEmpty() && next.matches("=+")) {
                    blocks.add(new Heading(1, stripInlineMarkup(trimmed))); i += 2; continue;
                }
                if (!next.isEmpty() && next.matches("-{3,}")) {
                    blocks.add(new Heading(2, stripInlineMarkup(trimmed))); i += 2; continue;
                }
            }

            // 수평선
            if (trimmed.matches("-{3,}|\\*{3,}|_{3,}")) {
                blocks.add(new Blank()); i++; continue;
            }

            // GFM 파이프 표
            if (trimmed.startsWith("|") && i + 1 < lines.length) {
                String next = lines[i + 1].trim();
                if (PIPE_DIV_RE.matcher(next).matches()) {
                    Table t = new Table();
                    t.rows.add(splitPipeRow(trimmed));
                    i += 2;
                    while (i < lines.length) {
                        String row = lines[i].trim();
                        if (!row.startsWith("|")) break;
                        t.rows.add(splitPipeRow(row));
                        i++;
                    }
                    blocks.add(t);
                    continue;
                }
            }

            // HTML 표 <table> ... </table>
            if (trimmed.toLowerCase().startsWith("<table")) {
                StringBuilder tableBuf = new StringBuilder(line).append('\n');
                int depth = 1;
                i++;
                while (i < lines.length && depth > 0) {
                    String l = lines[i];
                    String lLow = l.toLowerCase();
                    int opens = countOccurrences(lLow, "<table");
                    int closes = countOccurrences(lLow, "</table");
                    depth += opens - closes;
                    tableBuf.append(l).append('\n');
                    i++;
                    if (depth <= 0) break;
                }
                Table t = parseHtmlTable(tableBuf.toString());
                if (!t.rows.isEmpty()) blocks.add(t);
                continue;
            }

            // 코드 블록
            if (trimmed.startsWith("```")) {
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                if (i < lines.length) i++;
                if (code.length() > 0) {
                    // 각 라인을 별도 paragraph 로 (들여쓰기 4 space)
                    for (String cl : code.toString().split("\\n", -1)) {
                        if (!cl.isEmpty()) blocks.add(new MdParagraph("    " + cl));
                    }
                }
                continue;
            }

            // 그 외 HTML 라인 (개별 태그) 은 plain 화
            if (trimmed.startsWith("<")) {
                String plain = stripHtmlTags(line);
                if (!plain.trim().isEmpty()) {
                    blocks.add(new MdParagraph(stripInlineMarkup(plain.trim())));
                }
                i++; continue;
            }

            // 일반 문단 (들여쓰기 prefix 보존을 위해 single line 단위로 처리)
            // 한 줄을 그대로 paragraph 로 — 리스트 마커 (-, *, 1.) 가 보존됨
            blocks.add(new MdParagraph(stripInlineMarkup(line)));
            i++;
        }
        return blocks;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) { count++; idx += needle.length(); }
        return count;
    }

    /** &lt;table&gt; ... &lt;/table&gt; 블록을 Table block 으로 파싱. */
    private static Table parseHtmlTable(String html) {
        Table t = new Table();
        // <tr> 단위로 분리
        Pattern trPat = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher trM = trPat.matcher(html);
        Pattern cellPat = Pattern.compile("<(t[hd])[^>]*>(.*?)</\\1>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        while (trM.find()) {
            String trContent = trM.group(1);
            List<String> row = new ArrayList<>();
            Matcher cM = cellPat.matcher(trContent);
            while (cM.find()) {
                String cellHtml = cM.group(2);
                // <br> 를 줄바꿈으로 (셀 안 줄바꿈은 공백으로 표시)
                cellHtml = cellHtml.replaceAll("(?i)<br\\s*/?\\s*>", " / ");
                String plain = stripHtmlTags(cellHtml).trim();
                row.add(stripInlineMarkup(plain));
            }
            if (!row.isEmpty()) t.rows.add(row);
        }
        return t;
    }

    private static List<String> splitPipeRow(String line) {
        if (line.startsWith("|")) line = line.substring(1);
        if (line.endsWith("|")) line = line.substring(0, line.length() - 1);
        String[] cells = line.split("\\|", -1);
        List<String> out = new ArrayList<>();
        for (String c : cells) out.add(stripInlineMarkup(c.trim()));
        return out;
    }

    private static String stripInlineMarkup(String s) {
        if (s == null || s.isEmpty()) return "";
        // 단일 라인 이미지 마크다운은 alt 만 (멀티라인은 placeholder 로 이미 추출됨)
        s = s.replaceAll("!\\[([^\\]]*)\\]\\(([^\\)]*)\\)", "[이미지: $2 — $1]");
        // 링크는 text(url) 형태로
        s = s.replaceAll("\\[([^\\]]*)\\]\\(([^\\)]*)\\)", "$1");
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        s = s.replaceAll("__([^_]+)__", "$1");
        s = s.replaceAll("(?<!\\*)\\*(?!\\*)([^*]+)(?<!\\*)\\*(?!\\*)", "$1");
        s = s.replaceAll("(?<!_)_(?!_)([^_]+)(?<!_)_(?!_)", "$1");
        s = s.replaceAll("~~([^~]+)~~", "$1");
        s = s.replaceAll("`([^`]+)`", "$1");
        s = s.replaceAll("\\\\([*_\\[\\]#])", "$1");
        // HTML 잔여 태그 제거 (단순 inline 태그)
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
}
