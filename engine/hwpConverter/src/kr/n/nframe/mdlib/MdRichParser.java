package kr.n.nframe.mdlib;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MdToHwpRich에서 분리된 MD/HTML 파서 + 블록 데이터 모델 (v14.x 누적 규칙).
 *
 * <h3>책임</h3>
 * <ul>
 *   <li>MD 문자열 → Block 트리 ({@link #parseMarkdown})</li>
 *   <li>HTML 표(중첩 포함) → Table ({@link #parseHtmlTable})</li>
 *   <li>인라인 마크업 정리 ({@link #stripInlineMarkup})</li>
 *   <li>flowchart 패턴 정규화, single-cell row 확장, nested 평탄화</li>
 *   <li>fallback Unicode 박스 표 렌더 ({@link #renderBlockRich})</li>
 * </ul>
 *
 * <p>모든 메서드 stateless (static). 블록 모델 클래스는 nested static class로 노출되어
 * 같은 패키지({@code kr.n.nframe.mdlib})의 emit 측 ({@link MdToHwpRich})이
 * {@code import kr.n.nframe.mdlib.MdRichParser.*;} 로 접근한다.
 *
 * <p>v14.x 마커 ("[v14.NN ...]") 는 원본 MdToHwpRich에서 그대로 보존했으며,
 * 각 fix의 컨텍스트를 잃지 않기 위해 주석을 손대지 않았다.
 */
public final class MdRichParser {
    private MdRichParser() {}

    // =========================================================
    //  설정 플래그 (emit 측도 참조)
    // =========================================================

    /** [v14.68] nested 표 처리 모드.
     *  true  = nested 표를 outer cell 안의 진짜 HWP nested ControlTable 로 emit
     *           (사용자 task 요구 — 표 안에 표 형태 보존). expandNestedRows 비활성화.
     *  false = nested 표를 outer rows 로 펼쳐서 평탄화 (v14.59~v14.67 동작). */
    public static final boolean USE_NESTED_HWP_TABLE = true;

    // =========================================================
    //  bold sentinel — extractStrong / stripStrongMarkers / stripInlineMarkup 공용
    //  (텍스트에 일반적으로 나타나지 않는 PUA 문자)
    //  v15.12: 빈 문자열로 손상되어 있던 sentinel 복원. 빈 문자열일 때
    //   s.startsWith("", i) 가 항상 true 가 되어 extractStrong / stripStrong
    //   계열이 무한 루프 (i += 0). MD→HWP 변환 시 첫 표/단락에서 멈춤.
    //   PUA 시작 영역 U+E000 / U+E001 사용 — 일반 텍스트와 충돌 없음.
    // =========================================================

    static final String STRONG_OPEN  = "";
    static final String STRONG_CLOSE = "";

    // =========================================================
    //  Block 데이터 모델 — emit 측이 instanceof 로 분기
    // =========================================================

    static abstract class Block {}

    static class Heading extends Block {
        int level; String text;
        Heading(int lv, String t) { level = lv; text = t; }
    }

    static class MdParagraph extends Block {
        String text;
        /** [v14.87 task2/3] paragraph alignment: null/"left"=left, "center", "right". */
        String align;
        MdParagraph(String t) { text = t; }
        MdParagraph(String t, String a) { text = t; align = a; }
    }

    /** v14.18: 셀 병합(colspan/rowspan) 지원을 위한 셀 모델.
     *  [v14.58] 셀 본문 내 nested table 지원: nestedTable + textBefore/textAfter. */
    static class TableCell {
        String text;
        int colspan = 1;
        int rowspan = 1;
        /** [v14.49] cell alignment from MD: "center" | "right" | "left" | null. */
        String align;
        /** [v14.58] cell 본문 안에 들어갈 중첩 표. null = 평문 cell. */
        Table nestedTable;
        /** [v14.58] nested 앞 텍스트. nestedTable != null 일 때만 의미 있음. */
        String textBefore;
        /** [v14.58] nested 뒤 텍스트. nestedTable != null 일 때만 의미 있음. */
        String textAfter;
        /** [STEP2 task2/3] 한 셀 안에 top-level nested table 이 2개 이상일 때 텍스트/표 순서를
         *  보존하는 블록 리스트. 각 원소는 String(텍스트 세그먼트) 또는 Table(중첩표).
         *  null = 단일 nested(또는 평문) → 기존 nestedTable/textBefore/textAfter 경로 사용. */
        java.util.List<Object> nestedBlocks;
        TableCell(String text) {
            this.text = (text == null) ? "" : text;
        }
        TableCell(String text, int cs, int rs) {
            this.text = (text == null) ? "" : text;
            this.colspan = Math.max(1, cs);
            this.rowspan = Math.max(1, rs);
        }
        TableCell(String text, int cs, int rs, String align) {
            this(text, cs, rs);
            this.align = align;
        }
        /** [v14.91 task1] 셀 본문 안에 여러 &lt;p align="X"&gt; 가 있는 경우 각 &lt;p&gt; 별 정렬을
         *  보존하기 위한 segment 정보. segStarts[i] = i번째 &lt;p&gt; segment 가 시작되는 cell text 의
         *  char 위치 (\n 으로 합쳐진 평탄 text 기준). segAligns[i] = 해당 segment 의 align
         *  ("center"|"right"|"left"|null). null = 셀-수준 align 사용.
         *  segAligns == null 이면 기존(셀-수준 단일 align) 동작. */
        int[] segStarts;
        String[] segAligns;
    }

    static class Table extends Block {
        List<List<TableCell>> rows = new ArrayList<>();
    }

    static class Image extends Block {
        String alt;
        String path;
        /** [v14.70] base64 data URI 분석 후 할당된 binDataID. -1 = data URI 아님 또는 분석 실패. */
        int binDataId = -1;
        Image(String a, String p) { alt = a; path = p; }
    }

    /** [v14.70] base64 data URI 이미지 임베드 정보 — DocInfo BinData record + BinData stream 양쪽에 사용.
     *  [v14.77] pixelW/H 추가 — picture control 의 widthAtCreate/imageW 를 자연 hwpunit 크기로 set 위함. */
    static class ImageEmbed {
        int binDataId;
        String ext;        // "png", "jpg", "bmp", "gif" 등 (소문자)
        byte[] bytes;      // base64 디코딩된 binary
        String dataUri;    // 원본 data URI (매핑용)
        int pixelW;        // [v14.77] 이미지 자연 픽셀 폭 (PNG/BMP/JPG 헤더에서 추출). 0 = 알 수 없음.
        int pixelH;        // [v14.77] 이미지 자연 픽셀 높이.
        ImageEmbed(int id, String e, byte[] b, String uri) {
            binDataId = id; ext = e; bytes = b; dataUri = uri;
        }
    }

    static class Blank extends Block {}

    /** sentinel 토큰 해석 결과 — emit 측이 paragraph 단위로 bold range 와 함께 emit. */
    static class RichLine {
        final String text;
        final List<int[]> boldRanges; // [start,end), text 좌표
        RichLine(String text, List<int[]> boldRanges) {
            this.text = text;
            this.boldRanges = boldRanges == null ? new ArrayList<int[]>() : boldRanges;
        }
        static RichLine plain(String text) { return new RichLine(text, new ArrayList<int[]>()); }
    }

    // =========================================================
    //  정규식 패턴 (compile 1회)
    // =========================================================

    private static final Pattern HEADING_RE = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern PIPE_DIV_RE = Pattern.compile("\\|?\\s*:?-{3,}:?(\\s*\\|\\s*:?-{3,}:?)*\\|?");
    private static final Pattern MULTILINE_IMAGE_RE = Pattern.compile(
            "!\\[(?<alt>[^\\]]*?)\\]\\((?<path>[^)\\s]+(?:\\s[^)]*)?)\\)", Pattern.DOTALL);

    // =========================================================
    //  v15.14 페이지 폭 기반 정규화 — HR / TOC dot-leader
    // =========================================================

    /**
     * 본문 1단 폭 (hwpunit). MdToHwpRich.addParagraph 의 LineSegItem.segmentWidth 와
     *  addNativeTable 의 TOTAL_W, MdRichTemplateMutator.TOC_PAGE_INNER_WIDTH 와 동일 값.
     *  템플릿 secPr 의 pagePr width(59528) - 좌/우 margin(8504×2) = 42520.
     *
     *  v15.18 까지 41954 (잘못 추정한 값) → v15.27 부터 42520 으로 통일 (TOC dot-leader
     *  의 N 계산이 실제 페이지 폭에 맞아야 페이지 번호 우측 정렬됨).
     */
    static final int PAGE_INNER_WIDTH_HWPUNIT = 42520;

    /** ASCII / 반각 1 글자의 advance (hwpunit). MdToHwpRich.charVisualWidth 와 동일 단가. */
    private static final int CHAR_W_HALF = 450;
    /** CJK / 전각 1 글자의 advance (hwpunit). */
    private static final int CHAR_W_FULL = 900;

    /**
     * [v15.14 task1/3] HR("---" / "***" / "___") 본문 텍스트를 페이지 본문 폭에 깔끔히
     *  한 줄로 들어가도록 정규화. MD 원본에서 임의 길이(예: 110)의 dash 가 그대로 emit
     *  되면 HWP 10pt 폰트(ASCII ≈ 450 hwpunit) 환경에서 ~93 chars 이후 자동 wrap → 2-3 줄.
     *  사용자 요구: "페이지 가로에 딱 맞게만". {@link #PAGE_INNER_WIDTH_HWPUNIT} 의 0.85
     *  (computeLineStarts 의 wrapLimit 과 동일 임계) 이내에서 가장 긴 한 줄을 만든다.
     */
    static String normalizeHr(String hrLine) {
        if (hrLine == null || hrLine.isEmpty()) return hrLine;
        char c = hrLine.charAt(0);
        if (c != '-' && c != '*' && c != '_') return hrLine;
        int maxChars = (PAGE_INNER_WIDTH_HWPUNIT * 85 / 100) / CHAR_W_HALF;  // = 79
        StringBuilder sb = new StringBuilder(maxChars);
        for (int k = 0; k < maxChars; k++) sb.append(c);
        return sb.toString();
    }

    private static final Pattern TOC_LEADER_RE = Pattern.compile(
            "^(.*?\\S)\\s+([·]{5,})\\s+(\\d+)\\s*$");

    /**
     * [v15.27 task1/2] TOC 점선 leader 를 literal "·" 문자열 채움 방식으로 변경.
     *
     *  <p>이전(v15.16 ~ v15.26) : "label \t num" sentinel 출력 후 emit 측에서 HWP
     *  인라인 TAB 컨트롤(code=0x0009, leader=DOT, type=RIGHT) 로 변환. 한/글 고유의
     *  "탭" 기능이지만 다중 자릿수 페이지 번호 + 양식 차이 때문에 분리/뒤집힘 렌더
     *  ("1·1", "71", "8·2", "3·6") 회귀가 지속적으로 보고됨.
     *
     *  <p>사용자 요구 (task1/task2) : 인라인 TAB 컨트롤 제거, 목차명은 좌측 정렬,
     *  페이지 번호는 우측 정렬, 그 사이를 "·"(U+00B7) 문자로 채울 것.
     *
     *  <p>본 메서드는 이 정책에 맞춰 다음과 같이 변환한다 :
     *  <pre>
     *    "라벨 ······································· 36"
     *  </pre>
     *  점선 개수 N 은 페이지 본문 폭 (PAGE_INNER_WIDTH_HWPUNIT) 에 맞춰 계산 :
     *      N = floor((page_width - labelW - 2×SPACE_W - numW) / DOT_W)
     *  여기서 DOT_W = "·"(U+00B7) 의 시각 폭 (450 hwpunit, MdToHwpRich.charVisualWidth
     *  의 ASCII fallback 과 동일). 라벨/번호 길이가 달라도 모든 줄의 시각 폭이 페이지
     *  폭에 근접 → 페이지 번호가 우측 정렬되어 보임.
     *
     *  <p>line wrap 회피를 위해 약간 under-fill (≤ page width - tolerance) 한다.
     */
    static String normalizeTocLeaderLine(String line) {
        if (line == null || line.length() < 8) return line;
        Matcher m = TOC_LEADER_RE.matcher(line);
        if (!m.matches()) return line;
        String label = m.group(1);
        String num   = m.group(3);
        // [v15.32 task1/2 fix] 사용자 결정 — inline TAB 을 다시 완전히 제거하고
        //  literal "·" 문자열로 채움. inline TAB 방식은 한/글 버전/렌더 quirk 에 따라
        //  여러 회귀 (페이지 번호 분리/뒤집힘, 손상 경고 등) 가 반복적으로 발생하여
        //  안정성 우선으로 literal dot fill 채택.
        //
        //  Trade-off : 비례 폰트의 실제 advance 와 우리 추정 폭의 차이로 각 줄의
        //  페이지 번호 위치가 ± 1-2 char (≈ 1mm) 정도 흔들릴 수 있음. 하지만 페이지
        //  번호 자체는 절대 깨지지 않음 (예: "17" 이 "71" 또는 "1 1" 로 변하지 않음).
        return buildDotLeaderLine(label, num);
    }

    /** [v15.32] literal "·" 점선 fill — 페이지 본문 폭에 맞춰 정수 개수 dot 채움.
     *  inline TAB 미사용으로 한/글 렌더 quirk 회피. */
    static String buildDotLeaderLine(String label, String num) {
        final int targetW = PAGE_INNER_WIDTH_HWPUNIT;
        final int dotW = CHAR_W_HALF;             // "·" (U+00B7) = ASCII-width
        final int spaceW = CHAR_W_HALF;
        int labelW = visualWidthHwpunit(label);
        int numW   = visualWidthHwpunit(num);
        // label + " " + N*"·" + " " + num — 총 폭이 page width 에 근접하도록 N 계산.
        int remaining = targetW - labelW - spaceW - spaceW - numW;
        int n = remaining / dotW;
        // 1 dot 안전 마진 (line wrap 방지) — page 폭 추정 오차에 대한 buffer.
        if (n > 5) n -= 1;
        if (n < 5) n = 5;
        StringBuilder sb = new StringBuilder(label.length() + n + 8);
        sb.append(label).append(' ');
        for (int i = 0; i < n; i++) sb.append('·');
        sb.append(' ').append(num);
        return sb.toString();
    }

    /** Visual width approximation matching MdToHwpRich.charVisualWidth — hwpunit per char.
     *  (이름 충돌 회피용 — 기존 visualWidth(String) 은 표 border 아트가 사용하는 column-count 계산기). */
    private static int visualWidthHwpunit(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0xAC00 && ch <= 0xD7A3) w += CHAR_W_FULL;
            else if (ch >= 0x4E00 && ch <= 0x9FFF) w += CHAR_W_FULL;
            else if (ch >= 0x3000 && ch <= 0x303F) w += CHAR_W_FULL;
            else if (ch >= 0xFF00 && ch <= 0xFFEF) w += CHAR_W_FULL;
            else if (ch >= 0x2000 && ch <= 0x27BF) w += CHAR_W_FULL;
            else if (ch >= 0x3200 && ch <= 0x33FF) w += CHAR_W_FULL;
            else w += CHAR_W_HALF;
        }
        return w;
    }

    // =========================================================
    //  공개 API — emit 측 (MdToHwpRich) 이 호출
    // =========================================================

    /** MD 텍스트를 Block 리스트로 파싱. */
    static List<Block> parseMarkdown(String text) {
        text = text.replaceAll("(?s)<!--.*?-->", "");

        List<String[]> imageRefs = new ArrayList<>();
        StringBuilder before = new StringBuilder(text);
        Matcher imgMatcher = MULTILINE_IMAGE_RE.matcher(before.toString());
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (imgMatcher.find()) {
            String alt = imgMatcher.group("alt");
            String path = imgMatcher.group("path").replaceAll("\\s+", " ").trim();
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

            Matcher imgM = Pattern.compile("^\\[\\[IMG#(\\d+)\\]\\]$").matcher(trimmed);
            if (imgM.matches()) {
                int ix = Integer.parseInt(imgM.group(1));
                if (ix < imageRefs.size()) {
                    String[] ref = imageRefs.get(ix);
                    blocks.add(new Image(ref[0], ref[1]));
                }
                i++; continue;
            }

            Matcher hm = HEADING_RE.matcher(trimmed);
            if (hm.matches()) {
                blocks.add(new Heading(hm.group(1).length(), stripInlineMarkup(hm.group(2))));
                i++; continue;
            }

            if (i + 1 < lines.length) {
                String next = lines[i + 1].trim();
                if (!next.isEmpty() && next.matches("=+")) {
                    blocks.add(new Heading(1, stripInlineMarkup(trimmed))); i += 2; continue;
                }
                if (!next.isEmpty() && next.matches("-{3,}")) {
                    blocks.add(new Heading(2, stripInlineMarkup(trimmed))); i += 2; continue;
                }
            }

            // [v14.87 task1] 가로선 (---, ***, ___) 을 본문 텍스트로 보존 — 이전 v14.86 까지는
            //   Blank 으로 emit 되어 HWP 출력 시 사라지는 문제. 사용자 요구: 원본의 dash line 보존.
            // [v15.14 task1/3] 원본 dash 개수가 페이지 본문 폭을 초과(예: 110 dashes)하면
            //   HWP 에서 두 줄 이상으로 wrap → 보기 흉함. normalizeHr 로 페이지 폭 한 줄 분량으로 단축.
            if (trimmed.matches("-{3,}|\\*{3,}|_{3,}")) {
                blocks.add(new MdParagraph(normalizeHr(trimmed))); i++; continue;
            }

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

            // [v14.88 task3] 테두리가 있는 <div style="...border..."> 블록을 1x1 표로 변환 →
            //   HWP 출력 시 테두리 박스로 가시화 (이전 v14.87 까지 div 의 border 가 사라지는 문제).
            //   사각형 도형 (rect) 의 drawText 본문이 v14.86 부터 이 형태로 emit 되었음.
            // [v14.93 task2/3] visible border 만 1x1 table 로 변환 — `border: none` 으로 명시적
            //   테두리 없음 처리 또는 `text-decoration` 류처럼 단순히 "border" substring 만 포함
            //   하는 div 는 변환 대상이 아님. 이전까지 case1.md 의 목차 div (border: none) 가
            //   잘못 1x1 표로 감싸져 출력되던 회귀 수정.
            if (trimmed.toLowerCase().startsWith("<div") && hasVisibleBorderStyle(trimmed)) {
                StringBuilder divBody = new StringBuilder();
                int depth = 1;
                i++;
                while (i < lines.length && depth > 0) {
                    String l = lines[i];
                    String lLow = l.toLowerCase();
                    int opens = countOccurrences(lLow, "<div");
                    int closes = countOccurrences(lLow, "</div");
                    depth += opens - closes;
                    if (depth > 0) divBody.append(l).append('\n');
                    i++;
                    if (depth <= 0) break;
                }
                String wrap = "<table style=\"border-collapse: collapse; border: 1px solid #666; width: 100%;\">\n"
                        + "<tbody>\n<tr>\n<td style=\"padding: 6px; border: 1px solid #666;\">\n"
                        + divBody.toString()
                        + "</td>\n</tr>\n</tbody>\n</table>";
                Table t = parseHtmlTable(wrap);
                if (!t.rows.isEmpty()) blocks.add(t);
                continue;
            }

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

            if (trimmed.startsWith("```")) {
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                if (i < lines.length) i++;
                if (code.length() > 0) {
                    for (String cl : code.toString().split("\\n", -1)) {
                        if (!cl.isEmpty()) blocks.add(new MdParagraph("    " + cl));
                    }
                }
                continue;
            }

            if (trimmed.startsWith("<")) {
                // [v14.87 task2/3] align="..." 속성을 stripHtmlTags 전에 추출 → 라운드트립 시 정렬 보존
                String alignAttr = null;
                Matcher alM = Pattern.compile(
                        "<[a-zA-Z][^>]*\\balign\\s*=\\s*\"(left|right|center)\"",
                        Pattern.CASE_INSENSITIVE).matcher(line);
                if (alM.find()) alignAttr = alM.group(1).toLowerCase();
                String plain = stripHtmlTags(line);
                if (!plain.trim().isEmpty()) {
                    // [v15.14 task2/4] TOC 점선 leader 우측 정렬 보정 — HTML 태그 strip 후 적용
                    String norm = normalizeTocLeaderLine(stripInlineMarkup(plain.trim()));
                    blocks.add(new MdParagraph(norm, alignAttr));
                }
                i++; continue;
            }

            // [v15.14 task2/4] 일반 단락 텍스트에도 TOC pattern 이 등장하면 보정 적용
            blocks.add(new MdParagraph(normalizeTocLeaderLine(stripInlineMarkup(line))));
            i++;
        }
        // [표 셀 이미지 역방향] 표 밖의 [[IMG#N]] 은 위에서 Image 블록으로 resolve 되어
        //  실제 그림으로 배치되지만, 표 셀 내부에 남은 [[IMG#N]] 토큰은 raw text 로 남는다.
        //  이 짧은 토큰(공백 없음 → 셀 폭으로 wrap 되지 않음)은 그대로 두고, MdImageInjector
        //  가 이미지 index 로 이 토큰을 찾아 실제 그림으로 치환한다. (본문 경로 무영향)
        return blocks;
    }

    /** Block 하나를 RichLine 리스트 (text + bold ranges) 로 변환 — fallback Unicode 표 포함. */
    static List<RichLine> renderBlockRich(Block b) {
        List<RichLine> out = new ArrayList<>();
        if (b instanceof Heading) {
            Heading h = (Heading) b;
            // heading 은 bold marker 가 텍스트 안에 남아 있을 수 있으므로 inline-strong 추출
            out.add(extractStrong(h.text));
        } else if (b instanceof MdParagraph) {
            out.add(extractStrong(((MdParagraph) b).text));
        } else if (b instanceof Table) {
            for (String row : renderTable((Table) b)) {
                out.add(RichLine.plain(row));
            }
        } else if (b instanceof Image) {
            Image img = (Image) b;
            // v14.19: base64 data: URI 본문 leak 방지. data: 로 시작하면 path 노출 X.
            boolean isData = img.path != null
                    && img.path.trim().toLowerCase().startsWith("data:");
            StringBuilder sb = new StringBuilder("[이미지");
            if (isData) {
                if (img.alt != null && !img.alt.isEmpty()) {
                    sb.append(": ").append(img.alt);
                }
            } else {
                sb.append(": ").append(img.path);
                if (img.alt != null && !img.alt.isEmpty()) sb.append(" — ").append(img.alt);
            }
            sb.append("]");
            out.add(RichLine.plain(sb.toString()));
        } else if (b instanceof Blank) {
            out.add(RichLine.plain(""));
        }
        return out;
    }

    /**
     * 텍스트에서 [[STRONG_OPEN]] / [[STRONG_CLOSE]] sentinel 토큰을 찾아내
     * 실제 bold 범위로 변환하고 토큰을 제거한 plain text 와 ranges 를 반환.
     * sentinel 은 parseMarkdown 단계에서 <strong>/<\/strong> tag 자리에 삽입된다.
     */
    static RichLine extractStrong(String s) {
        if (s == null || s.isEmpty()) return RichLine.plain("");
        StringBuilder out = new StringBuilder(s.length());
        List<int[]> ranges = new ArrayList<>();
        final String OPEN = STRONG_OPEN;
        final String CLOSE = STRONG_CLOSE;
        int i = 0;
        int curStart = -1;
        while (i < s.length()) {
            if (s.startsWith(OPEN, i)) {
                if (curStart < 0) curStart = out.length();
                i += OPEN.length();
            } else if (s.startsWith(CLOSE, i)) {
                if (curStart >= 0 && out.length() > curStart) {
                    ranges.add(new int[]{curStart, out.length()});
                }
                curStart = -1;
                i += CLOSE.length();
            } else {
                out.append(s.charAt(i));
                i++;
            }
        }
        if (curStart >= 0 && out.length() > curStart) {
            // 닫히지 않은 strong → 끝까지
            ranges.add(new int[]{curStart, out.length()});
        }
        return new RichLine(out.toString(), ranges);
    }

    /** boldRanges 를 [from,to) 윈도우로 잘라 윈도우 좌표계로 변환. */
    static List<int[]> sliceRanges(List<int[]> boldRanges, int from, int to) {
        List<int[]> sub = new ArrayList<>();
        if (boldRanges == null) return sub;
        for (int[] r : boldRanges) {
            int s = Math.max(r[0], from);
            int e = Math.min(r[1], to);
            if (e > s) sub.add(new int[]{s - from, e - from});
        }
        return sub;
    }

    /** ASCII-art table 등 bold 미적용 컨텍스트에서 sentinel 제거. */
    static String stripStrongMarkers(String s) {
        if (s == null) return null;
        return s.replace(STRONG_OPEN, "").replace(STRONG_CLOSE, "");
    }

    /** [v14.58] nested Table 모델을 outer cell 안에 평탄화하기 위한 텍스트 변환.
     *  각 row 의 cell 들을 " │ " 로 join, row 들을 "\n" 로 분리.
     *  rowspan/colspan 무시 (visual 표 형식이 아닌 텍스트 보존이 목적).
     *  nested 안에 또 nested 가 있는 경우 재귀. */
    static String flattenNestedTableToText(Table nested) {
        if (nested == null || nested.rows == null || nested.rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (List<TableCell> row : nested.rows) {
            if (row == null || row.isEmpty()) continue;
            StringBuilder rowSb = new StringBuilder();
            for (TableCell c : row) {
                String t;
                if (c.nestedTable != null) {
                    StringBuilder ib = new StringBuilder();
                    if (c.textBefore != null && !c.textBefore.isEmpty()) ib.append(c.textBefore);
                    String inner = flattenNestedTableToText(c.nestedTable);
                    if (!inner.isEmpty()) {
                        if (ib.length() > 0) ib.append(' ');
                        ib.append(inner);
                    }
                    if (c.textAfter != null && !c.textAfter.isEmpty()) {
                        if (ib.length() > 0) ib.append(' ');
                        ib.append(c.textAfter);
                    }
                    t = ib.toString();
                } else {
                    t = (c.text == null) ? "" : c.text;
                }
                t = t.replace('\n', ' ').trim();
                if (rowSb.length() > 0) rowSb.append(" │ ");
                rowSb.append(t);
            }
            if (rowSb.length() > 0) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(rowSb);
            }
        }
        return sb.toString();
    }

    // =========================================================
    //  Unicode 표 fallback (renderBlockRich 보조)
    // =========================================================

    /** 표를 Unicode box drawing 으로 paragraph 라인 list 화 (fallback only). */
    private static List<String> renderTable(Table t) {
        List<String> out = new ArrayList<>();
        if (t.rows.isEmpty()) return out;
        int colCount = 0;
        for (List<TableCell> row : t.rows) colCount = Math.max(colCount, row.size());
        int[] widths = new int[colCount];
        for (List<TableCell> row : t.rows) {
            for (int c = 0; c < row.size(); c++) {
                widths[c] = Math.max(widths[c], visualWidth(row.get(c).text));
            }
        }
        for (int c = 0; c < colCount; c++) widths[c] = Math.max(widths[c], 2);

        out.add(buildBorderLine("┌", "┬", "┐", widths));
        out.add(buildContentLine(t.rows.get(0), widths));
        if (t.rows.size() > 1) {
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

    private static String buildContentLine(List<TableCell> row, int[] widths) {
        StringBuilder sb = new StringBuilder("│");
        for (int c = 0; c < widths.length; c++) {
            String cell = c < row.size() ? row.get(c).text : "";
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
                w += 2; i++;
            } else if (c >= 0x1100 && (c <= 0x115F || (c >= 0x2E80 && c <= 0x9FFF)
                    || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xF900 && c <= 0xFAFF)
                    || (c >= 0xFE30 && c <= 0xFE4F) || (c >= 0xFF00 && c <= 0xFF60)
                    || (c >= 0xFFE0 && c <= 0xFFE6))) {
                w += 2;
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
    //  HTML 표 파싱 + 패턴 정규화
    // =========================================================

    /** [v14.93 task2/3] `<div style="...">` 가 실제 visible border 를 가지는지 판정.
     *  border 관련 property (border / border-top|right|bottom|left / border-style / border-width)
     *  의 값이 단 하나라도 visible (non-zero, non-none, non-hidden, non-transparent) 이면 true.
     *  모든 border 값이 "none"-류이거나 border property 자체가 없으면 false. */
    private static boolean hasVisibleBorderStyle(String s) {
        if (s == null) return false;
        String low = s.toLowerCase();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "border(?:-(?:top|right|bottom|left|style|width|color))?\\s*:\\s*([^;\"']+)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(low);
        while (m.find()) {
            String val = m.group(1).trim();
            // visible 아님 키워드: 0/none/hidden/transparent/initial/inherit
            if (!val.matches("^(?:0(?:px|em|rem)?\\b.*|none\\b.*|hidden\\b.*|transparent\\b.*|initial\\b.*|inherit\\b.*)$")) {
                return true;
            }
        }
        return false;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) { count++; idx += needle.length(); }
        return count;
    }

    /** v14.18: HTML &lt;tr&gt;/&lt;td&gt;/&lt;th&gt; 파싱 시 colspan/rowspan 속성도 추출.
     *  [v14.58] depth-aware cell tokenizer 도입 — 중첩 &lt;table&gt; 안의 &lt;td&gt;/&lt;th&gt;
     *  를 outer cell 종료로 오인식하던 정규식 버그 수정. nested table 도 별도 모델로
     *  보관 (textBefore + nestedTable + textAfter). */
    private static Table parseHtmlTable(String html) {
        Table t = new Table();
        Pattern colspanPat = Pattern.compile("colspan\\s*=\\s*\"?(\\d+)\"?", Pattern.CASE_INSENSITIVE);
        Pattern rowspanPat = Pattern.compile("rowspan\\s*=\\s*\"?(\\d+)\"?", Pattern.CASE_INSENSITIVE);
        Pattern alignAttrPat   = Pattern.compile("\\balign\\s*=\\s*\"?(center|right|left)\"?", Pattern.CASE_INSENSITIVE);
        Pattern textAlignCss  = Pattern.compile("text-align\\s*:\\s*(center|right|left)", Pattern.CASE_INSENSITIVE);
        Pattern divAlignPat   = Pattern.compile("<\\s*div[^>]*\\balign\\s*=\\s*\"?(center|right|left)\"?", Pattern.CASE_INSENSITIVE);

        // [v14.58] depth-aware <tr>...</tr> tokenizer (top-level only)
        List<String> trBlocks = splitTopLevelBlocks(html, "tr");
        for (String trContent : trBlocks) {
            List<TableCell> row = new ArrayList<>();
            // [v14.58] depth-aware <td>/<th> tokenizer — top-level only
            List<String[]> cellTokens = splitCellsAtTopLevel(trContent);
            for (String[] tok : cellTokens) {
                String attrs    = tok[1];
                String cellHtml = tok[2];
                int cs = 1, rs = 1;
                Matcher csM = colspanPat.matcher(attrs);
                if (csM.find()) {
                    try { cs = Math.max(1, Integer.parseInt(csM.group(1))); } catch (NumberFormatException ignored) {}
                }
                Matcher rsM = rowspanPat.matcher(attrs);
                if (rsM.find()) {
                    try { rs = Math.max(1, Integer.parseInt(rsM.group(1))); } catch (NumberFormatException ignored) {}
                }
                // [v14.49] align 속성 결정 우선순위:
                //   1) cell tag 의 align="..." 속성
                //   2) cell tag 의 style="text-align: ...;"
                //   3) cell body 가 <div align="X">...</div> 로 전체 wrap 된 경우만 X 사용
                //   4) null (default left)
                // [v14.90 task1] body 의 <div align> 이 cell 의 일부 paragraph 만 정렬할 때
                //   cell 전체에 적용되어 다른 paragraph 까지 강제 정렬되는 회귀 방지.
                //   "전체 wrap" 이 아닌 partial 정렬은 cell-level 영향 X → 기본 left 정렬.
                String align = null;
                Matcher aM = alignAttrPat.matcher(attrs);
                if (aM.find()) align = aM.group(1).toLowerCase();
                if (align == null) {
                    Matcher taM = textAlignCss.matcher(attrs);
                    if (taM.find()) align = taM.group(1).toLowerCase();
                }
                if (align == null) {
                    String trimmedBody = cellHtml.trim();
                    if (trimmedBody.matches("(?is)^<div\\s[^>]*\\balign\\s*=\\s*\"?(?:center|right|left)\"?[^>]*>.*</div>$")) {
                        Matcher divM = divAlignPat.matcher(trimmedBody);
                        if (divM.find()) align = divM.group(1).toLowerCase();
                    }
                }

                // [v14.58] cell 본문의 top-level <table>...</table> 추출.
                // [STEP2 task2/3] 한 셀 안 모든 top-level nested table 을 캡처해 둘째 이상
                //   nested table 이 drop 되던 것을 방지. 단일(1개)은 기존 경로 그대로.
                java.util.List<int[]> nestedRanges = findAllTopLevelTableRanges(cellHtml);
                TableCell tc;
                if (nestedRanges.size() == 1) {
                    int[] nestedRange = nestedRanges.get(0);
                    String beforeHtml = cellHtml.substring(0, nestedRange[0]);
                    String nestedHtml = cellHtml.substring(nestedRange[0], nestedRange[1]);
                    String afterHtml  = cellHtml.substring(nestedRange[1]);
                    String beforeTxt  = stripInlineMarkup(stripHtmlTags(
                            beforeHtml.replaceAll("(?i)<br\\s*/?\\s*>", "\n")).trim());
                    String afterTxt   = stripInlineMarkup(stripHtmlTags(
                            afterHtml.replaceAll("(?i)<br\\s*/?\\s*>", "\n")).trim());
                    Table nested = parseHtmlTable(nestedHtml);
                    tc = new TableCell("", cs, rs, align);
                    tc.nestedTable = nested;
                    tc.textBefore  = beforeTxt;
                    tc.textAfter   = afterTxt;
                    // fallback text (nested 가 emit 실패 시 그래도 text 살리기 위함)
                    StringBuilder fb = new StringBuilder();
                    if (!beforeTxt.isEmpty()) fb.append(beforeTxt);
                    if (!afterTxt.isEmpty())  { if (fb.length() > 0) fb.append('\n'); fb.append(afterTxt); }
                    tc.text = fb.toString();
                } else if (nestedRanges.size() >= 2) {
                    // [STEP2 task2/3] 셀 안 다중 nested table — 텍스트/표 순서를 블록으로 보존.
                    tc = new TableCell("", cs, rs, align);
                    java.util.List<Object> blocks = new java.util.ArrayList<>();
                    StringBuilder fb = new StringBuilder();
                    Table firstTbl = null;
                    int cursor = 0;
                    for (int[] r : nestedRanges) {
                        String segHtml = cellHtml.substring(cursor, r[0]);
                        String segTxt  = stripInlineMarkup(stripHtmlTags(
                                segHtml.replaceAll("(?i)<br\\s*/?\\s*>", "\n")).trim());
                        if (!segTxt.isEmpty()) {
                            blocks.add(segTxt);
                            if (fb.length() > 0) fb.append('\n');
                            fb.append(segTxt);
                        }
                        Table nt = parseHtmlTable(cellHtml.substring(r[0], r[1]));
                        if (firstTbl == null) firstTbl = nt;
                        blocks.add(nt);
                        cursor = r[1];
                    }
                    String tailHtml = cellHtml.substring(cursor);
                    String tailTxt  = stripInlineMarkup(stripHtmlTags(
                            tailHtml.replaceAll("(?i)<br\\s*/?\\s*>", "\n")).trim());
                    if (!tailTxt.isEmpty()) {
                        blocks.add(tailTxt);
                        if (fb.length() > 0) fb.append('\n');
                        fb.append(tailTxt);
                    }
                    tc.nestedBlocks = blocks;
                    tc.nestedTable  = firstTbl;   // nestedTable != null guard 유지 (emit 경로 진입)
                    tc.text = fb.toString();
                } else {
                    // [v14.91 task1] 셀 본문에서 block-level 정렬 element (<p>, <div>, <h1>-<h6>)
                    //   가 explicit align 을 가지면 segment 정보로 보존. <div border> → 1x1 cell
                    //   에서 <p align="center"> 가 손실되거나, 일반 cell 내 <div align="center">
                    //   가 셀-수준 default 정렬로 평탄화되며 손실되던 회귀 수정.
                    Pattern blockWithAlign = Pattern.compile(
                            "(?is)<(p|div|h[1-6])\\b([^>]*)>(.*?)</\\1>");
                    java.util.List<String> segTextsList  = new java.util.ArrayList<>();
                    java.util.List<String> segAlignsList = new java.util.ArrayList<>();
                    boolean hasExplicitAlign = false;
                    Matcher m = blockWithAlign.matcher(cellHtml);
                    int lastEnd = 0;
                    while (m.find()) {
                        if (m.start() > lastEnd) {
                            String beforeHtml = cellHtml.substring(lastEnd, m.start());
                            String beforePlain = stripInlineMarkup(stripHtmlTags(
                                    beforeHtml.replaceAll("(?i)<br\\s*/?\\s*>", "\n"))).trim();
                            if (!beforePlain.isEmpty()) {
                                segTextsList.add(beforePlain);
                                segAlignsList.add(null);
                            }
                        }
                        String elAttrs = m.group(2);
                        String elBody  = m.group(3);
                        String elAlign = null;
                        Matcher aM2 = alignAttrPat.matcher(elAttrs);
                        if (aM2.find()) elAlign = aM2.group(1).toLowerCase();
                        if (elAlign == null) {
                            Matcher taM2 = textAlignCss.matcher(elAttrs);
                            if (taM2.find()) elAlign = taM2.group(1).toLowerCase();
                        }
                        String elPlain = stripInlineMarkup(stripHtmlTags(
                                elBody.replaceAll("(?i)<br\\s*/?\\s*>", "\n"))).trim();
                        if (!elPlain.isEmpty() || elAlign != null) {
                            segTextsList.add(elPlain);
                            segAlignsList.add(elAlign);
                            if (elAlign != null) hasExplicitAlign = true;
                        }
                        lastEnd = m.end();
                    }
                    if (lastEnd < cellHtml.length()) {
                        String tailHtml = cellHtml.substring(lastEnd);
                        String tailPlain = stripInlineMarkup(stripHtmlTags(
                                tailHtml.replaceAll("(?i)<br\\s*/?\\s*>", "\n"))).trim();
                        if (!tailPlain.isEmpty()) {
                            segTextsList.add(tailPlain);
                            segAlignsList.add(null);
                        }
                    }
                    if (hasExplicitAlign && segTextsList.size() >= 2) {
                        StringBuilder pBuf = new StringBuilder();
                        int[] segStarts = new int[segTextsList.size()];
                        String[] segAligns = new String[segTextsList.size()];
                        for (int pi = 0; pi < segTextsList.size(); pi++) {
                            if (pi > 0) pBuf.append('\n');
                            segStarts[pi] = pBuf.length();
                            segAligns[pi] = segAlignsList.get(pi);
                            pBuf.append(segTextsList.get(pi));
                        }
                        tc = new TableCell(pBuf.toString(), cs, rs, align);
                        tc.segStarts = segStarts;
                        tc.segAligns = segAligns;
                    } else {
                        String body = cellHtml.replaceAll("(?i)<br\\s*/?\\s*>", "\n");
                        String plain = stripHtmlTags(body).trim();
                        tc = new TableCell(stripInlineMarkup(plain), cs, rs, align);
                    }
                }
                row.add(tc);
            }
            // [v14.91 task2] 빈 <tr></tr> 도 보존 — 위 행의 rowspan 이 차지해야 할
            //   placeholder. 이전 v14.90 까지 빈 row 가 drop 되어 다음 행 cells 이
            //   rowspan 점유 영역(c0..cs-1)을 피해 c=cs.. 로 밀려나며 numCols 가
            //   부풀려지던 회귀 (예: 1/15쪽 12줄 표 → 3 cols 가 6 cols 로 변형).
            t.rows.add(row);
        }
        // [v14.66] 4-row flowchart 표 (T3/T4/T7 패턴) 를 T0 (주요절차) 패턴으로
        //   재구성. 사용자 task 요구: "≪ 주요절차 ≫" 표와 동일한 셀 구조로 통일.
        //   변환 대상 패턴 (rs=2 cs=1 title) → T0 패턴 (rs=2 cs=2 title) 자동 감지.
        normalizeFlowchartPattern(t);

        // [v14.67] 3-row 짧은 flowchart 표 (T5 패턴 — < 지방보조사업의 주요 점검 대상 >)
        //   를 T0 패턴으로 재구성. T5 는 3-col grid 의 [empty, title cs=1 rs=2, empty]
        //   + [empty, empty] + [content cs=3] 형태. T0 의 4-col grid layout 과 일치
        //   하도록 title cs=1→2, content cs=3→4 변경 (numCols 자동 3→4).
        normalizeShortFlowchartPattern(t);

        // [v14.59] expandNestedRows: nested table 이 있는 outer cell 의 row 를
        //   [textBefore-row(cs=N)] + [nested rows...] + [textAfter-row(cs=N)] 로
        //   교체. 이렇게 하면 사용자가 원하는 "v14.55~v14.57 펼쳐진 형태" 를
        //   유지하면서 누락된 outer cell 의 앞/뒤 텍스트가 별도 row 로 복원됨.
        // [v14.68] USE_NESTED_HWP_TABLE = true 일 때 expandNestedRows skip — nested
        //   는 addNativeTable 에서 진짜 HWP nested ControlTable 로 emit.
        if (!USE_NESTED_HWP_TABLE) {
            expandNestedRows(t);
        }

        // [v14.60] single-cell row 자동 확장 + 가운데정렬:
        //   row.size()==1 이고 cell.colspan < freeCols 이면 colspan = freeCols,
        //   align = "center". rowspan inheritance 를 grid model 로 추적하여 안전하게
        //   판정 (e.g. row 1 col 0~3 가 row 0 의 rowspan 으로 점유된 경우 freeCols=1).
        //   대상: 등기사항전부증명서 표 (Task 1+2) 같이 outer 가 nested 없이 단일
        //   <th>/<td> 만 있는 row 들. 가운데정렬 강제는 명시적 사용자 요구에 따른 것.
        expandSingleCellRows(t);

        // [v14.59] 타이틀 셀 가운데 배치 패턴 감지 + 변환 (Task 2 본질 수정):
        //   첫 행이 [title-th, empty-th, ...] 형태 (1번째 th 만 비어있지 않음) 이고
        //   타이틀 cs=1 rs=1 인 경우, 단일 cell [title cs=numCols] 로 단순 병합.
        //   numCols 는 expandNestedRows 후의 outer max cols 기준 (nested 가 풀리면
        //   numCols 가 늘어날 수 있으므로 head.colspan 도 그에 맞춰야 grid 폭 일치).
        if (!t.rows.isEmpty()) {
            int finalNumCols = 0;
            for (List<TableCell> row : t.rows) {
                int s = 0;
                for (TableCell c : row) s += Math.max(1, c.colspan);
                if (s > finalNumCols) finalNumCols = s;
            }
            if (finalNumCols < 1) finalNumCols = 1;

            List<TableCell> first = t.rows.get(0);
            if (first.size() >= 2) {
                TableCell head = first.get(0);
                String headPlain = (head.text == null) ? "" : stripStrongMarkers(head.text).trim();
                if (!headPlain.isEmpty() && !headPlain.equals("​")
                        && head.colspan == 1 && head.rowspan == 1) {
                    boolean restEmpty = true;
                    for (int k = 1; k < first.size(); k++) {
                        TableCell c = first.get(k);
                        String t2 = c.text;
                        String s2 = (t2 == null) ? "" : stripStrongMarkers(t2).trim();
                        if (!s2.isEmpty() && !s2.equals("​")) { restEmpty = false; break; }
                        if (c.nestedTable != null) { restEmpty = false; break; }
                    }
                    if (restEmpty) {
                        head.colspan = finalNumCols;
                        head.align = "center";
                        // 첫 행의 나머지 cells 제거 (단일 cell 로 단순화)
                        while (first.size() > 1) first.remove(1);
                    }
                }
            }
        }
        return t;
    }

    /** [v14.66] 4-row flowchart 표를 T0 (주요절차) 패턴으로 정규화.
     *  대상 패턴 (T3/T4/T7 = ※ 용도외/보조사업자/실적보고 표):
     *    row 0: [empty cs=1, title rs=2 cs=1, empty cs=2]
     *    row 1: [empty cs=1, empty cs=2]
     *    row 2: [empty cs=3, empty cs=1]
     *    row 3: [content cs=4]
     *  T0 패턴 (≪ 주요절차 ≫):
     *    row 0: [empty cs=1, title rs=2 cs=2, empty cs=1]
     *    row 1: [empty cs=1, empty cs=1]
     *    row 2: [empty cs=2, empty cs=2]
     *    row 3: [content cs=4]
     *  변환: title cell 을 cs=2 로 확장 (가운데 large), 양옆/아래 빈 cells 의 cs 재조정.
     *  텍스트 손실 없음 (변경 대상 cells 모두 empty 검증). */
    private static void normalizeFlowchartPattern(Table t) {
        if (t == null || t.rows == null || t.rows.size() != 4) return;
        List<TableCell> r0 = t.rows.get(0);
        List<TableCell> r1 = t.rows.get(1);
        List<TableCell> r2 = t.rows.get(2);
        List<TableCell> r3 = t.rows.get(3);
        if (r0 == null || r1 == null || r2 == null || r3 == null) return;
        if (r0.size() != 3 || r1.size() != 2 || r2.size() != 2 || r3.size() != 1) return;

        TableCell r0Empty1 = r0.get(0);
        TableCell r0Title  = r0.get(1);
        TableCell r0After  = r0.get(2);
        TableCell r1Empty1 = r1.get(0);
        TableCell r1After  = r1.get(1);
        TableCell r2First  = r2.get(0);
        TableCell r2Second = r2.get(1);
        TableCell r3Cell   = r3.get(0);

        // 패턴 cs/rs 검출
        if (r0Empty1.colspan != 1) return;
        if (r0Title.rowspan != 2 || r0Title.colspan != 1) return;
        if (r0After.colspan != 2) return;
        if (r1Empty1.colspan != 1) return;
        if (r1After.colspan != 2) return;
        if (r2First.colspan != 3 || r2Second.colspan != 1) return;
        if (r3Cell.colspan != 4) return;

        // r0Title 은 non-empty (title), 나머지 cells 모두 empty
        String titleText = r0Title.text == null ? "" : stripStrongMarkers(r0Title.text).trim();
        if (titleText.isEmpty() || titleText.equals("​")) return;
        if (!isFlowchartCellEmpty(r0Empty1)) return;
        if (!isFlowchartCellEmpty(r0After))  return;
        if (!isFlowchartCellEmpty(r1Empty1)) return;
        if (!isFlowchartCellEmpty(r1After))  return;
        if (!isFlowchartCellEmpty(r2First))  return;
        if (!isFlowchartCellEmpty(r2Second)) return;

        // T0 패턴으로 cs 재조정 (text 손실 없음 — 위 검증)
        r0Title.colspan  = 2;   // rs=2 cs=1 → rs=2 cs=2 (title 가운데 large)
        r0After.colspan  = 1;   // 2 → 1 (오른쪽 끝 empty)
        r1After.colspan  = 1;   // 2 → 1 (오른쪽 끝 empty)
        r2First.colspan  = 2;   // 3 → 2 (왼쪽 절반)
        r2Second.colspan = 2;   // 1 → 2 (오른쪽 절반)
    }

    private static boolean isFlowchartCellEmpty(TableCell c) {
        if (c == null) return true;
        if (c.nestedTable != null) return false;
        String s = c.text == null ? "" : stripStrongMarkers(c.text).trim();
        return s.isEmpty() || s.equals("​");
    }

    /** [v14.67] 3-row 짧은 flowchart 표를 T0 (주요절차) 패턴으로 정규화.
     *  대상 패턴 (T5 = < 지방보조사업의 주요 점검 대상 > 표):
     *    row 0: [empty cs=1, title rs=2 cs=1, empty cs=1]   ← 3-col grid (numCols=3)
     *    row 1: [empty cs=1, empty cs=1]                     ← b col inherited
     *    row 2: [content cs=3]                               ← 3-col 채우는 single content
     *  T0 패턴 (4-col grid):
     *    row 0: [empty cs=1, title rs=2 cs=2, empty cs=1]   ← 4-col grid (numCols=4)
     *    row 1: [empty cs=1, empty cs=1]
     *    row 2 (이후): content cs=4 등
     *  변환: title cs=1→2, content cs=3→4. numCols 가 자동 3→4 로 변경되어
     *    col widths 가 [10488, 10488, 10488, 10490] (4-col fixed) 로 emit.
     *    master cell view: [col 0=10488(a1), col 1+2 cs=2=20976(b1), col 3=10490(d1)]
     *    → T0 와 동일 layout. */
    private static void normalizeShortFlowchartPattern(Table t) {
        if (t == null || t.rows == null || t.rows.size() != 3) return;
        List<TableCell> r0 = t.rows.get(0);
        List<TableCell> r1 = t.rows.get(1);
        List<TableCell> r2 = t.rows.get(2);
        if (r0 == null || r1 == null || r2 == null) return;
        if (r0.size() != 3 || r1.size() != 2 || r2.size() != 1) return;

        TableCell r0Empty1 = r0.get(0);
        TableCell r0Title  = r0.get(1);
        TableCell r0After  = r0.get(2);
        TableCell r1Empty1 = r1.get(0);
        TableCell r1After  = r1.get(1);
        TableCell r2Cell   = r2.get(0);

        if (r0Empty1.colspan != 1) return;
        if (r0Title.rowspan != 2 || r0Title.colspan != 1) return;
        if (r0After.colspan != 1) return;
        if (r1Empty1.colspan != 1) return;
        if (r1After.colspan != 1) return;
        if (r2Cell.colspan != 3) return;

        String titleText = r0Title.text == null ? "" : stripStrongMarkers(r0Title.text).trim();
        if (titleText.isEmpty() || titleText.equals("​")) return;

        if (!isFlowchartCellEmpty(r0Empty1)) return;
        if (!isFlowchartCellEmpty(r0After))  return;
        if (!isFlowchartCellEmpty(r1Empty1)) return;
        if (!isFlowchartCellEmpty(r1After))  return;
        // r2Cell 은 content (non-empty 가능, 변환 후에도 텍스트 보존)

        // T0 패턴으로 cs 재조정 — 3-col grid → 4-col grid 자동 확장
        r0Title.colspan = 2;   // 1 → 2 (title 가운데 large, b1+c1 합쳐짐)
        r2Cell.colspan = 4;    // 3 → 4 (content 페이지 폭 가득)
    }

    /** [v14.60] single-cell row 의 colspan 을 자동으로 free grid 폭만큼 확장 +
     *  가운데정렬 강제. rowspan inheritance 를 grid 모델로 추적하여 안전하게
     *  판정 (cs == freeCols 인 자연 fit row 는 건드리지 않음). 단일 cell 이 grid 의
     *  남은 폭보다 좁을 때만 발동. */
    private static void expandSingleCellRows(Table t) {
        if (t == null || t.rows == null || t.rows.isEmpty()) return;
        int numRows = t.rows.size();
        // numCols: 모든 row 의 cell colspan 합 max
        int numCols = 0;
        for (List<TableCell> row : t.rows) {
            if (row == null) continue;
            int s = 0;
            for (TableCell c : row) s += Math.max(1, c.colspan);
            if (s > numCols) numCols = s;
        }
        if (numCols < 2) return;
        int gridCols = numCols + 4;
        boolean[][] occupied = new boolean[numRows][gridCols];
        for (int r = 0; r < numRows; r++) {
            List<TableCell> row = t.rows.get(r);
            if (row == null) continue;
            // freeCols: 이 row 에서 inherited (rowspan from prev row) 가 아닌 컬럼 수
            int inherited = 0;
            for (int c = 0; c < numCols; c++) if (occupied[r][c]) inherited++;
            int freeCols = numCols - inherited;
            // 단일 cell 이고 cs<freeCols, rs=1, 그리고 nested 없음 → 확장
            if (row.size() == 1 && freeCols >= 2) {
                TableCell only = row.get(0);
                if (only != null && only.colspan < freeCols && only.rowspan == 1
                        && only.nestedTable == null) {
                    only.colspan = freeCols;
                    only.align = "center";
                }
            }
            // grid 점유 적용
            int c = 0;
            for (TableCell cell : row) {
                while (c < gridCols && occupied[r][c]) c++;
                if (c >= gridCols) break;
                int cs = Math.max(1, cell.colspan);
                int rs = Math.max(1, cell.rowspan);
                for (int dr = 0; dr < rs && r + dr < numRows; dr++) {
                    for (int dc = 0; dc < cs && c + dc < gridCols; dc++) {
                        occupied[r + dr][c + dc] = true;
                    }
                }
                c += cs;
            }
        }
    }

    /** [v14.59] nested table 이 있는 outer cell 의 row 를 풀어서 outer table 의
     *  multiple row 로 교체. textBefore/textAfter 도 단일 cell row(cs=nestedCols) 로
     *  추가 emit. 결과: nested 표 cells 가 outer 의 행처럼 펼쳐짐 + outer cell 안의
     *  앞/뒤 텍스트가 별도 row 로 보존 (사용자가 v14.55~v14.57 에서 익숙해진 레이아웃). */
    private static void expandNestedRows(Table t) {
        if (t == null || t.rows == null) return;
        // 1) 재귀: nested 안에 또 nested 가 있는 경우 먼저 평탄화
        for (List<TableCell> row : t.rows) {
            if (row == null) continue;
            for (TableCell c : row) {
                if (c.nestedTable != null) expandNestedRows(c.nestedTable);
            }
        }
        // 2) row 단위로 nested 가 있으면 풀어서 새 row 들로 교체
        List<List<TableCell>> newRows = new ArrayList<>();
        for (List<TableCell> row : t.rows) {
            if (row == null) { newRows.add(row); continue; }
            TableCell nestedCell = null;
            for (TableCell c : row) {
                if (c.nestedTable != null) { nestedCell = c; break; }
            }
            if (nestedCell == null) {
                newRows.add(row);
                continue;
            }
            // nested 의 max cols 산출 (이미 재귀로 expand 된 상태)
            int nestedCols = 0;
            for (List<TableCell> nr : nestedCell.nestedTable.rows) {
                int s = 0;
                for (TableCell c : nr) s += Math.max(1, c.colspan);
                if (s > nestedCols) nestedCols = s;
            }
            if (nestedCols < 1) nestedCols = 1;
            // textBefore row
            if (nestedCell.textBefore != null && !nestedCell.textBefore.isEmpty()) {
                List<TableCell> beforeRow = new ArrayList<>();
                beforeRow.add(new TableCell(nestedCell.textBefore, nestedCols, 1, "left"));
                newRows.add(beforeRow);
            }
            // nested 의 모든 row 를 outer 에 그대로 추가
            for (List<TableCell> nr : nestedCell.nestedTable.rows) {
                newRows.add(nr);
            }
            // textAfter row
            if (nestedCell.textAfter != null && !nestedCell.textAfter.isEmpty()) {
                List<TableCell> afterRow = new ArrayList<>();
                afterRow.add(new TableCell(nestedCell.textAfter, nestedCols, 1, "left"));
                newRows.add(afterRow);
            }
        }
        t.rows.clear();
        t.rows.addAll(newRows);
    }

    /** [v14.58] depth-aware top-level block tokenizer. tagName 의 시작/종료 태그를
     *  찾되, 같은 이름의 더 깊은 nested 태그를 건너뛴다. 반환: 각 블록의 inner content. */
    private static List<String> splitTopLevelBlocks(String html, String tagName) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        String openLow  = "<" + tagName;
        String closeLow = "</" + tagName;
        String low = html.toLowerCase();
        int len = html.length();
        int i = 0;
        while (i < len) {
            int start = low.indexOf(openLow, i);
            if (start < 0) break;
            // 정확히 <tagName 다음 글자가 ' ', '>' 인지 확인 (e.g. <tr 다음에 공백 또는 >)
            int afterTag = start + openLow.length();
            if (afterTag >= len) break;
            char nx = html.charAt(afterTag);
            if (nx != ' ' && nx != '>' && nx != '\t' && nx != '\n' && nx != '/') {
                i = afterTag;
                continue;
            }
            int contentStart = html.indexOf('>', afterTag);
            if (contentStart < 0) break;
            contentStart++;
            // depth-aware close 찾기
            int depth = 1;
            int scan = contentStart;
            int contentEnd = -1;
            while (scan < len) {
                int nextOpen  = low.indexOf(openLow,  scan);
                int nextClose = low.indexOf(closeLow, scan);
                if (nextClose < 0) break;
                if (nextOpen >= 0 && nextOpen < nextClose) {
                    int afterTag2 = nextOpen + openLow.length();
                    if (afterTag2 < len) {
                        char nx2 = html.charAt(afterTag2);
                        if (nx2 == ' ' || nx2 == '>' || nx2 == '\t' || nx2 == '\n' || nx2 == '/') {
                            depth++;
                            int gt = html.indexOf('>', afterTag2);
                            scan = (gt < 0) ? nextOpen + openLow.length() : gt + 1;
                            continue;
                        }
                    }
                    scan = nextOpen + openLow.length();
                    continue;
                }
                // close
                int afterTag3 = nextClose + closeLow.length();
                if (afterTag3 < len) {
                    char nx3 = html.charAt(afterTag3);
                    if (nx3 != '>' && nx3 != ' ' && nx3 != '\t' && nx3 != '\n') {
                        scan = nextClose + closeLow.length();
                        continue;
                    }
                }
                depth--;
                if (depth == 0) {
                    contentEnd = nextClose;
                    int gt = html.indexOf('>', afterTag3);
                    i = (gt < 0) ? nextClose + closeLow.length() : gt + 1;
                    break;
                }
                scan = nextClose + closeLow.length();
            }
            if (contentEnd < 0) break;
            out.add(html.substring(contentStart, contentEnd));
        }
        return out;
    }

    /** [v14.58] tr-content 안에서 top-level &lt;td&gt;/&lt;th&gt; 셀만 토큰화.
     *  중첩 &lt;table&gt;...&lt;/table&gt; 의 cell 들은 건너뛴다.
     *  반환: [{tagName ("td"|"th"), attrs, innerHtml}, ...]. */
    private static List<String[]> splitCellsAtTopLevel(String trContent) {
        List<String[]> out = new ArrayList<>();
        if (trContent == null || trContent.isEmpty()) return out;
        String low = trContent.toLowerCase();
        int len = trContent.length();
        int i = 0;
        int tableDepth = 0;
        while (i < len) {
            // <table 검사 (depth 증가)
            if (i + 6 <= len && low.charAt(i) == '<' && low.startsWith("table", i + 1)) {
                char nx = (i + 6 < len) ? low.charAt(i + 6) : '\0';
                if (nx == ' ' || nx == '>' || nx == '\t' || nx == '\n') {
                    tableDepth++;
                    int gt = trContent.indexOf('>', i + 6);
                    i = (gt < 0) ? i + 6 : gt + 1;
                    continue;
                }
            }
            // </table 검사 (depth 감소)
            if (i + 7 <= len && low.charAt(i) == '<' && low.charAt(i + 1) == '/'
                    && low.startsWith("table", i + 2)) {
                char nx = (i + 7 < len) ? low.charAt(i + 7) : '\0';
                if (nx == ' ' || nx == '>' || nx == '\t' || nx == '\n') {
                    if (tableDepth > 0) tableDepth--;
                    int gt = trContent.indexOf('>', i + 7);
                    i = (gt < 0) ? i + 7 : gt + 1;
                    continue;
                }
            }
            // depth>0 이면 cell 열림 무시
            if (tableDepth > 0) { i++; continue; }
            // <td 또는 <th 검사 (top-level cell 시작)
            if (i + 3 <= len && low.charAt(i) == '<' && low.charAt(i + 1) == 't'
                    && (low.charAt(i + 2) == 'd' || low.charAt(i + 2) == 'h')) {
                char nx = (i + 3 < len) ? low.charAt(i + 3) : '\0';
                if (nx == ' ' || nx == '>' || nx == '\t' || nx == '\n') {
                    String tag = (low.charAt(i + 2) == 'd') ? "td" : "th";
                    int gt = trContent.indexOf('>', i + 3);
                    if (gt < 0) break;
                    String attrs = trContent.substring(i + 3, gt);
                    int contentStart = gt + 1;
                    // depth-aware 종료 태그 검색 — </td> 또는 </th> 매칭하되 nested <table> 건너뜀
                    int contentEnd = findCellClose(trContent, contentStart, tag);
                    if (contentEnd < 0) break;
                    String inner = trContent.substring(contentStart, contentEnd);
                    out.add(new String[]{tag, attrs, inner});
                    int closeTagEnd = trContent.indexOf('>', contentEnd);
                    i = (closeTagEnd < 0) ? contentEnd + 5 : closeTagEnd + 1;
                    continue;
                }
            }
            i++;
        }
        return out;
    }

    /** [v14.58] cell 의 종료 태그 (&lt;/td&gt; 또는 &lt;/th&gt;) 위치 검색.
     *  중첩 &lt;table&gt; 안의 &lt;/td&gt;/&lt;/th&gt; 는 건너뜀. 반환: 종료 태그 시작 인덱스. */
    private static int findCellClose(String s, int from, String tag) {
        String low = s.toLowerCase();
        String closeLow = "</" + tag;
        int len = s.length();
        int i = from;
        int tableDepth = 0;
        while (i < len) {
            if (i + 6 <= len && low.charAt(i) == '<' && low.startsWith("table", i + 1)) {
                char nx = (i + 6 < len) ? low.charAt(i + 6) : '\0';
                if (nx == ' ' || nx == '>' || nx == '\t' || nx == '\n') {
                    tableDepth++;
                    int gt = s.indexOf('>', i + 6);
                    i = (gt < 0) ? i + 6 : gt + 1;
                    continue;
                }
            }
            if (i + 7 <= len && low.charAt(i) == '<' && low.charAt(i + 1) == '/'
                    && low.startsWith("table", i + 2)) {
                char nx = (i + 7 < len) ? low.charAt(i + 7) : '\0';
                if (nx == ' ' || nx == '>' || nx == '\t' || nx == '\n') {
                    if (tableDepth > 0) tableDepth--;
                    int gt = s.indexOf('>', i + 7);
                    i = (gt < 0) ? i + 7 : gt + 1;
                    continue;
                }
            }
            if (tableDepth == 0
                    && i + closeLow.length() <= len
                    && low.startsWith(closeLow, i)) {
                int afterTag = i + closeLow.length();
                if (afterTag >= len) return i;
                char nx = low.charAt(afterTag);
                if (nx == '>' || nx == ' ' || nx == '\t' || nx == '\n') return i;
            }
            i++;
        }
        return -1;
    }

    /** [v14.58] cellHtml 안의 첫 번째 top-level &lt;table&gt;...&lt;/table&gt; 범위.
     *  반환: int[]{start, endExclusive} 또는 null (없을 때). */
    /** [STEP2 task2/3] cellHtml 안의 모든 top-level <table>..</table> 범위(절대 인덱스)를 순서대로.
     *  findFirstTopLevelTableRange(검증된 depth-aware 스캐너)를 나머지 구간에 반복 적용. */
    private static java.util.List<int[]> findAllTopLevelTableRanges(String cellHtml) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (cellHtml == null || cellHtml.isEmpty()) return out;
        int base = 0;
        String rest = cellHtml;
        while (!rest.isEmpty()) {
            int[] r = findFirstTopLevelTableRange(rest);
            if (r == null) break;
            out.add(new int[]{base + r[0], base + r[1]});
            base += r[1];
            rest = cellHtml.substring(base);
        }
        return out;
    }

    private static int[] findFirstTopLevelTableRange(String cellHtml) {
        if (cellHtml == null || cellHtml.isEmpty()) return null;
        String low = cellHtml.toLowerCase();
        int len = cellHtml.length();
        int i = 0;
        while (i < len) {
            if (i + 6 <= len && low.charAt(i) == '<' && low.startsWith("table", i + 1)) {
                char nx = (i + 6 < len) ? low.charAt(i + 6) : '\0';
                if (nx == ' ' || nx == '>' || nx == '\t' || nx == '\n') {
                    int start = i;
                    int depth = 1;
                    int gt = cellHtml.indexOf('>', i + 6);
                    int scan = (gt < 0) ? i + 6 : gt + 1;
                    while (scan < len && depth > 0) {
                        if (scan + 6 <= len && low.charAt(scan) == '<' && low.startsWith("table", scan + 1)) {
                            char nx2 = (scan + 6 < len) ? low.charAt(scan + 6) : '\0';
                            if (nx2 == ' ' || nx2 == '>' || nx2 == '\t' || nx2 == '\n') {
                                depth++;
                                int g2 = cellHtml.indexOf('>', scan + 6);
                                scan = (g2 < 0) ? scan + 6 : g2 + 1;
                                continue;
                            }
                        }
                        if (scan + 7 <= len && low.charAt(scan) == '<' && low.charAt(scan + 1) == '/'
                                && low.startsWith("table", scan + 2)) {
                            char nx2 = (scan + 7 < len) ? low.charAt(scan + 7) : '\0';
                            if (nx2 == ' ' || nx2 == '>' || nx2 == '\t' || nx2 == '\n') {
                                depth--;
                                int g2 = cellHtml.indexOf('>', scan + 7);
                                if (depth == 0) {
                                    int endEx = (g2 < 0) ? scan + 7 : g2 + 1;
                                    return new int[]{start, endEx};
                                }
                                scan = (g2 < 0) ? scan + 7 : g2 + 1;
                                continue;
                            }
                        }
                        scan++;
                    }
                    return null;
                }
            }
            i++;
        }
        return null;
    }

    private static List<TableCell> splitPipeRow(String line) {
        if (line.startsWith("|")) line = line.substring(1);
        if (line.endsWith("|")) line = line.substring(0, line.length() - 1);
        String[] cells = line.split("\\|", -1);
        List<TableCell> out = new ArrayList<>();
        for (String c : cells) out.add(new TableCell(stripInlineMarkup(c.trim())));
        return out;
    }

    /**
     * v14.16: <strong>...</strong> 와 markdown bold (**...**, __...__) 를 plain
     * text 화하되, 그 위치를 sentinel 로 보존한다. 이후 extractStrong() 가 sentinel
     * 을 (start,end) 범위로 변환한다.
     */
    private static String stripInlineMarkup(String s) {
        if (s == null || s.isEmpty()) return "";
        // 0) <strong> / <b> tag → sentinel (대소문자 무시)
        s = s.replaceAll("(?i)<\\s*strong[^>]*>", STRONG_OPEN);
        s = s.replaceAll("(?i)<\\s*/\\s*strong\\s*>", STRONG_CLOSE);
        s = s.replaceAll("(?i)<\\s*b\\s*>", STRONG_OPEN);
        s = s.replaceAll("(?i)<\\s*/\\s*b\\s*>", STRONG_CLOSE);

        // v14.19: base64 data: URI 가 본문에 그대로 노출되는 문제 방지.
        // data: 로 시작하면 URL 부분을 노출하지 않고 alt text 만 표시.
        {
            Matcher m = Pattern.compile("!\\[([^\\]]*)\\]\\(([^\\)]*)\\)").matcher(s);
            StringBuffer buf = new StringBuffer();
            while (m.find()) {
                String alt = m.group(1);
                String url = m.group(2);
                String repl;
                if (url != null && url.trim().toLowerCase().startsWith("data:")) {
                    repl = "[이미지: " + (alt == null ? "" : alt) + "]";
                } else {
                    repl = "[이미지: " + url + " — " + (alt == null ? "" : alt) + "]";
                }
                m.appendReplacement(buf, Matcher.quoteReplacement(repl));
            }
            m.appendTail(buf);
            s = buf.toString();
        }
        s = s.replaceAll("\\[([^\\]]*)\\]\\(([^\\)]*)\\)", "$1");
        // markdown bold: **...**, __...__  → sentinel-wrap (단, sentinel 자체에는 ** 가 없음)
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", STRONG_OPEN + "$1" + STRONG_CLOSE);
        s = s.replaceAll("__([^_]+)__", STRONG_OPEN + "$1" + STRONG_CLOSE);
        s = s.replaceAll("(?<!\\*)\\*(?!\\*)([^*]+)(?<!\\*)\\*(?!\\*)", "$1");
        s = s.replaceAll("(?<!_)_(?!_)([^_]+)(?<!_)_(?!_)", "$1");
        s = s.replaceAll("~~([^~]+)~~", "$1");
        s = s.replaceAll("`([^`]+)`", "$1");
        s = s.replaceAll("\\\\([*_\\[\\]#])", "$1");
        // [v14.49] HTML tag 제거 시 literal "< 텍스트 >" 보존 — 이전 v14.48 까지
        //   <[^>]+> 가 "< 지방보조사업의 주요 점검 대상(관리기준 제20조) >" 같이 첫 글자가
        //   공백/한글/숫자인 literal 텍스트 전체를 tag 로 잘못 인식해 통째로 strip → 셀
        //   내용 누락. 수정: 첫 글자가 letter / "/" / "!" / "?" 일 때만 tag 로 인식.
        s = s.replaceAll("<[a-zA-Z/!?][^>]*>", "");
        s = s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&nbsp;", " ");
        return s;
    }

    private static String stripHtmlTags(String s) {
        // [v14.49] regex 수정 — literal "< 텍스트 >" 가 tag 로 잘못 인식되지 않도록
        // 첫 글자가 letter / "/" / "!" / "?" 일 때만 tag 로 처리.
        return s.replaceAll("<[a-zA-Z/!?][^>]*>", "")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&nbsp;", " ");
    }
}
