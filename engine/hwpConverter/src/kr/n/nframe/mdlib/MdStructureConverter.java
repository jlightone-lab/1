package kr.n.nframe.mdlib;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * v13.37: 외부 MD 파일 → HWP/HWPX 구조 변환기.
 *
 * <p>본 도구로 생성한 MD 가 아닌, 외부에서 받은 일반 Markdown 을
 * HWP/HWPX 로 변환한다. 사이드카 / 임베드 라운드트립과 달리, 원본의
 * 서식·바이너리 구조를 복원하는 것이 아니라 MD 의 제목·문단·표·이미지
 * 구조를 HWPX 템플릿에 새로 주입하여 출력한다.
 *
 * <h3>지원 MD 요소 (MVP)</h3>
 * <ul>
 *   <li># ~ ###### 헤딩 → 크기별 charShape 가 적용된 hp:p</li>
 *   <li>일반 문단 → 기본 charShape hp:p</li>
 *   <li>**bold** / __bold__ → HTML 태그로 보존 (한/글이 인식)</li>
 *   <li>*italic* / _italic_ → HTML 태그로 보존</li>
 *   <li>| ... | 테이블 → HWPX CtrlTable (셀별 hp:p)</li>
 *   <li>&gt; 인용 → 들여쓰기 문단 (prefix "│ ")</li>
 *   <li>- / * / 1. 리스트 → prefix 유지</li>
 *   <li>```코드블록``` → 코드 고정폭 문단 (prefix "    ")</li>
 *   <li>--- 구분선 → 공백 문단</li>
 * </ul>
 *
 * <h3>제약</h3>
 * <ul>
 *   <li>이미지/링크 URL 은 텍스트로만 보존됨 (바이너리 삽입 X)</li>
 *   <li>복잡한 HTML (rowspan/colspan/inline style) 은 플레인 텍스트화</li>
 *   <li>폰트·색상·정렬 등 상세 서식은 템플릿 기본값을 사용</li>
 *   <li>외부 MD 로 만든 HWP 는 "모양 동일" 보장 없음 — 구조만 재현</li>
 * </ul>
 */
public class MdStructureConverter {

    // ---- 템플릿의 알려진 charShape ID (case1_50p.hwpx 기준) ----
    //   크기별로 의미있는 charShape 를 선별. 실제 font/bold 여부는 템플릿 의존.
    private static final int CHARPR_NORMAL  = 0;   // 10pt
    private static final int CHARPR_H1      = 7;   // 20pt
    private static final int CHARPR_H2      = 5;   // 16pt
    private static final int CHARPR_H3      = 12;  // 14pt
    private static final int CHARPR_H4      = 16;  // 12pt
    private static final int CHARPR_H5      = 6;   // 11pt
    private static final int CHARPR_H6      = 0;   // 10pt (normal)
    private static final int PARAPR_DEFAULT = 0;

    // ---- 템플릿 리소스 경로 (classpath) ----
    private static final String TEMPLATE_BASE = "/kr/n/nframe/resources/hwpx-template/";
    private static final String[] TEMPLATE_FILES = {
            "mimetype",
            "version.xml",
            "settings.xml",
            "Contents/header.xml",
            "Contents/content.hpf",
            "META-INF/container.xml",
            "META-INF/container.rdf",
            "META-INF/manifest.xml",
    };

    /**
     * 외부 MD → HWPX (구조 변환).
     * [v14.94] MD → HWP → HWPX 라운드트립 경로로 변경. 이전 v14.93 까지는 MdToHwpxNative 의
     *  네이티브 HWPX 빌더를 사용했으나, MdToHwpRich/MdImageInjector 에 누적된 모든 표·정렬·셀
     *  배경·nested table margin·border:none 처리 등 fix 가 HWPX 출력 경로에는 반영되지 않아
     *  같은 MD 입력에 대한 HWP 와 HWPX 결과물 품질이 큰 폭으로 갈리던 문제 해결.
     *  새 경로:
     *    1) MD → 임시 HWP (MdToHwpRich, 모든 v14.91~v14.93 fix 적용)
     *    2) MD 의 data URI 이미지/셀 배경 → HWPX 에 주입 + 임시 HWP → 최종 HWPX (injectAsHwpx)
     *    3) 실패 시 기존 MdToHwpxNative 로 fallback (호환).
     */
    public void convertMarkdownToHwpxStructure(String filePathMd, String filePathHwpx) throws IOException {
        String mdIn = prepInputMd(filePathMd);
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("md_to_hwpx_");
            Path tempHwp = tmpDir.resolve("step.hwp");
            new MdToHwpRich().convert(mdIn, tempHwp.toString());
            new MdImageInjector().injectAsHwpx(mdIn, tempHwp.toString(), filePathHwpx);
            System.out.println("[MdStructureConverter] HWPX 생성 완료 (MD → HWP → HWPX 라운드트립).");
            return;
        } catch (Throwable t) {
            System.out.println("[MdStructureConverter] 라운드트립 변환 실패 → MdToHwpxNative fallback: "
                    + t.getClass().getSimpleName() + " — " + t.getMessage());
        } finally {
            if (tmpDir != null) {
                final Path d = tmpDir;
                try {
                    Files.walk(d).sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) { /* ignore */ } });
                } catch (Exception ignored) { }
            }
        }
        try {
            new MdToHwpxNative().convert(mdIn, filePathHwpx);
        } catch (Exception e) {
            // fallback to basic XML generation
            byte[] mdBytes = Files.readAllBytes(Paths.get(mdIn));
            String mdText = new String(mdBytes, StandardCharsets.UTF_8);
            List<Block> blocks = parseMarkdown(mdText);
            String sectionXml = buildSection0Xml(blocks);
            writeHwpxZip(filePathHwpx, sectionXml);
            System.out.println("[MdStructureConverter] (fallback) 기본 HWPX 구조 변환 완료.");
        }
    }

    /**
     * 외부 MD → HWP (구조 변환). v14.11 부터 .hwp + .hwpx 동시 생성.
     *
     * <p>v14.11: VSCode preview 와 시각적 동일성을 위해 두 가지 결과를 동시 출력:
     * <ul>
     *   <li>{@code case1.hwp} — hwplib 기반 텍스트 (Unicode 박스 드로잉 + 헤딩 강조)
     *       원하는 위치에 정확히 텍스트 컨텐츠 보존</li>
     *   <li>{@code case1.hwpx} — Swing HTMLEditorKit 으로 MD 를 HTML 렌더링한 결과를
     *       PNG 이미지로 변환하여 HWPX 에 페이지 단위로 임베드.
     *       VSCode preview 와 시각적으로 동일</li>
     * </ul>
     */
    public void convertMarkdownToHwpStructure(String filePathMd, String filePathHwp) throws Exception {
        String mdIn = prepInputMd(filePathMd);
        // v14.14: 우선 MdToHwpRich (heading 폰트 + 풍부 템플릿) 시도, 실패 시 MdToHwpDirect 로 fallback.
        try {
            new MdToHwpRich().convert(mdIn, filePathHwp);
            System.out.println("[MdStructureConverter] .hwp 생성 완료 (Rich 모드 — heading 폰트 + 이미지 포함 템플릿).");
        } catch (Throwable t) {
            System.out.println("[MdStructureConverter] Rich 변환 실패 → MdToHwpDirect 로 fallback: "
                    + t.getClass().getSimpleName() + " — " + t.getMessage());
            t.printStackTrace();
            new MdToHwpDirect().convert(mdIn, filePathHwp);
            System.out.println("[MdStructureConverter] .hwp 생성 완료 (텍스트 기반 fallback).");
        }
        // v14.83: MD에 data: URI 이미지가 있으면 HWPX 라운드트립으로 picture control 주입.
        // hwplib HWPWriter의 그림 호환 한계를 우회 — 자체 SectionWriter.writePicture 사용.
        try {
            new MdImageInjector().inject(mdIn, filePathHwp);
        } catch (Throwable t) {
            System.out.println("[MdStructureConverter] 이미지 주입 실패 (placeholder 유지): "
                    + t.getClass().getSimpleName() + " — " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * [표셀 이미지 역변환] 정변환(HwpMdConverter)이 표 셀 안의 이미지를
     * {@code <img src="data:...;base64,..." alt="...">} HTML 태그로 emit 하므로,
     * md→hwp/hwpx 역변환 입력에서 이 태그를 다시 마크다운 이미지
     * {@code ![alt](data:...)} 로 정규화한다. 그래야 기존 이미지 주입 경로
     * (MdImageInjector) 가 셀 내부 이미지도 인식해 실제 그림 객체로 배치한다.
     *
     * <p>다른 기능 무영향: 일반 md 및 표 밖 마크다운 이미지에는 {@code <img>} 가
     * 없어 no-op (원본 경로 그대로 반환). {@code <td>}/{@code <table>} 구조·셀 텍스트/
     * 정렬·본문 이미지 resolve 에는 손대지 않는다. 변환 시에만 임시 md 파일을 만들어
     * 두 변환기(MdToHwpRich·MdImageInjector)에 동일 입력으로 넘긴다.
     */
    private static final Pattern CELL_IMG_TAG =
            Pattern.compile("(?is)<img\\b[^>]*?\\bsrc\\s*=\\s*\"(data:[^\"]*)\"[^>]*?>");
    private static final Pattern IMG_ALT_ATTR =
            Pattern.compile("(?is)\\balt\\s*=\\s*\"([^\"]*)\"");

    private String prepInputMd(String filePathMd) throws IOException {
        byte[] b = Files.readAllBytes(Paths.get(filePathMd));
        String md = new String(b, StandardCharsets.UTF_8);
        if (md.indexOf("<img") < 0) return filePathMd;
        Matcher m = CELL_IMG_TAG.matcher(md);
        if (!m.find()) return filePathMd;
        StringBuffer sb = new StringBuffer(md.length());
        m.reset();
        while (m.find()) {
            String tag = m.group(0);
            String src = m.group(1);
            Matcher am = IMG_ALT_ATTR.matcher(tag);
            String alt = am.find() ? am.group(1) : "";
            // htmlAttrEscape 역: &lt;/&gt;/&quot; 먼저, &amp; 마지막
            alt = alt.replace("&lt;", "<").replace("&gt;", ">")
                     .replace("&quot;", "\"").replace("&amp;", "&");
            // src: 정변환은 & → &amp;, " → %22 로 이스케이프했음
            src = src.replace("%22", "\"").replace("&amp;", "&");
            // 마크다운 이미지 alt 의 [ ] 는 링크텍스트 규칙상 escape
            String altMd = alt.replace("[", "\\[").replace("]", "\\]");
            m.appendReplacement(sb, Matcher.quoteReplacement("![" + altMd + "](" + src + ")"));
        }
        m.appendTail(sb);
        Path p = Files.createTempFile("md_norm_", ".md");
        p.toFile().deleteOnExit();
        Files.write(p, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[MdStructureConverter] 표 셀 <img> → 마크다운 이미지 정규화 적용: " + p);
        return p.toString();
    }

    /** 출력 경로의 .hwp 확장자를 .hwpx 로 바꾼다. .hwp 가 아니면 ".hwpx" 를 덧붙인다. */
    private static String hwpxFallbackPath(String hwpPath) {
        String fb = hwpPath.replaceAll("(?i)\\.hwp$", ".hwpx");
        if (fb.equals(hwpPath)) fb = hwpPath + ".hwpx";
        return fb;
    }

    // =========================================================
    //  MD 파서 (블록 단위)
    // =========================================================

    static abstract class Block {}

    static class Heading extends Block {
        int level;         // 1~6
        String text;       // 텍스트 (inline markup 제거 후)
        Heading(int lv, String t) { level = lv; text = t; }
    }

    static class Paragraph extends Block {
        String text;
        Paragraph(String t) { text = t; }
    }

    static class Table extends Block {
        List<List<String>> rows = new ArrayList<>();  // 행 x 열
    }

    static class Blank extends Block {}

    /**
     * MD 텍스트를 블록 리스트로 파싱. HTML 블록/주석은 제거 or 플레인화.
     */
    private List<Block> parseMarkdown(String text) {
        // HTML 주석 블록 (임베드 포함) 제거
        text = text.replaceAll("(?s)<!--.*?-->", "");

        String[] lines = text.split("\\r?\\n", -1);
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                blocks.add(new Blank());
                i++;
                continue;
            }

            // ATX 헤딩
            Matcher hm = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$").matcher(trimmed);
            if (hm.matches()) {
                int lv = hm.group(1).length();
                String t = stripInlineMarkup(hm.group(2));
                blocks.add(new Heading(lv, t));
                i++;
                continue;
            }

            // Setext 헤딩 (===== / -----)
            if (i + 1 < lines.length) {
                String next = lines[i + 1].trim();
                if (!next.isEmpty() && next.matches("=+")) {
                    blocks.add(new Heading(1, stripInlineMarkup(trimmed)));
                    i += 2;
                    continue;
                }
                if (!next.isEmpty() && next.matches("-{3,}")) {
                    blocks.add(new Heading(2, stripInlineMarkup(trimmed)));
                    i += 2;
                    continue;
                }
            }

            // 수평선 / --- 구분
            if (trimmed.matches("-{3,}|\\*{3,}|_{3,}")) {
                blocks.add(new Blank());
                i++;
                continue;
            }

            // 파이프 테이블 (다음 라인이 구분선 ---|---)
            if (trimmed.startsWith("|") && i + 1 < lines.length) {
                String next = lines[i + 1].trim();
                if (next.matches("\\|?\\s*:?-{3,}:?(\\s*\\|\\s*:?-{3,}:?)*\\|?")) {
                    Table t = new Table();
                    t.rows.add(splitPipeRow(trimmed));
                    i += 2; // skip header + separator
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

            // 코드 블록 ```
            if (trimmed.startsWith("```")) {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    sb.append(lines[i]).append('\n');
                    i++;
                }
                if (i < lines.length) i++; // closing ```
                if (sb.length() > 0) {
                    blocks.add(new Paragraph(sb.toString().replaceAll("\\n$", "")));
                }
                continue;
            }

            // HTML 블록은 제거 or 플레인 추출
            if (trimmed.startsWith("<")) {
                // 간단히 이 라인만 제외 (복잡한 HTML 처리는 제공하지 않음)
                String plain = stripHtmlTags(line);
                if (!plain.trim().isEmpty()) {
                    blocks.add(new Paragraph(stripInlineMarkup(plain.trim())));
                }
                i++;
                continue;
            }

            // 일반 문단 (빈 줄까지 누적)
            StringBuilder sb = new StringBuilder();
            while (i < lines.length && !lines[i].trim().isEmpty()) {
                String ln = lines[i];
                // 헤딩 시작 혹은 테이블 / 코드블록 만나면 중단
                if (ln.trim().matches("^#{1,6}\\s+.+")) break;
                if (ln.trim().startsWith("```")) break;
                if (ln.trim().startsWith("<")) break;
                if (sb.length() > 0) sb.append(' ');
                sb.append(ln.trim());
                i++;
            }
            if (sb.length() > 0) {
                blocks.add(new Paragraph(stripInlineMarkup(sb.toString())));
            }
        }
        return blocks;
    }

    private static List<String> splitPipeRow(String line) {
        // 앞뒤 | 제거
        if (line.startsWith("|")) line = line.substring(1);
        if (line.endsWith("|")) line = line.substring(0, line.length() - 1);
        String[] cells = line.split("\\|", -1);
        List<String> out = new ArrayList<>();
        for (String c : cells) out.add(stripInlineMarkup(c.trim()));
        return out;
    }

    /**
     * Markdown 인라인 마크업 제거 (bold/italic/code/link/image).
     * 텍스트 콘텐츠만 남긴다 (MVP — 서식 유지 X).
     */
    private static String stripInlineMarkup(String s) {
        if (s == null || s.isEmpty()) return "";
        // 이미지 ![alt](url) → alt
        s = s.replaceAll("!\\[([^\\]]*)\\]\\(([^\\)]*)\\)", "$1");
        // 링크 [text](url) → text
        s = s.replaceAll("\\[([^\\]]*)\\]\\(([^\\)]*)\\)", "$1");
        // bold **text** or __text__ → text
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        s = s.replaceAll("__([^_]+)__", "$1");
        // italic *text* or _text_ → text
        s = s.replaceAll("(?<!\\*)\\*(?!\\*)([^*]+)(?<!\\*)\\*(?!\\*)", "$1");
        s = s.replaceAll("(?<!_)_(?!_)([^_]+)(?<!_)_(?!_)", "$1");
        // strikethrough ~~text~~ → text
        s = s.replaceAll("~~([^~]+)~~", "$1");
        // code `text` → text
        s = s.replaceAll("`([^`]+)`", "$1");
        // escape 문자 (\* \_ \[ \])
        s = s.replaceAll("\\\\([*_\\[\\]#])", "$1");
        return s;
    }

    private static String stripHtmlTags(String s) {
        return s.replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&nbsp;", " ");
    }

    // =========================================================
    //  section0.xml 생성
    // =========================================================

    private String buildSection0Xml(List<Block> blocks) {
        StringBuilder out = new StringBuilder(65536);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>");
        out.append("<hs:sec xmlns:ha=\"http://www.hancom.co.kr/hwpml/2011/app\"")
           .append(" xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\"")
           .append(" xmlns:hp10=\"http://www.hancom.co.kr/hwpml/2016/paragraph\"")
           .append(" xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\"")
           .append(" xmlns:hc=\"http://www.hancom.co.kr/hwpml/2011/core\"")
           .append(" xmlns:hh=\"http://www.hancom.co.kr/hwpml/2011/head\"")
           .append(" xmlns:hhs=\"http://www.hancom.co.kr/hwpml/2011/history\"")
           .append(" xmlns:hm=\"http://www.hancom.co.kr/hwpml/2011/master-page\"")
           .append(" xmlns:hpf=\"http://www.hancom.co.kr/schema/2011/hpf\"")
           .append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
           .append(" xmlns:opf=\"http://www.idpf.org/2007/opf/\">");

        // 첫 번째 문단에 secPr (섹션 설정) 첨부
        boolean first = true;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            appendBlockXml(out, b, first);
            first = false;
        }

        // 아무 블록도 없으면 빈 문단 1개 추가 (secPr 포함)
        if (first) {
            out.append(openParagraph(PARAPR_DEFAULT));
            out.append("<hp:run charPrIDRef=\"").append(CHARPR_NORMAL).append("\">")
               .append(SECTION_PR)
               .append("</hp:run>")
               .append(LINE_SEG_DEFAULT)
               .append("</hp:p>");
        }

        out.append("</hs:sec>");
        return out.toString();
    }

    private void appendBlockXml(StringBuilder out, Block b, boolean first) {
        String secPr = first ? SECTION_PR : "";
        if (b instanceof Heading) {
            Heading h = (Heading) b;
            int charPr = charPrForHeading(h.level);
            out.append(openParagraph(PARAPR_DEFAULT));
            out.append("<hp:run charPrIDRef=\"").append(charPr).append("\">");
            out.append(secPr);
            out.append("<hp:t>").append(xmlEscape(h.text)).append("</hp:t>");
            out.append("</hp:run>");
            out.append(LINE_SEG_DEFAULT);
            out.append("</hp:p>");
        } else if (b instanceof Paragraph) {
            Paragraph p = (Paragraph) b;
            out.append(openParagraph(PARAPR_DEFAULT));
            out.append("<hp:run charPrIDRef=\"").append(CHARPR_NORMAL).append("\">");
            out.append(secPr);
            out.append("<hp:t>").append(xmlEscape(p.text)).append("</hp:t>");
            out.append("</hp:run>");
            out.append(LINE_SEG_DEFAULT);
            out.append("</hp:p>");
        } else if (b instanceof Table) {
            Table t = (Table) b;
            // 표 자체는 복잡한 structural 요소 (ctrl table).
            // MVP: 텍스트 표현으로 대체 (각 셀을 파이프로 이어붙인 문단)
            out.append(openParagraph(PARAPR_DEFAULT));
            out.append("<hp:run charPrIDRef=\"").append(CHARPR_NORMAL).append("\">");
            out.append(secPr);
            StringBuilder tableText = new StringBuilder();
            for (int ri = 0; ri < t.rows.size(); ri++) {
                if (ri > 0) tableText.append('\n');
                List<String> row = t.rows.get(ri);
                for (int ci = 0; ci < row.size(); ci++) {
                    if (ci > 0) tableText.append(" | ");
                    tableText.append(row.get(ci));
                }
            }
            // 멀티 라인 텍스트는 line break 로 변환 (하나의 t 에 포함)
            String[] tlines = tableText.toString().split("\n");
            for (int ti = 0; ti < tlines.length; ti++) {
                if (ti > 0) out.append("<hp:lineBreak/>");
                out.append("<hp:t>").append(xmlEscape(tlines[ti])).append("</hp:t>");
            }
            out.append("</hp:run>");
            out.append(LINE_SEG_DEFAULT);
            out.append("</hp:p>");
        } else if (b instanceof Blank) {
            // 빈 문단
            out.append(openParagraph(PARAPR_DEFAULT));
            out.append("<hp:run charPrIDRef=\"").append(CHARPR_NORMAL).append("\">");
            out.append(secPr);
            out.append("</hp:run>");
            out.append(LINE_SEG_DEFAULT);
            out.append("</hp:p>");
        }
    }

    private String openParagraph(int paraPrId) {
        return "<hp:p id=\"0\" paraPrIDRef=\"" + paraPrId
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

    /** 기본 lineSeg (한/글이 필요로 하는 최소 렌더링 정보) */
    private static final String LINE_SEG_DEFAULT =
            "<hp:linesegarray><hp:lineseg textpos=\"0\" vertpos=\"0\" vertsize=\"1000\""
            + " textheight=\"1000\" baseline=\"850\" spacing=\"600\" horzpos=\"0\""
            + " horzsize=\"41954\" flags=\"393216\"/></hp:linesegarray>";

    /** 섹션 설정 (첫 문단의 run 안에 필수) — case1_50p 템플릿에서 추출 */
    private static final String SECTION_PR =
            "<hp:secPr id=\"\" textDirection=\"HORIZONTAL\" spaceColumns=\"1134\" tabStop=\"8000\""
            + " tabStopVal=\"4000\" tabStopUnit=\"HWPUNIT\" outlineShapeIDRef=\"0\" memoShapeIDRef=\"0\""
            + " textVerticalWidthHead=\"0\" masterPageCnt=\"0\">"
            + "<hp:grid lineGrid=\"0\" charGrid=\"0\" wonggojiFormat=\"0\"/>"
            + "<hp:startNum pageStartsOn=\"BOTH\" page=\"0\" pic=\"0\" tbl=\"0\" equation=\"0\"/>"
            + "<hp:visibility hideFirstHeader=\"0\" hideFirstFooter=\"0\" hideFirstMasterPage=\"0\""
            + " border=\"SHOW_ALL\" fill=\"SHOW_ALL\" hideFirstPageNum=\"0\" hideFirstEmptyLine=\"0\" showLineNumber=\"0\"/>"
            + "<hp:lineNumberShape restartType=\"0\" countBy=\"0\" distance=\"0\" startNumber=\"0\"/>"
            + "<hp:pagePr landscape=\"WIDELY\" width=\"59528\" height=\"84188\" gutterType=\"LEFT_ONLY\">"
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

    // =========================================================
    //  HWPX ZIP 패키징 (템플릿 + 생성한 section0.xml)
    // =========================================================

    private void writeHwpxZip(String outputPath, String sectionXml) throws IOException {
        Path out = Paths.get(outputPath).toAbsolutePath();
        Files.createDirectories(out.getParent());
        try (OutputStream os = Files.newOutputStream(out);
             ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
            // mimetype 은 압축 없이 첫 엔트리로 기록 (HWPX 규격)
            byte[] mimetype = loadTemplate("mimetype");
            ZipEntry mime = new ZipEntry("mimetype");
            mime.setMethod(ZipEntry.STORED);
            mime.setSize(mimetype.length);
            mime.setCompressedSize(mimetype.length);
            CRC32 crc = new CRC32(); crc.update(mimetype);
            mime.setCrc(crc.getValue());
            zip.putNextEntry(mime);
            zip.write(mimetype);
            zip.closeEntry();

            // 나머지 템플릿 파일들
            for (String name : TEMPLATE_FILES) {
                if ("mimetype".equals(name)) continue;
                byte[] data = loadTemplate(name);
                zip.putNextEntry(new ZipEntry(name));
                zip.write(data);
                zip.closeEntry();
            }

            // 생성된 section0.xml
            zip.putNextEntry(new ZipEntry("Contents/section0.xml"));
            zip.write(sectionXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private byte[] loadTemplate(String relPath) throws IOException {
        String resourcePath = TEMPLATE_BASE + relPath;
        try (InputStream in = MdStructureConverter.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("HWPX 템플릿 리소스 누락: " + resourcePath
                        + "  (JAR 빌드 시 resources/ 포함 여부 확인 필요)");
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }
}
