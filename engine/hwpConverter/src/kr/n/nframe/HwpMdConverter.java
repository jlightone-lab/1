package kr.n.nframe;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import kr.n.nframe.hwplib.model.BinDataItem;
import kr.n.nframe.hwplib.model.CharShape;
import kr.n.nframe.hwplib.model.CharShapeRef;
import kr.n.nframe.hwplib.model.Control;
import kr.n.nframe.hwplib.model.CtrlField;
import kr.n.nframe.hwplib.model.CtrlPicture;
import kr.n.nframe.hwplib.model.CtrlTable;
import kr.n.nframe.hwplib.model.HwpDocument;
import kr.n.nframe.hwplib.model.ParaShape;
import kr.n.nframe.hwplib.model.Paragraph;
import kr.n.nframe.hwplib.model.Section;
import kr.n.nframe.hwplib.model.Style;
import kr.n.nframe.hwplib.model.TableCell;
import kr.n.nframe.hwplib.reader.HwpxReader;
import kr.n.nframe.hwplib.constants.CtrlId;
import kr.n.nframe.mdlib.MdStructureConverter;

/**
 * v14.2: HWP/HWPX → Markdown 단방향 변환기. 출력은 깨끗한 GFM 위주.
 * 인라인 style/align/padding/TOC 점선 등 메타데이터성 표현은 폐기됨 (Markdown 손실 포맷).
 *
 * <p>제공 API
 * <ul>
 *   <li>{@link #convertHwpxToMarkdown(String, String)} — HWPX → MD</li>
 *   <li>{@link #convertHwpToMarkdown(String, String)}  — HWP → MD (내부적으로 hwp2hwpx 경유)</li>
 *   <li>{@link #convertMarkdownToHwp(String, String)}  — MD → HWP (MdStructureConverter 로 위임)</li>
 *   <li>{@link #convertMarkdownToHwpx(String, String)} — MD → HWPX (MdStructureConverter 로 위임)</li>
 * </ul>
 *
 * <p>변환 규칙
 * <ul>
 *   <li>Style.englishName/localName 이 Heading/개요/제목 계열이면 {@code #}~{@code ######} 로 매핑</li>
 *   <li>CharShape.property 비트: italic(0)=*x*, bold(1)=**x**, underline(2-3)은 폐기 (MD 미지원),
 *       strike(18-20)=~~x~~ (GFM)</li>
 *   <li>CtrlTable → GFM 표 (단순 표) 또는 HTML &lt;table&gt; (중첩 표·병합 셀); style/border/width/align 속성 미부착</li>
 *   <li>CtrlPicture(shapeType=="pic") → {@code ![alt](path)}; 바이너리는 &lt;base&gt;_assets/ 에 추출</li>
 *   <li>CtrlField(HYPERLINK) → {@code [text](url)}</li>
 *   <li>기타 확장 컨트롤(머리말/꼬리말/각주/페이지번호/루비 등)은 변환 생략</li>
 * </ul>
 *
 * <p>한계: HWP 에는 존재하지만 Markdown 에는 정확히 대응되지 않는 요소(글꼴/크기/색상,
 * 문단 정렬, 각주, 페이지 나눔, 밑줄, TOC 점선 등)는 폐기된다. MD → HWP/HWPX 방향은 손실된 정보를
 * 복원하지 않으며 {@link MdStructureConverter} 의 구조 기반 변환에 위임한다.
 */
public class HwpMdConverter {

    // ---- CharShape.property 비트 (HeaderParser.buildCharProperty 와 동일) ----
    private static final long CHARPROP_ITALIC       = 1L;
    private static final long CHARPROP_BOLD         = 1L << 1;
    private static final long CHARPROP_UL_TYPE_MASK = 0x3L << 2;
    private static final long CHARPROP_STRIKE_MASK  = 0x7L << 18;

    // ---- HWP 인라인 컨트롤 문자 (HWP 5.0 §5.4, 표 6) ----
    private static final char CH_SECD         = 0x0002;
    private static final char CH_FIELD_BEGIN  = 0x0003;
    private static final char CH_FIELD_END    = 0x0004;
    private static final char CH_INLINE_END   = 0x0005;
    private static final char CH_TAB          = 0x0009;
    private static final char CH_NEWLINE      = 0x000A;
    private static final char CH_OBJECT       = 0x000B;
    private static final char CH_PARA_END     = 0x000D;
    private static final char CH_HIDDEN_CMT   = 0x000F;
    private static final char CH_HDR_FTR      = 0x0010;
    private static final char CH_FOOTNOTE     = 0x0011;
    private static final char CH_AUTO_NUMBER  = 0x0012;
    private static final char CH_PAGE_NUM_POS = 0x0015;
    private static final char CH_BOOKMARK     = 0x0016;
    private static final char CH_RUBY_TEXT    = 0x0017;
    private static final char CH_NBSPACE      = 0x0018;
    private static final char CH_FWSPACE      = 0x0019;

    // =========================================================
    //  v14.44 (task2): MD 단독 이동 시 자체 복원 기능 — v13.41 거동 복원.
    //   · WRITE_SIDECAR : {md}.origin.hwp[x] 사이드카 파일 생성
    //   · EMBED_ORIGIN  : MD 끝에 base64 mime-encoded HWP/HWPX 추가 (HTML 주석)
    //  v14.48 (task3): WRITE_SIDECAR 기본값 false 로 변경. base64 임베드만으로
    //   MD 단독 round-trip 이 가능하므로 사이드카는 잡음으로 작용. 필요 시
    //   -Dhwp.md.sidecar=true 로 재활성화 가능. EMBED_ORIGIN 은 기본 true 유지.
    //  (HwpConverter CLI 의 --no-sidecar/--no-embed 는 v14.0 이후 deprecated
    //   처리되어 효과 없음 — 비활성화/활성화는 -D 옵션으로 사용.)
    // =========================================================
    private static volatile boolean WRITE_SIDECAR =
            "true".equalsIgnoreCase(System.getProperty("hwp.md.sidecar", "false"));
    // [v14.87 task5] 기본값 false 로 변경 — 사용자 요청: MD 끝의 base64 임베드 블록은
    //   문서 noise 로 작용. 필요 시 -Dhwp.md.embed=true 로 재활성화.
    private static volatile boolean EMBED_ORIGIN  =
            "true".equalsIgnoreCase(System.getProperty("hwp.md.embed",   "false"));

    public static void setSidecarEnabled(boolean v) { WRITE_SIDECAR = v; }
    public static boolean isSidecarEnabled()        { return WRITE_SIDECAR; }
    public static void setEmbedOriginEnabled(boolean v) { EMBED_ORIGIN = v; }
    public static boolean isEmbedOriginEnabled()        { return EMBED_ORIGIN; }

    // =========================================================
    //  공개 API
    // =========================================================

    /** HWPX → Markdown 변환. */
    public void convertHwpxToMarkdown(String filePathHwpx, String filePathMd) throws Exception {
        ensureInputExists(filePathHwpx, ".hwpx");
        ensureDistinctPaths(filePathHwpx, filePathMd);
        filePathMd = kr.n.nframe.newfeature.OutputNaming.unique(filePathMd); // v16t42 비덮어쓰기
        System.out.println("[HwpMdConverter] Reading HWPX: " + filePathHwpx);
        HwpDocument doc = HwpxReader.read(filePathHwpx);
        logDocStats(doc);

        System.out.println("[HwpMdConverter] Writing Markdown: " + filePathMd);
        writeMarkdown(doc, filePathMd);
        // v14.44 (task2): v13.41 거동 — 사이드카 + base64 임베드.
        writeOriginSidecar(filePathHwpx, filePathMd, ".hwpx");
        appendEmbeddedOrigin(filePathMd, filePathHwpx, "hwpx");
        System.out.println("[HwpMdConverter] Conversion complete.");
    }

    /**
     * HWP → Markdown 변환 (내부적으로 임시 HWPX 경유).
     */
    public void convertHwpToMarkdown(String filePathHwp, String filePathMd) throws Exception {
        ensureInputExists(filePathHwp, ".hwp");
        ensureDistinctPaths(filePathHwp, filePathMd);
        filePathMd = kr.n.nframe.newfeature.OutputNaming.unique(filePathMd); // v16t42 비덮어쓰기
        Path tmpHwpx = Files.createTempFile("hwpmd-", ".hwpx");
        try {
            System.out.println("[HwpMdConverter] HWP → HWPX (temp): " + tmpHwpx);
            HwpConverter converter = new HwpConverter();
            converter.convertHwpToHwpx(filePathHwp, tmpHwpx.toString());

            System.out.println("[HwpMdConverter] Reading intermediate HWPX ...");
            HwpDocument doc = HwpxReader.read(tmpHwpx.toString());
            logDocStats(doc);

            System.out.println("[HwpMdConverter] Writing Markdown: " + filePathMd);
            writeMarkdown(doc, filePathMd);
            // v14.44 (task2): v13.41 거동 — 사이드카 + base64 임베드 (원본 HWP 사용).
            writeOriginSidecar(filePathHwp, filePathMd, ".hwp");
            appendEmbeddedOrigin(filePathMd, filePathHwp, "hwp");
            System.out.println("[HwpMdConverter] Conversion complete.");
        } finally {
            try { Files.deleteIfExists(tmpHwpx); } catch (IOException ignored) {}
        }
    }

    /**
     * v14.44 (task2): 원본 HWP/HWPX 를 {md}.origin.hwp[x] 로 복사. v13.41 거동 복원.
     * MD 와 같은 디렉터리에 저장되어 MD → HWP 역변환 시 이 사이드카가 우선 적용된다.
     */
    private static void writeOriginSidecar(String srcPath, String mdPath, String dotExt) {
        if (!WRITE_SIDECAR) return;
        try {
            Path src = Paths.get(srcPath);
            if (!Files.exists(src)) return;
            String sidecar = mdPath + ".origin" + dotExt;
            Files.copy(src, Paths.get(sidecar),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[HwpMdConverter] Sidecar 저장: " + sidecar
                    + " (MD → 원본 복원용)");
        } catch (IOException e) {
            System.err.println("[HwpMdConverter] Sidecar 저장 실패: " + e.getMessage());
        }
    }

    /**
     * v14.44 (task2): MD 끝에 원본 HWP/HWPX 의 base64 (MIME) 임베드 블록을 추가.
     * v13.41 의 형식과 동일:
     * <pre>
     * &lt;!-- hwp-converter-embedded-origin: FORMAT=hwp ENCODING=base64 SIZE=N --&gt;
     * &lt;!--
     * (base64 mime-encoded bytes, 76-char wrap)
     * --&gt;
     * &lt;!-- end-embedded-origin --&gt;
     * </pre>
     */
    private static void appendEmbeddedOrigin(String mdPath, String srcPath, String fmt) {
        if (!EMBED_ORIGIN) return;
        try {
            byte[] data = Files.readAllBytes(Paths.get(srcPath));
            String b64 = java.util.Base64.getMimeEncoder().encodeToString(data);
            String block = "\n\n<!-- hwp-converter-embedded-origin: FORMAT=" + fmt
                    + " ENCODING=base64 SIZE=" + data.length + " -->\n"
                    + "<!--\n" + b64 + "\n-->\n"
                    + "<!-- end-embedded-origin -->\n";
            Files.write(Paths.get(mdPath),
                    block.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);
            System.out.println("[HwpMdConverter] Base64 임베드: " + fmt.toUpperCase()
                    + " " + data.length + " bytes → base64 " + b64.length()
                    + " chars (MD 단독 이동 시 자체 복원용)");
        } catch (IOException e) {
            System.err.println("[HwpMdConverter] Base64 임베드 실패 (" + fmt
                    + "): " + e.getMessage());
        }
    }

    // =========================================================
    //  MD → HWP / HWPX (구조 변환 위임)
    // =========================================================
    //  Markdown 은 HWP 의 서식 / 바이너리 / 메타데이터를 완전히 표현할 수
    //  없는 손실(lossy) 포맷이다. MD 만으로 원본 HWP/HWPX 를 바이트 단위로
    //  복원하는 것은 불가능하므로, MD → HWP/HWPX 방향은 항상 구조 기반
    //  변환기 {@link MdStructureConverter} 에 위임한다.
    // =========================================================

    /** MD → HWP 변환. 항상 {@link MdStructureConverter} 로 위임한다. */
    public void convertMarkdownToHwp(String filePathMd, String filePathHwp) throws Exception {
        ensureInputExists(filePathMd, ".md");
        ensureDistinctPaths(filePathMd, filePathHwp);
        ensureParentDir(filePathHwp);
        filePathHwp = kr.n.nframe.newfeature.OutputNaming.unique(filePathHwp); // v16t42 비덮어쓰기
        System.out.println("[HwpMdConverter] MD → HWP (구조 변환): " + filePathMd + " → " + filePathHwp);
        // [v14.30] 사용자 요청: hwp 파일만 생성 (이전 v14.29 의 .hwpx 자동 생성 제거).
        new MdStructureConverter().convertMarkdownToHwpStructure(filePathMd, filePathHwp);
    }

    /** MD → HWPX 변환. 항상 {@link MdStructureConverter} 로 위임한다. */
    public void convertMarkdownToHwpx(String filePathMd, String filePathHwpx) throws Exception {
        ensureInputExists(filePathMd, ".md");
        ensureDistinctPaths(filePathMd, filePathHwpx);
        ensureParentDir(filePathHwpx);
        filePathHwpx = kr.n.nframe.newfeature.OutputNaming.unique(filePathHwpx); // v16t42 비덮어쓰기
        System.out.println("[HwpMdConverter] MD → HWPX (구조 변환): " + filePathMd + " → " + filePathHwpx);
        new MdStructureConverter().convertMarkdownToHwpxStructure(filePathMd, filePathHwpx);
    }

    // =========================================================
    //  Markdown 렌더링
    // =========================================================

    private void writeMarkdown(HwpDocument doc, String mdPath) throws IOException {
        ensureParentDir(mdPath);
        Path mdP = Paths.get(mdPath).toAbsolutePath();
        Path outDir = mdP.getParent();

        // Images are embedded as base64 data URIs in the .md file, so no assets folder is needed.
        Path imgDir = null;

        MdContext ctx = new MdContext(doc, imgDir, outDir);
        for (Section sec : doc.sections) {
            for (Paragraph p : sec.paragraphs) {
                renderParagraph(ctx, p, 0);
            }
        }
        // v13.25/26: 문서 끝 — 남은 버퍼들 flush
        flushPendingToc(ctx);
        flushPendingRightLines(ctx);

        String content = trimTrailingBlankLines(ctx.out.toString());
        // v13.22: 전역 후처리 — 모든 Markdown emphasis 마커를 HTML 태그로 변환.
        content = postProcessGlobalEmphasis(content);
        // v14.2: 인라인 HTML 메타데이터 제거 (style/align 속성, padding div, TOC 점선 등).
        content = cleanInlineHtml(content);
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(mdPath), StandardCharsets.UTF_8))) {
            w.write(content);
            if (!content.endsWith("\n")) w.newLine();
        }
    }

    /**
     * v14.2: 인라인 HTML 메타데이터 제거 후처리.
     *
     * <p>Markdown 은 글꼴 크기/색상/정렬/패딩 등 시각적 메타데이터를 표현할 수 없는
     * 손실 포맷이다. 변환 도중 emit 된 {@code style="..."} / {@code align="..."} 속성과
     * 알려진 noise wrapper 태그를 제거해 깨끗한 GFM 출력을 만든다.
     *
     * <p>처리 항목:
     * <ul>
     *   <li>모든 {@code style="..."}, {@code align="..."}, {@code border="..."},
     *       {@code width="..."} 속성 제거</li>
     *   <li>{@code <div>...</div>} unwrap (속성 정리 후 의미 없는 wrapper 제거)</li>
     *   <li>{@code <h1..h6 align="...">…</h1..h6>} → 일반 heading</li>
     *   <li>{@code <u>}, {@code </u>} 제거 (MD 는 underline 미지원)</li>
     *   <li>{@code <s>…</s>} → {@code ~~…~~} (GFM strikethrough)</li>
     *   <li>4+ 연속 middle-dot ({@code ·}) → {@code ...} (TOC 필러 점선 단순화)</li>
     *   <li>{@code <pre style="...">…</pre>}, {@code <span>...</span>} 등 inline noise unwrap</li>
     * </ul>
     */
    private String cleanInlineHtml(String s) {
        if (s == null || s.isEmpty()) return s;
        // [v14.27] border 가 포함된 style 은 유지 (table 구분선 보존).
        // [v14.44 task2] padding-left 가 포함된 style 도 유지 (v13.41 의 들여쓰기 wrapper).
        // tempered: ((?!border)(?!padding-left)[^"])* 는 닫는 따옴표까지 가는 동안
        //           "border" 또는 "padding-left" 가 한 번도 나오지 않는 경우만 매칭.
        s = s.replaceAll("\\s+style\\s*=\\s*\"((?:(?!border)(?!padding-left)[^\"])*)\"", "");
        s = s.replaceAll("\\s+style\\s*=\\s*'((?:(?!border)(?!padding-left)[^'])*)'", "");
        // v14.44 (task2): align="..." 은 v13.41 거동 — <h{n}> / <div> 의 정렬 정보로 보존.
        //         (이전 v14.2 는 align 을 모두 strip 했지만, 이로 인해 v13.41 의 시각적
        //          정렬 표현이 손실됨. 보존해도 GFM viewer 가 무시하므로 안전.)
        s = s.replaceAll("\\s+border\\s*=\\s*\"[^\"]*\"", "");
        s = s.replaceAll("\\s+width\\s*=\\s*\"[^\"]*\"", "");
        s = s.replaceAll("\\s+cellpadding\\s*=\\s*\"[^\"]*\"", "");
        s = s.replaceAll("\\s+cellspacing\\s*=\\s*\"[^\"]*\"", "");

        // 2) <h1..h6 ...>X</h1..h6> → "# X" / "## X" / ... (속성 제거 후 unwrap)
        java.util.regex.Pattern hp = java.util.regex.Pattern.compile(
                "<h([1-6])\\s*>([\\s\\S]*?)</h\\1>");
        java.util.regex.Matcher hm = hp.matcher(s);
        StringBuffer hb = new StringBuffer();
        while (hm.find()) {
            int level = Integer.parseInt(hm.group(1));
            String body = hm.group(2).trim();
            StringBuilder rep = new StringBuilder();
            for (int i = 0; i < level; i++) rep.append('#');
            rep.append(' ').append(body);
            hm.appendReplacement(hb, java.util.regex.Matcher.quoteReplacement(rep.toString()));
        }
        hm.appendTail(hb);
        s = hb.toString();

        // 3) <u>...</u> 제거 (MD 는 밑줄 미지원).
        s = s.replaceAll("</?u>", "");

        // 4) <s>X</s> → ~~X~~ (GFM 취소선).
        s = s.replaceAll("<s>([\\s\\S]*?)</s>", "~~$1~~");

        // 5) 의미 없는 빈 <div> / <span> 등 wrapper unwrap.
        //    <div> X </div> → X. 반복 적용해 중첩 wrapper 도 제거.
        for (int i = 0; i < 4; i++) {
            String before = s;
            s = s.replaceAll("<div\\s*>([\\s\\S]*?)</div>", "$1");
            s = s.replaceAll("<span\\s*>([\\s\\S]*?)</span>", "$1");
            s = s.replaceAll("<pre\\s*>([\\s\\S]*?)</pre>", "$1");
            if (s.equals(before)) break;
        }

        // [v14.28] TOC 점선 (·······) 단순화 제거.
        // v13.41 처럼 원본 dot leader 를 그대로 보존하여 MD preview 에서 HWP 의
        // 목차 점선이 시각적으로 일치하도록 한다. 이전 v14.2~v14.27 에서는
        // 4+ 연속 · 를 ... 으로 줄여 표시했으나 사용자 요청으로 원복.
        // (s.replaceAll("·{4,}", "...") 제거)

        // 7) 점선 unwrap 결과 남은 다중 빈 줄을 정리.
        s = s.replaceAll("\n{3,}", "\n\n");
        return s;
    }

    /**
     * v13.22: 전역 Markdown emphasis → HTML 태그 변환 후처리.
     *
     * <p>escape 백슬래시({@code \*}, {@code \_})를 제거하고,
     * 남아 있는 emphasis 마커({@code **x**}, {@code *x*}, {@code _x_}, {@code ~~x~~})를
     * 각각 HTML 태그({@code <strong>}, {@code <em>}, {@code <em>}, {@code <s>})로 교체한다.
     *
     * <p>code block 내부나 이미 HTML 태그인 부분은 영향 없음.
     */
    private String postProcessGlobalEmphasis(String s) {
        if (s == null || s.isEmpty()) return s;
        s = s.replace("\\*", "*");
        s = s.replace("\\_", "_");
        s = s.replace("\\[", "[");
        s = s.replace("\\]", "]");
        s = escapeStrayAngleBrackets(s);
        // v13.23: **bold** regex 개선 — 내부에 단일 *가 있어도 매칭
        s = s.replaceAll("\\*\\*([^\\n]+?)\\*\\*", "<strong>$1</strong>");
        s = s.replaceAll("~~([^~\\n]+?)~~", "<s>$1</s>");
        // v13.31: 연속 <strong> 조각 병합 — HWP charShape 이 잘게 쪼갠 bold 부분을 합쳐
        //         "<strong>*</strong>*<strong>(예시)</strong>" 같이 `*` 로 끊어진 패턴을
        //         "<strong>* (예시)</strong>" 로 정리. 짧은 중간 text (≤5 chars) 만 병합.
        for (int i = 0; i < 3; i++) {
            String before = s;
            s = s.replaceAll("</strong>([^<\\n]{0,5}?)<strong>", "$1");
            if (s.equals(before)) break;
        }
        // v13.31: 병합 후 <strong> 내부 시작/끝의 literal `*` 또는 공백+* 제거.
        //         "<strong>**(예시) **교부결정서</strong>" → "<strong>(예시) 교부결정서</strong>".
        //         병합 과정에서 남은 artifact 를 정리.
        s = s.replaceAll("<strong>[\\s*]+", "<strong>");
        s = s.replaceAll("[\\s*]+</strong>", "</strong>");
        // 연속 <strong> 이 비어있게 된 경우 (`<strong></strong>`) 제거
        s = s.replace("<strong></strong>", "");
        // v14.2: "※" / "* " 들여쓰기 wrapper 제거 — MD 는 padding 표현 불가.
        //         원본 라인을 그대로 emit 해 깨끗한 GFM 을 유지한다.
        return s;
    }

    /**
     * 문단 하나를 Markdown 으로 렌더링한다.
     * @param nestLevel 표 안 중첩 깊이 (0=본문, 1+=표 셀 내부). 중첩시 블록 컨트롤 출력을 달리한다.
     */
    private void renderParagraph(MdContext ctx, Paragraph p, int nestLevel) {
        int headingLevel = detectHeadingLevel(ctx.doc, p);
        InlineResult inline = renderInline(ctx, p, nestLevel, headingLevel > 0);

        // v13.26: block 출력 전 누적된 TOC/right-lines 버퍼 flush.
        //         이 단락에 block (표/이미지/box) 이 있으면, 누적 TOC 보다 뒤에 배치되어야
        //         사용자 요구("o 경기도..." 박스를 목차 하단으로")가 충족된다.
        if (!inline.blocks.isEmpty()) {
            flushPendingToc(ctx);
            flushPendingRightLines(ctx);
        }

        // v16t44 (task5): 단락 정렬을 블록 emit 전에 계산 (0=left/justify, 2=center, 3=right)
        int align = getParagraphAlign(ctx.doc, p);

        for (Object block : inline.blocks) {
            if (block instanceof String) {
                String b = (String) block;
                // v16t44 (task5): 이미지 블록은 원본 문단 정렬(중앙/우측)을 div align 으로 재현.
                //   HTML 블록과 markdown 이미지 사이 빈 줄 필수 (CommonMark/GFM 파싱 규칙).
                if (nestLevel == 0 && b.startsWith("![") && (align == 2 || align == 3)) {
                    String dir = (align == 2) ? "center" : "right";
                    ctx.out.append("<div align=\"").append(dir).append("\">\n\n")
                           .append(b).append("\n\n</div>\n\n");
                } else {
                    ctx.out.append(b).append("\n\n");
                }
            }
        }

        String text = inline.text.toString().trim();
        if (headingLevel > 0) {
            text = stripLeadingTrailingFormatMarkers(text);
        }

        if (text.isEmpty()) {
            if (inline.blocks.isEmpty() && !endsWithBlankLine(ctx.out)) {
                ctx.out.append('\n');
            }
            return;
        }

        // v13.19: 단락 정렬 감지 (0=left/justify, 2=center, 3=right) — v16t44 부터 위에서 선계산
        boolean center = (align == 2) && nestLevel == 0;
        boolean right  = (align == 3) && nestLevel == 0;

        // v13.21: HWP 원본이 LEFT align 이지만 선행 공백(탭 치환 결과) 이 많아 시각적으로
        //         우측 영역에 배치된 "서식 라인" 감지 — 상당한 선행 공백이 있는 짧은 라인을
        //         우측 정렬로 재현 (예: 신청서의 "신청자   : 단체명   (인)" 같은 라인).
        if (!center && !right && nestLevel == 0 && align == 0 && inline.blocks.isEmpty()) {
            int leadingSpaces = countLeadingWhitespace(inline.text.toString());
            // 선행 공백 10자 이상 + 실제 내용이 상대적으로 짧은 경우 (form 양식 라인)
            int lineLen = inline.text.toString().length();
            if (leadingSpaces >= 10 && (lineLen - leadingSpaces) <= 40) {
                right = true;
                text = text; // text 는 이미 trim 되었으므로 그대로 사용
            }
        }

        if (headingLevel > 0 && nestLevel == 0) {
            // v13.25/26: TOC 스타일 heading 감지
            TocEntry tocEntry = tryParseTocEntry(text, headingLevel);
            if (tocEntry != null) {
                flushPendingRightLines(ctx);
                // v13.26: 첫 TOC entry 시작 시점에 직전 1x1 강조박스 있으면 TOC 뒤로 이동
                if (ctx.pendingToc.isEmpty()) {
                    extractTrailingBorderBox(ctx);
                }
                ctx.pendingToc.add(tocEntry);
                return;
            }
            // TOC 아닌 heading — 이전 버퍼들 flush
            flushPendingToc(ctx);
            flushPendingRightLines(ctx);
            // v14.44 (task2): v13.41 거동 — center 정렬 heading 은
            //   <h{level} align="center">X</h{level}> 로 emit. (cleanInlineHtml 가 보존)
            if (center) {
                ctx.out.append("<h").append(headingLevel).append(" align=\"center\">")
                        .append(convertMarkdownEmphasisToHtml(text))
                        .append("</h").append(headingLevel).append(">\n\n");
            } else {
                for (int i = 0; i < headingLevel; i++) ctx.out.append('#');
                ctx.out.append(' ').append(text).append("\n\n");
            }
            return;
        }

        // v13.25: non-heading paragraph — TOC 버퍼가 있으면 flush
        flushPendingToc(ctx);

        // v13.26: 우측정렬 단락이면 누적, 아니면 누적된 것 flush
        if (right) {
            String rawText = inline.text.toString();
            while (rawText.endsWith(" ") || rawText.endsWith("\t")) {
                rawText = rawText.substring(0, rawText.length() - 1);
            }
            ctx.pendingRightLines.add(convertMarkdownEmphasisToHtml(rawText));
            return;
        }
        flushPendingRightLines(ctx);

        int listLevel = detectListLevel(ctx.doc, p);
        if (listLevel > 0) {
            String marker = isOrderedList(ctx.doc, p) ? "1. " : "- ";
            for (int i = 0; i < listLevel - 1; i++) ctx.out.append("  ");
            ctx.out.append(marker).append(text).append('\n');
            return;
        }

        // v14.44 (task2): v13.41 거동 — 부연 설명 라인 ("※ ", "* ") 을
        //   <div style="padding-left: 1em;"> 으로 감싸 시각적 들여쓰기 표현.
        //   ParaShape.leftMargin 은 HWPX 변환 단계에서 0 으로 평탄화되므로
        //   text-pattern 휴리스틱으로 대체.
        //   조건: 단락 시작이 (※ + 공백) 또는 (* + 공백) — '*'이 bold 마커가 아닌 경우.
        //   '* '이지만 '* *' (즉 별표가 두 개 연속) 등은 제외.
        if (shouldWrapAsIndent(text)) {
            ctx.out.append("<div style=\"padding-left: 1em;\">")
                    .append(text).append("</div>\n\n");
            return;
        }

        // v14.45 (task1): v13.41 거동 — 본문 중앙정렬 단락은 <div align="center">…</div> 로 감싸
        //   시각적 정렬 정보를 보존한다.
        if (center) {
            ctx.out.append("<div align=\"center\">")
                    .append(convertMarkdownEmphasisToHtml(text))
                    .append("</div>\n\n");
            return;
        }
        // v14.2: 들여쓰기는 MD 에서 표현 불가 → plain text 로 emit.
        ctx.out.append(text).append("\n\n");
    }

    /**
     * v14.44 (task2): "※ X" / "* X" 같이 부연 설명임을 표시하는 단락 검사.
     * v13.41 의 padding-left wrapper 동작 재현.
     */
    private boolean shouldWrapAsIndent(String text) {
        if (text == null || text.length() < 2) return false;
        char c0 = text.charAt(0);
        if (c0 != '※' && c0 != '*') return false;
        char c1 = text.charAt(1);
        // 다음 글자가 공백류여야 함. '※「' 같이 바로 한글 punctuation 으로 이어지면 wrap 안 함.
        if (c1 != ' ' && c1 != '\t' && c1 != '　') return false;
        // '*' 케이스에서 '**' (bold marker 시작) 이거나 '* *' 같은 패턴은 제외.
        if (c0 == '*') {
            // already filtered by c1 != '*' via whitespace check, so safe.
            // 단, 리스트 아이템 marker ('- ') 와 혼동될 위험 없음 (c0 != '-').
        }
        return true;
    }

    /**
     * v13.26: TOC 시작 직전 ctx.out 의 마지막 블록이 1x1 강조 박스(<div style="border: 2px solid #666;">)
     * 인 경우, 이를 추출해 ctx.deferredBoxAfterToc 에 저장. TOC flush 뒤에 다시 출력된다.
     *
     * <p>원본 HWP 에서 목차 상단에 "o 경기도 홈페이지..." 같은 강조 박스가 있지만
     * 사용자 피드백상 해당 박스가 TOC 뒤 (하단) 에 위치해야 더 자연스럽다.
     */
    /**
     * v13.26: (현재 미사용) TOC 시작 직전 trailing border box 추출 헬퍼.
     * 현재는 block paragraph 처리 시점에 flushPendingToc 를 호출하는 방식으로
     * 동일한 배치 효과를 얻고 있으므로 이 함수는 유지만 하고 호출하지 않음.
     */
    private void extractTrailingBorderBox(MdContext ctx) {
        String s = ctx.out.toString();
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        if (end == 0) return;
        String closing = "</div>";
        if (end < closing.length()) return;
        if (!s.substring(end - closing.length(), end).equals(closing)) return;
        String opening = "<div style=\"border: 2px solid #666;";
        int openIdx = s.lastIndexOf(opening, end);
        if (openIdx < 0) return;
        String box = s.substring(openIdx, end);
        ctx.out.setLength(openIdx);
        ctx.deferredBoxAfterToc = box;
    }

    /**
     * v14.2: 누적된 우측정렬 라인들을 flush.
     * v14.45 (task1): v13.41 거동 — 우측정렬 단락은 {@code <div align="right">…</div>} 으로
     *   감싸 시각적 정렬 정보를 보존한다 (cleanInlineHtml 가 align 속성 보존).
     */
    private void flushPendingRightLines(MdContext ctx) {
        if (ctx.pendingRightLines.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String line : ctx.pendingRightLines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            sb.append("<div align=\"right\">").append(t).append("</div>\n\n");
        }
        ctx.out.append(sb);
        ctx.pendingRightLines.clear();
    }

    /**
     * v13.25: TOC 패턴 감지 — heading 텍스트에서 제목/페이지 번호 추출.
     * 매칭 되면 TocEntry 반환, 아니면 null.
     */
    private TocEntry tryParseTocEntry(String text, int level) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^(.+?)\\s*[·\\-_\\s]{3,}\\s*(\\d{1,4})\\s*$").matcher(text);
        if (!m.matches()) return null;
        String title = m.group(1).trim();
        String page  = m.group(2).trim();
        if (title.isEmpty() || page.isEmpty()) return null;
        boolean major = title.matches("^[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+[.)].*");
        return new TocEntry(title, page, level, major);
    }

    /**
     * v13.25: 누적된 TOC entries 를 단일 HTML table 로 flush.
     *
     * <p>단일 table 구조이므로 모든 row 가 같은 column 너비를 공유 → 페이지 번호가
     * 항상 같은 위치에 정렬됨. row 별 font-size 와 들여쓰기는 <tr style="..."> 또는
     * 각 <td style="..."> 에 적용.
     *
     * <p>대번호(로마숫자) 행: 17pt, 소번호(아라비안) 행: 15pt, 1em 들여쓰기.
     * 페이지 번호 cell: text-align: right + 15pt.
     */
    private void flushPendingToc(MdContext ctx) {
        if (ctx.pendingToc.isEmpty()) return;
        List<TocEntry> list = ctx.pendingToc;
        // [v14.28] v13.41 스타일로 복원: monospace div 안에 dot leader 로 시각화.
        // 이전 v14.2 의 GFM 리스트 출력 (- 제목 (p.번호)) 은 점선이 사라져 HWP 의
        // 목차 모양과 시각 차이가 컸음. v13.41 처럼 monospace + 점선으로 출력하여
        // MD preview 에서 HWP 의 목차와 동일한 느낌을 재현.
        StringBuilder sb = new StringBuilder();
        // v16t44 (task6): align 속성으로 중앙 배치 — GitHub 등이 style 을 sanitize 해도 유지됨.
        sb.append("<div align=\"center\" style=\"font-family: monospace; white-space: pre; line-height: 1.6;")
          .append(" text-decoration: none; border: none;\">");
        for (TocEntry e : list) {
            int titleCols = measureMonospaceCols(e.title);
            int pageCols = (e.page == null) ? 0 : e.page.length();
            int totalCols = 100;
            // v16t44 (task6): 소번호(비major) 선행 공백 2칸 들여쓰기 — white-space:pre 로 보존.
            //   페이지번호 컬럼(100col) 유지를 위해 filler 에서 들여쓰기만큼 차감.
            int indentCols = e.major ? 0 : 2;
            int fillerCols = totalCols - indentCols - titleCols - pageCols - 2;
            if (fillerCols < 3) fillerCols = 3;
            // major 섹션 (로마숫자) 앞에 빈 줄
            if (e.major && sb.length() > 0
                    && !sb.toString().endsWith("\">"))  {
                sb.append('\n');
            }
            if (indentCols > 0) sb.append("  ");
            // 제목
            sb.append("<span style=\"font-weight: bold; text-decoration: none;");
            if (!e.major) sb.append(" border: none;");  // 들여쓰기 표현용 noise
            sb.append("\">").append(e.title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("</span> ");
            // 점선 (dot leader)
            for (int di = 0; di < fillerCols; di++) sb.append('·');
            // 페이지 번호
            if (e.page != null && !e.page.isEmpty()) {
                sb.append(" <span style=\"font-weight: bold; text-decoration: none;\">")
                  .append(e.page).append("</span>");
            }
            sb.append('\n');
        }
        sb.append("</div>\n\n");
        ctx.out.append(sb);
        ctx.pendingToc.clear();
        // v14.2: deferredBoxAfterToc 는 더 이상 사용되지 않지만 안전하게 emit.
        if (ctx.deferredBoxAfterToc != null) {
            ctx.out.append(ctx.deferredBoxAfterToc).append("\n\n");
            ctx.deferredBoxAfterToc = null;
        }
    }

    /**
     * v13.20: TOC 스타일 heading 을 페이지 번호가 우측 정렬된 HTML 로 변환.
     *
     * <p>패턴: "제목 텍스트 ········· 숫자" 또는 "제목 텍스트 공백 숫자"
     * 결과: 3-컬럼 HTML table (좌:제목 / 중:dots / 우:페이지 번호) 로 변환해
     * 제목과 페이지 사이를 dot 리더로 시각적으로 연결한다.
     * 주 섹션(로마 숫자 Ⅰ/Ⅱ/Ⅲ) 앞에는 빈 줄을 삽입해 원본 목차 구조를 반영한다.
     *
     * @return 변환된 HTML (TOC 패턴 아니면 null)
     */
    private String tryFormatTocHeading(String text, int level) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^(.+?)\\s*[·\\-_\\s]{3,}\\s*(\\d{1,4})\\s*$").matcher(text);
        if (!m.matches()) return null;
        String title = m.group(1).trim();
        String page  = m.group(2).trim();
        if (title.isEmpty() || page.isEmpty()) return null;

        // v13.20: 로마숫자로 시작하는 주 섹션 (Ⅰ. / Ⅱ. / Ⅲ. ...) 앞에 빈 줄 추가
        //         원본 목차의 대분류 상단 간격을 흉내내기 위함
        boolean isMajorSection = title.matches("^[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+[.)].*");

        // v13.24: TOC monospace 렌더링 개선.
        //   · 총 컬럼을 100 으로 확대 → "2. 2024년도 경기도 지방보조금관리위원회 운영" 같이
        //     긴 제목에서도 충분한 dots 확보, 페이지 번호가 같은 위치에 정렬
        //   · text-decoration: none 명시 — VSCode preview 등에서 <h1>/<strong> 에 기본
        //     underline 이 걸리는 테마 대응
        int titleCols = measureMonospaceCols(title);
        int pageCols  = page.length();
        int totalCols = 100;
        // v16t44 (task6): 비major 선행 공백 2칸 들여쓰기 + filler 차감 (flushPendingToc 와 동일 규칙)
        int indentCols = isMajorSection ? 0 : 2;
        int fillerCols = totalCols - indentCols - titleCols - pageCols - 2;
        if (fillerCols < 3) fillerCols = 3;

        StringBuilder sb = new StringBuilder();
        if (isMajorSection) {
            sb.append("<div style=\"height: 6px;\"></div>\n\n");
        }
        // v16t44 (task6): align 속성으로 중앙 배치 (style sanitize 대응)
        sb.append("<div align=\"center\" style=\"font-family: monospace; white-space: pre; line-height: 1.6;")
          .append(" text-decoration: none; border: none;\">");
        if (indentCols > 0) sb.append("  ");
        sb.append("<span style=\"font-weight: bold; text-decoration: none;\">")
          .append(title).append("</span> ");
        for (int di = 0; di < fillerCols; di++) sb.append('·');
        sb.append(" <span style=\"font-weight: bold; text-decoration: none;\">")
          .append(page).append("</span>");
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * v13.23: 문자열의 monospace 컬럼 너비 계산.
     * CJK 및 전각 글자는 2 col, 그 외는 1 col.
     */
    private int measureMonospaceCols(String s) {
        if (s == null) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x1100 && c <= 0x115F) w += 2;
            else if (c >= 0x2E80 && c <= 0x9FFF) w += 2;
            else if (c >= 0xAC00 && c <= 0xD7A3) w += 2;
            else if (c >= 0xF900 && c <= 0xFAFF) w += 2;
            else if (c >= 0xFE30 && c <= 0xFE4F) w += 2;
            else if (c >= 0xFF00 && c <= 0xFF60) w += 2;
            else w += 1;
        }
        return w;
    }

    /** heading 문단의 텍스트 양끝에 남은 bold/italic 마커 잔해 제거. */
    private String stripLeadingTrailingFormatMarkers(String s) {
        if (s == null || s.isEmpty()) return s;
        // 좌측: <u>, **, *, ~~ 가 연속으로 있으면 제거
        int start = 0;
        boolean changed = true;
        while (changed && start < s.length()) {
            changed = false;
            if (s.startsWith("<u>", start))      { start += 3; changed = true; }
            else if (s.startsWith("**", start))  { start += 2; changed = true; }
            else if (s.startsWith("~~", start))  { start += 2; changed = true; }
            else if (s.startsWith("*",  start))  { start += 1; changed = true; }
        }
        int end = s.length();
        changed = true;
        while (changed && end > start) {
            changed = false;
            if (end >= start + 4 && s.startsWith("</u>", end - 4)) { end -= 4; changed = true; }
            else if (end >= start + 2 && s.startsWith("**", end - 2)) { end -= 2; changed = true; }
            else if (end >= start + 2 && s.startsWith("~~", end - 2)) { end -= 2; changed = true; }
            else if (end >= start + 1 && s.charAt(end - 1) == '*')   { end -= 1; changed = true; }
        }
        return s.substring(start, end).trim();
    }

    // =========================================================
    //  인라인 (텍스트 + 포맷 + 개체) 렌더링
    // =========================================================

    private InlineResult renderInline(MdContext ctx, Paragraph p, int nestLevel) {
        return renderInline(ctx, p, nestLevel, false);
    }

    private InlineResult renderInline(MdContext ctx, Paragraph p, int nestLevel, boolean skipFormat) {
        InlineResult r = new InlineResult();
        if (p.paraText == null || p.paraText.rawBytes == null) return r;

        byte[] raw = p.paraText.rawBytes;
        int wcharCount = raw.length / 2;
        if (wcharCount == 0) return r;

        char[] chars = new char[wcharCount];
        for (int i = 0; i < wcharCount; i++) {
            chars[i] = (char) (((raw[i * 2] & 0xFF)) | ((raw[i * 2 + 1] & 0xFF) << 8));
        }

        List<CharShapeRef> refs = p.charShapeRefs;
        int refIdx = 0;
        CharShapeRef curRef = refs.isEmpty() ? null : refs.get(0);
        Iterator<Control> ctrlIter = p.controls.iterator();

        StringBuilder textBuf = r.text;
        LinkState link = null;
        FormatState curFmt = null;

        int pos = 0;
        while (pos < wcharCount) {
            char c = chars[pos];

            while (refIdx + 1 < refs.size() && pos >= refs.get(refIdx + 1).position) {
                refIdx++;
                curRef = refs.get(refIdx);
            }

            if (c == CH_PARA_END) break;

            if (isExtendedControlChar(c)) {
                Control ctrl = ctrlIter.hasNext() ? ctrlIter.next() : null;
                curFmt = closeFormat(textBuf, curFmt);

                if (c == CH_OBJECT && ctrl instanceof CtrlTable) {
                    String tm = renderTable(ctx, (CtrlTable) ctrl, nestLevel);
                    if (tm != null) r.blocks.add(tm);
                } else if (c == CH_OBJECT && ctrl instanceof CtrlPicture
                        && "pic".equals(((CtrlPicture) ctrl).shapeType)) {
                    String im = renderImage(ctx, (CtrlPicture) ctrl);
                    if (im != null) r.blocks.add(im);
                } else if (c == CH_OBJECT && ctrl instanceof CtrlPicture
                        && !"pic".equals(((CtrlPicture) ctrl).shapeType)
                        && !((CtrlPicture) ctrl).textboxParagraphs.isEmpty()) {
                    // [v14.86 task1] 사각형/타원/선 등 그리기 도형의 drawText 본문을 MD 로 노출.
                    //   기존 코드는 shapeType="pic" 만 처리해 도형 안의 텍스트가 누락되었음.
                    //   bordered <div> 로 감싸 시각적으로 도형임을 표현.
                    String sm = renderShapeText(ctx, (CtrlPicture) ctrl, nestLevel);
                    if (sm != null) r.blocks.add(sm);
                } else if (c == CH_FIELD_BEGIN && ctrl instanceof CtrlField
                        && ((CtrlField) ctrl).ctrlId == CtrlId.FIELD_HYPERLINK) {
                    String url = extractHyperlinkUrl(((CtrlField) ctrl).command);
                    if (url != null && !url.isEmpty()) {
                        link = new LinkState(url);
                        textBuf = link.text;
                    }
                }
                pos += 8;
                continue;
            }

            if (c == CH_FIELD_END) {
                if (link != null) {
                    curFmt = closeFormat(textBuf, curFmt);
                    String lt = link.text.toString().trim();
                    textBuf = r.text;
                    if (!lt.isEmpty()) {
                        textBuf.append('[').append(escapeLinkText(lt))
                                .append("](").append(escapeLinkUrl(link.url)).append(')');
                    }
                    link = null;
                }
                pos += 3;
                continue;
            }
            if (c == CH_INLINE_END) {
                pos += 3;
                continue;
            }
            if (c == CH_TAB) {
                // v13.17: HWP 5.0 인라인 TAB 은 8-WCHAR 확장 컨트롤로 저장된다
                // (HwpxReader.SectionParser.writeTabControl 참조).
                //   pos 0   : CH_TAB (0x0009)
                //   pos 1-2 : width (UINT32 LE, 2 WCHAR)
                //   pos 3   : leader(byte) + type(byte) 패킹 (1 WCHAR)
                //   pos 4-6 : 0x0020 패딩 (3 WCHAR)
                //   pos 7   : CH_TAB 복사본 (1 WCHAR)
                //
                // leader = 0(NONE) / 1(SOLID) / 2(DASH) / 3(DOT) / 4(DASH_DOT) / 5(DASH_DOT_DOT)
                // type   = 0(LEFT) / 1(RIGHT) / 2(CENTER) / 3(DECIMAL)
                int leader = 0;
                boolean isEightWchar = false;
                if (pos + 7 < wcharCount && chars[pos + 7] == CH_TAB) {
                    // pos+3 의 하위 byte 가 leader. WCHAR = (type << 8) | leader (little-endian 저장에서)
                    // Java char 가 UTF-16LE WCHAR 이면 low byte = leader
                    int packed = chars[pos + 3] & 0xFFFF;
                    leader = packed & 0xFF;
                    isEightWchar = true;
                }

                FormatState want = skipFormat ? null : nextFormat(ctx.doc, curRef);
                curFmt = switchFormat(textBuf, curFmt, want);

                if (leader == 3) {
                    // DOT leader — 목차형 점 리더. 원본 HWP 와 유사한 밀도를 유지한다.
                    if (nestLevel > 0)       textBuf.append(' ');
                    else if (skipFormat)     textBuf.append(" ·············· ");  // heading: v13.18 길게
                    else                     textBuf.append(" ····················· "); // 본문: 아주 길게
                } else if (leader == 2 || leader == 4 || leader == 5) {
                    // DASH / DASH-DOT / DASH-DOT-DOT leader
                    if (nestLevel > 0)       textBuf.append(' ');
                    else if (skipFormat)     textBuf.append(" -------------- ");
                    else                     textBuf.append(" --------------------- ");
                } else if (leader == 1) {
                    // SOLID leader (실선)
                    if (nestLevel > 0)       textBuf.append(' ');
                    else if (skipFormat)     textBuf.append(" ______________ ");
                    else                     textBuf.append(" _____________________ ");
                } else {
                    // 리더 없음 → 공백
                    if (skipFormat || nestLevel > 0) textBuf.append(' ');
                    else                             textBuf.append("    ");
                }

                pos += isEightWchar ? 8 : 3;
                continue;
            }

            if (c == CH_NEWLINE) {
                curFmt = closeFormat(textBuf, curFmt);
                // v13.16: heading/표 셀 내부에서는 강제 줄바꿈을 공백으로 치환
                if (skipFormat || nestLevel > 0) textBuf.append(' ');
                else textBuf.append("  \n");
                pos += 1;
                continue;
            }
            if (c == CH_NBSPACE || c == CH_FWSPACE) {
                FormatState want = skipFormat ? null : nextFormat(ctx.doc, curRef);
                curFmt = switchFormat(textBuf, curFmt, want);
                textBuf.append(' ');
                pos += 1;
                continue;
            }

            if (c < 0x0020) {
                pos += 1;
                continue;
            }
            // v13.16: HWP 가 목차 페이지번호 placeholder 등으로 쓰는 PUA/비표시 문자 필터
            if (isNonPrintableDisplayChar(c)) {
                pos += 1;
                continue;
            }
            if (Character.isHighSurrogate(c) && pos + 1 < wcharCount
                    && Character.isLowSurrogate(chars[pos + 1])) {
                int cp = Character.toCodePoint(c, chars[pos + 1]);
                // v13.17: HWP 폰트 PUA → 표준 Unicode 매핑 (원문자 ① / 화살표 ↓ / 도장 ㊞ 등)
                String mapped = mapHwpPuaCodePoint(cp);
                if (mapped != null) {
                    FormatState want = skipFormat ? null : nextFormat(ctx.doc, curRef);
                    curFmt = switchFormat(textBuf, curFmt, want);
                    for (int ci = 0; ci < mapped.length(); ci++) {
                        escapeAndAppend(textBuf, mapped.charAt(ci));
                    }
                    pos += 2;
                    continue;
                }
                // 매핑 없는 PUA-A/B 는 drop (이전 정책 유지, 알 수 없는 글리프로 ? 노출 방지)
                if (cp >= 0xF0000 && cp <= 0x10FFFD) {
                    pos += 2;
                    continue;
                }
                FormatState want = skipFormat ? null : nextFormat(ctx.doc, curRef);
                curFmt = switchFormat(textBuf, curFmt, want);
                escapeAndAppend(textBuf, cp);
                pos += 2;
            } else {
                FormatState want = skipFormat ? null : nextFormat(ctx.doc, curRef);
                curFmt = switchFormat(textBuf, curFmt, want);
                escapeAndAppend(textBuf, c);
                pos += 1;
            }
        }

        curFmt = closeFormat(textBuf, curFmt);
        if (link != null) {
            String lt = link.text.toString().trim();
            if (!lt.isEmpty()) {
                r.text.append('[').append(escapeLinkText(lt))
                        .append("](").append(escapeLinkUrl(link.url)).append(')');
            }
        }

        return r;
    }

    private FormatState closeFormat(StringBuilder buf, FormatState cur) {
        if (cur == null) return null;
        if (cur.strike)    buf.append("~~");
        if (cur.italic)    buf.append('*');
        if (cur.bold)      buf.append("**");
        if (cur.underline) buf.append("</u>");
        return null;
    }

    private FormatState switchFormat(StringBuilder buf, FormatState cur, FormatState next) {
        if (formatEquals(cur, next)) return cur;
        closeFormat(buf, cur);
        if (next != null) {
            if (next.underline) buf.append("<u>");
            if (next.bold)      buf.append("**");
            if (next.italic)    buf.append('*');
            if (next.strike)    buf.append("~~");
        }
        return next;
    }

    /**
     * v13.22: escape 최소화 — 뷰어에 {@code \*}, {@code \_} 같은 이스케이프 문자가
     * 그대로 노출되는 문제를 해소한다.
     *
     * <p>실제 CommonMark/GFM 파서에서 의미가 있는 경우는 다음과 같이 제한적:
     * <ul>
     *   <li>{@code *} 가 단어 경계 밖에 있을 때 emphasis 로 해석 → 한국어 문맥에서는 거의 발생 안 함</li>
     *   <li>{@code _} 는 단어 경계에서만 emphasis → intraword({@code dist_output}) 는 문제 없음</li>
     * </ul>
     *
     * <p>수정된 규칙:
     * <ul>
     *   <li>항상 escape: {@code \} (백슬래시), {@code `} (백틱), {@code [}, {@code ]}</li>
     *   <li>escape 제거: {@code *}, {@code _} — 뷰어 노출 방지 우선
     *       (bold emphasis 는 별도 pipeline 으로 {@code <strong>} 변환)</li>
     * </ul>
     */
    private void escapeAndAppend(StringBuilder run, int codePoint) {
        switch (codePoint) {
            case '\\': run.append("\\\\"); return;
            case '`':  run.append("\\`");  return;
            case '[':  run.append("\\[");  return;
            case ']':  run.append("\\]");  return;
            default:
                if (Character.isBmpCodePoint(codePoint)) {
                    run.append((char) codePoint);
                } else {
                    run.appendCodePoint(codePoint);
                }
        }
    }

    private String escapeLinkText(String s) {
        return s.replace("[", "\\[").replace("]", "\\]");
    }

    private String escapeLinkUrl(String s) {
        return s.replace("(", "%28").replace(")", "%29").replace(" ", "%20");
    }

    // =========================================================
    //  표 렌더링 (v13.16: GFM / HTML 자동 선택, 중첩·병합 지원)
    // =========================================================

    /**
     * 표를 Markdown 으로 렌더링.
     *
     * <p>렌더 전략:
     * <ul>
     *   <li>단순 표 (중첩 없음, 병합 없음, 셀 내 줄바꿈 없음) → GFM 파이프 표</li>
     *   <li>복잡 표 (중첩 표 / rowspan / colspan / 여러 문단 셀) → HTML &lt;table&gt;</li>
     * </ul>
     *
     * <p>HTML 출력은 GitHub / VS Code preview / 대부분 Markdown 렌더러에서 정상 표시되며
     * 병합·중첩·서식을 온전히 보존한다.
     *
     * @param nestLevel 현재 중첩 깊이 (0=본문 최상위). 중첩 시 HTML 표로 강제 fallback.
     */
    private String renderTable(MdContext ctx, CtrlTable t, int nestLevel) {
        if (t.rowCount == 0 || t.colCount == 0 || t.cells.isEmpty()) return null;

        // v13.16: 1행 × 1열 표는 "본문 강조 상자" 용도.
        // v13.26: 분기 재정비 —
        //   · 단순 단일 단락 text only   → blockquote (> ...)
        //   · 복잡 (multi-paragraph / align=right / block element) → <div border> 강조 박스
        //     (blockquote 안의 <div> / <table> 은 뷰어 렌더 깨짐 문제 있음)
        if (t.rowCount == 1 && t.colCount == 1 && t.cells.size() == 1) {
            TableCell only = t.cells.get(0);
            String body = renderCellContentBlock(ctx, only, nestLevel).trim();
            if (body.isEmpty()) return null;

            // [v14.86 task3] 1×1 wrapper 표를 평탄화하지 않고 &lt;table&gt; 블록으로 emit.
            //   v14.85 까지: blockquote (`> ...`) 또는 unwrap 으로 처리 → MD→HWP 라운드트립 시
            //   wrapper 표 손실 (입찰공고문.hwp 13 tables → MD 2 tables 만 보존되는 문제).
            //   변경: 모든 1×1 표를 &lt;table&gt; 로 emit → 라운드트립 보존.
            String converted = convertCellImagesToHtml(body);
            converted = convertMarkdownEmphasisToHtml(converted);
            converted = converted.replaceAll("\n{2,}", "\n");
            converted = escapeStrayAngleBrackets(converted);
            StringBuilder sb = new StringBuilder();
            sb.append("<table style=\"border-collapse: collapse; border: 1px solid #666; width: 100%;\">\n");
            sb.append("<tbody>\n<tr>\n");
            sb.append("<td style=\"padding: 6px; border: 1px solid #666;\">\n");
            sb.append(converted).append('\n');
            sb.append("</td>\n</tr>\n</tbody>\n</table>");
            return sb.toString();
        }

        // 병합 셀 그리드 구축: [r][c] 에 TableCell 포인터, 점유된 칸은 null 유지
        TableCell[][] grid = new TableCell[t.rowCount][t.colCount];
        boolean[][] occupied = new boolean[t.rowCount][t.colCount];
        boolean hasMerged = false;
        for (TableCell cell : t.cells) {
            int r = cell.rowAddr, c = cell.colAddr;
            if (r < 0 || r >= t.rowCount || c < 0 || c >= t.colCount) continue;
            grid[r][c] = cell;
            if (cell.rowSpan > 1 || cell.colSpan > 1) hasMerged = true;
            for (int rr = r; rr < Math.min(r + cell.rowSpan, t.rowCount); rr++) {
                for (int cc = c; cc < Math.min(c + cell.colSpan, t.colCount); cc++) {
                    occupied[rr][cc] = true;
                }
            }
        }
        // [v14.89 task1] v14.87 표 형태 복원 + ROW 0 의 "title-only" 패턴에만 한정 흡수.
        //   v14.88 의 광범위 흡수가 다른 표를 변형시킨 회귀를 방지하기 위해 매우 좁은 조건만 적용.
        // [v14.90 task3] 흡수가 발생한 표는 빈 boundary col (col 0 / last col) 도 skip emit.
        boolean[] skipCol = new boolean[t.colCount];
        if (t.rowCount >= 2 && t.colCount >= 4) {
            int titleC = -1;
            int nonEmptyRow0 = 0;
            for (int c = 0; c < t.colCount; c++) {
                TableCell cl = grid[0][c];
                if (cl != null && cellVisibleLen(cl) > 0) {
                    nonEmptyRow0++;
                    titleC = c;
                }
            }
            if (nonEmptyRow0 == 1 && titleC > 0 && titleC < t.colCount - 1) {
                TableCell title = grid[0][titleC];
                if (title.rowSpan >= 2 && title.colSpan == 1) {
                    int absorbL = 0;
                    for (int lc = titleC - 1; lc > 0; lc--) {
                        TableCell ln = grid[0][lc];
                        if (ln == null || cellVisibleLen(ln) > 0) break;
                        if (ln.colSpan > 1 || ln.rowSpan > 1) break;
                        absorbL++;
                    }
                    int absorbR = 0;
                    for (int rc = titleC + 1; rc < t.colCount - 1; rc++) {
                        TableCell rn = grid[0][rc];
                        if (rn == null || cellVisibleLen(rn) > 0) break;
                        if (rn.colSpan > 1 || rn.rowSpan > 1) break;
                        absorbR++;
                    }
                    if (absorbL > 0 || absorbR > 0) {
                        int newC = titleC - absorbL;
                        int newCS = 1 + absorbL + absorbR;
                        for (int dc = -absorbL; dc <= absorbR; dc++) grid[0][titleC + dc] = null;
                        grid[0][newC] = title;
                        title.colAddr = newC;
                        title.colSpan = newCS;
                        // [v14.90] 흡수된 영역의 모든 셀을 grid 에서 clear (master 제외).
                        //   원래 row 1 (rs=2 의 두번째 row) 의 INTERIOR 빈 셀들이 그대로 남아있어서
                        //   appendHtmlRow 가 occupied 무시하고 render 하던 버그 수정.
                        for (int rr = 0; rr < Math.min(title.rowSpan, t.rowCount); rr++) {
                            for (int cc = newC; cc < newC + newCS; cc++) {
                                if (rr == 0 && cc == newC) continue;
                                occupied[rr][cc] = true;
                                grid[rr][cc] = null;
                            }
                        }
                        // [v14.90 task3] 흡수 후, 모든 row 에서 col 0 / last col 이 비어있으면 skip 표시.
                        boolean colLeftEmpty = true, colRightEmpty = true;
                        for (int r2 = 0; r2 < t.rowCount; r2++) {
                            TableCell c0 = grid[r2][0];
                            if (c0 != null && cellVisibleLen(c0) > 0) colLeftEmpty = false;
                            TableCell cN = grid[r2][t.colCount - 1];
                            if (cN != null && cellVisibleLen(cN) > 0) colRightEmpty = false;
                        }
                        if (colLeftEmpty) skipCol[0] = true;
                        if (colRightEmpty) skipCol[t.colCount - 1] = true;
                    }
                }
            }
        }
        // make skipCol[] effective: occupied=true 로 표시해 appendHtmlRow 에서 skip
        for (int c = 0; c < t.colCount; c++) {
            if (skipCol[c]) {
                for (int r = 0; r < t.rowCount; r++) {
                    occupied[r][c] = true;
                    grid[r][c] = null;
                }
            }
        }

        // 복잡도 판단
        boolean hasNestedTable = false;
        for (TableCell cell : t.cells) {
            if (cell == null || cell.paragraphs == null) continue;
            for (Paragraph cp : cell.paragraphs) {
                if (cp.controls == null) continue;
                for (Control cc : cp.controls) {
                    if (cc instanceof CtrlTable) { hasNestedTable = true; break; }
                }
                if (hasNestedTable) break;
            }
            if (hasNestedTable) break;
        }

        // v13.18: 1~2행 × 2열 표 (번호열 + 제목열 패턴) 는 section heading 으로 승격
        //   HWP 에서 "번호.title" 형태의 강조 상자를 2-셀 표로 구현하는 경우가 많다.
        //   예) "| **3.** | **지방보조금 예산 편성 및 지원 대상** |" → "## 3. 지방보조금 예산 편성 및 지원 대상"
        //   추가 빈 행/열은 HWP 의 시각적 spacer 이므로 무시.
        if ((t.rowCount == 1 || t.rowCount == 2) && t.colCount == 2
                && !hasMerged && !hasNestedTable && nestLevel == 0) {
            TableCell leftCell  = grid[0][0];
            TableCell rightCell = grid[0][1];
            // 2행일 때 2행은 비어있어야 함 (단순 spacer)
            boolean secondRowEmpty = t.rowCount == 1;
            if (t.rowCount == 2) {
                TableCell l2 = grid[1][0], r2 = grid[1][1];
                String lv = l2 == null ? "" : renderCellContentInline(ctx, l2).trim();
                String rv = r2 == null ? "" : renderCellContentInline(ctx, r2).trim();
                secondRowEmpty = lv.isEmpty() && rv.isEmpty();
            }
            if (secondRowEmpty && leftCell != null && rightCell != null) {
                String left  = renderCellContentInline(ctx, leftCell).trim();
                String right = renderCellContentInline(ctx, rightCell).trim();
                String leftPlain  = stripLeadingTrailingFormatMarkers(left);
                String rightPlain = stripLeadingTrailingFormatMarkers(right).trim();
                // 좌측이 "N." "N)" "Ⅰ." "①" 등 짧은 번호 패턴이고 우측이 텍스트면 heading 으로
                if (leftPlain.length() <= 6 && !rightPlain.isEmpty()
                        && leftPlain.matches("^[0-9]+[.)]?\\s*$"
                                 + "|^[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+[.)]?\\s*$"
                                 + "|^[①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳]\\s*$")) {
                    // v13.20: 사용자 피드백에 따라 원색 배경 박스 제거.
                    //         단순 h2 Markdown heading 으로 복귀 (dummy_02 v13.17 스타일).
                    return "## " + leftPlain.trim() + " " + rightPlain;
                }
            }
        }

        // v13.19: 세로·가로 구분선이 명확히 보이도록 모든 표를 HTML 로 렌더링한다.
        //         (GFM 파이프 표는 뷰어에 따라 구분선이 희미하거나 누락되어 사용자
        //          피드백에서 "표가 리스트처럼 보인다" 이슈가 반복 제기됨.)
        //         HTML 은 border="1" + inline border-collapse 로 모든 뷰어에서 동일한
        //         구분선 표현이 보장된다.
        return renderTableHtml(ctx, t, grid, occupied, nestLevel);
    }

    /** 단순 표 → GFM 파이프 표. 첫 행을 헤더로 간주. */
    private String renderTableGfm(MdContext ctx, CtrlTable t, TableCell[][] grid) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < t.rowCount; r++) {
            sb.append('|');
            for (int c = 0; c < t.colCount; c++) {
                String v = grid[r][c] != null
                        ? renderCellContentInline(ctx, grid[r][c]) : "";
                sb.append(' ').append(sanitizeForGfmCell(v)).append(" |");
            }
            sb.append('\n');
            if (r == 0) {
                sb.append('|');
                for (int c = 0; c < t.colCount; c++) sb.append(" --- |");
                sb.append('\n');
            }
        }
        return rtrim(sb.toString());
    }

    /**
     * 복잡 표 → HTML &lt;table&gt;. 병합(rowspan/colspan), 중첩 표, 셀 내 여러 문단·이미지를
     * 온전히 보존한다.
     *
     * <p>v13.16: 표 구조 정확성 개선 — thead/tbody 의 여닫기가 모든 행 수에서
     * 올바르게 짝을 이루도록 단순화. 1행만 있는 표는 thead 하나로 닫고 tbody 를
     * 열지 않는다. 2행 이상이면 첫 행을 thead, 나머지를 tbody 로 감싼다.
     */
    private String renderTableHtml(MdContext ctx, CtrlTable t,
                                    TableCell[][] grid, boolean[][] occupied, int nestLevel) {
        String pad = "";
        StringBuilder sb = new StringBuilder();
        // v14.27: v13.41 참고 — inline border style 부여로 모든 MD viewer 에서
        //         표 구분선이 정상 표시되도록 함 (VS Code/GitHub/Typora 등).
        sb.append("<table style=\"border-collapse: collapse; border: 1px solid #666; width: 100%;\">\n");
        sb.append("<tbody>\n");
        appendHtmlRow(sb, ctx, t, grid, occupied, 0, "th", pad, nestLevel);
        for (int r = 1; r < t.rowCount; r++) {
            appendHtmlRow(sb, ctx, t, grid, occupied, r, "td", pad, nestLevel);
        }
        sb.append("</tbody>\n");
        sb.append("</table>");
        return sb.toString();
    }

    /**
     * HTML 한 행을 렌더링. rowspan/colspan/병합 셀을 처리한다.
     * v13.19: 셀 스타일 및 정렬 반영 (중앙정렬 감지, padding 적용).
     */
    private void appendHtmlRow(StringBuilder sb, MdContext ctx, CtrlTable t,
                                TableCell[][] grid, boolean[][] occupied,
                                int r, String cellTag, String pad, int nestLevel) {
        // v13.27: HTML row 를 pad 없이 평탄화하여 중첩 table 의 인덴트 누적으로
        //         Markdown 파서가 코드블록으로 오인하는 문제 회피.
        sb.append("<tr>\n");
        for (int c = 0; c < t.colCount; c++) {
            if (occupied[r][c] && grid[r][c] == null) continue;
            TableCell cell = grid[r][c];
            // v14.44 (task2): v13.41 출력 형식 복원.
            //   · style 속성을 rowspan/colspan 뒤에 배치
            //   · padding: 6px (v14.43: 4px 8px)
            //   · text-align 을 셀 컨텐츠 정렬 + 길이 휴리스틱으로 결정
            sb.append("<").append(cellTag);
            if (cell != null && cell.rowSpan > 1) sb.append(" rowspan=\"").append(cell.rowSpan).append("\"");
            if (cell != null && cell.colSpan > 1) sb.append(" colspan=\"").append(cell.colSpan).append("\"");
            // v14.44 (task2): cell alignment 결정 — v13.41 로직 그대로.
            //   detectCellAlign 결과: 2=center, 3=right, 0=left/justify
            //   visible 길이 > 20 또는 단락 수 > 1 인 td 만 left, 나머지는 center.
            String align;
            int detected = detectCellAlign(ctx.doc, cell);
            if (detected == 2) {
                align = "center";
            } else if (detected == 3) {
                align = "right";
            } else {
                int vlen = cellVisibleLen(cell);
                int paraCount = (cell == null || cell.paragraphs == null) ? 0 : cell.paragraphs.size();
                boolean longContent = (vlen > 20) || (paraCount > 1);
                align = (longContent && "td".equals(cellTag)) ? "left" : "center";
            }
            // [v14.85 task1] 셀의 BorderFill image fill → background-image style + 빈 셀 가시성 보장
            //   off-by-one 수정 (v14.84 는 borderFillId 를 1-base 그대로 list index 로 사용해
            //   잘못된 BorderFill 참조 — 6 image-fill 이 10 false-positive 로 emit 됨).
            String bgImgStyle = cellBgImageStyle(ctx, cell);
            sb.append(" style=\"padding: 6px; border: 1px solid #666; text-align: ").append(align)
              .append(";").append(bgImgStyle).append("\"");
            sb.append('>');
            if (cell != null) {
                // v14.45 (task1): cell-level visual align 을 renderCellContentBlock 에 전달.
                int cellFallback = "center".equals(align) ? 2 : ("right".equals(align) ? 3 : 0);
                String body = renderCellContentBlock(ctx, cell, nestLevel + 1, cellFallback).trim();
                body = convertCellImagesToHtml(body);
                body = convertMarkdownEmphasisToHtml(body);
                body = body.replaceAll("\n{2,}", "\n");
                body = escapeStrayAngleBrackets(body);
                if (body.contains("\n") || body.contains("<table")) {
                    sb.append('\n').append(body).append('\n');
                } else {
                    sb.append(body);
                }
            }
            sb.append("</").append(cellTag).append(">\n");
        }
        sb.append("</tr>\n");
    }

    /** v13.19: 셀 내부 단락들의 가장 빈번한 정렬을 반환 (0=justify/left, 2=center, 3=right). */
    private int detectCellAlign(HwpDocument doc, TableCell cell) {
        if (cell == null || cell.paragraphs == null || cell.paragraphs.isEmpty()) return 0;
        int centerCount = 0;
        int rightCount = 0;
        int totalVisible = 0;
        for (Paragraph p : cell.paragraphs) {
            int v = countVisibleChars(p.paraText == null ? null : p.paraText.rawBytes);
            if (v == 0) continue;
            totalVisible++;
            int a = getParagraphAlign(doc, p);
            if (a == 2) centerCount++;
            else if (a == 3) rightCount++;
        }
        if (totalVisible == 0) return 0;
        if (centerCount * 2 >= totalVisible) return 2;
        if (rightCount  * 2 >= totalVisible) return 3;
        return 0;
    }

    /**
     * v13.19: ParaShape 의 alignment 추출 (bits 2-4 of property1).
     * 0=JUSTIFY, 1=LEFT, 2=RIGHT, 3=CENTER — XmlHelper.parseAlignType 과 동일 매핑.
     * 반환값: 0=left/justify, 2=center, 3=right
     */
    private int getParagraphAlign(HwpDocument doc, Paragraph p) {
        if (p == null || p.paraShapeId < 0 || p.paraShapeId >= doc.paraShapes.size()) return 0;
        ParaShape ps = doc.paraShapes.get(p.paraShapeId);
        if (ps == null) return 0;
        int raw = (int) ((ps.property1 >> 2) & 0x7);
        // parseAlignType: 0=JUSTIFY, 1=LEFT, 2=RIGHT, 3=CENTER
        if (raw == 3) return 2;   // CENTER
        if (raw == 2) return 3;   // RIGHT
        return 0;
    }

    /**
     * v13.22: ParaShape.leftMargin (HWPUnit) 을 HTML em 단위로 근사 변환.
     *
     * <p>HWP 단위: 1 pt ≈ 100 HWPUnit, 1 em ≈ 13pt ≈ 1300 HWPUnit.
     * 들여쓰기 정도에 따라 적절한 padding-left em 값을 반환한다.
     *
     * @return em 값 (0 = 들여쓰기 없음, 0.5/1.0/2.0/... 로 반올림)
     */
    private double getParagraphIndentEm(HwpDocument doc, Paragraph p) {
        if (p == null || p.paraShapeId < 0 || p.paraShapeId >= doc.paraShapes.size()) return 0;
        ParaShape ps = doc.paraShapes.get(p.paraShapeId);
        if (ps == null) return 0;
        int leftMargin = ps.leftMargin;
        if (leftMargin <= 500) return 0;
        // 근사: 1em ≈ 1300 HWPUnit. 0.5em 단위로 반올림.
        double em = leftMargin / 1300.0;
        return Math.round(em * 2) / 2.0;  // 0.5 단위
    }

    /**
     * v13.19: HTML 블록 안의 Markdown emphasis 를 HTML 태그로 변환.
     * CommonMark 는 HTML 블록 내부에서 Markdown 을 파싱하지 않으므로
     * 셀 안의 **x** 가 리터럴로 남는 뷰어가 많아 <strong>x</strong> 로 교체.
     */
    private String convertMarkdownEmphasisToHtml(String s) {
        if (s == null || s.isEmpty()) return s;
        // v13.19: 이스케이프된 \* 를 placeholder 로 잠시 치환해 **...** 파싱을 안전하게 처리
        s = s.replace("\\*", "\uE010");  // escaped asterisk placeholder
        s = s.replace("\\_", "\uE011");  // escaped underscore placeholder
        // **text** → <strong>text</strong>
        s = s.replaceAll("\\*\\*([^*\\n]+?)\\*\\*", "<strong>$1</strong>");
        // *text* → <em>text</em>
        s = s.replaceAll("(?<!\\*)\\*([^*\\n]+?)\\*(?!\\*)", "<em>$1</em>");
        // ~~text~~ → <s>text</s>
        s = s.replaceAll("~~([^~\\n]+?)~~", "<s>$1</s>");
        // placeholder 복원 — HTML 컨텍스트이므로 백슬래시 제거 (escape 불필요)
        s = s.replace("\uE010", "*");
        s = s.replace("\uE011", "_");
        return s;
    }

    /**
     * 표 셀 렌더 경로 전용: 셀 본문의 마크다운 이미지 {@code ![alt](url)} 를
     * {@code <img src="url" alt="alt">} HTML 태그로 치환한다.
     *
     * <p>이유: CommonMark/GFM 규칙상 raw HTML 블록({@code <td>}) 내부의 인라인
     * 마크다운은 파싱되지 않아, 셀 안 이미지가 base64 data URI 리터럴로 노출된다.
     * 이 변환은 appendHtmlRow / 1×1 셀의 본문에만 적용되며, 표 밖 마크다운
     * 이미지 경로·역변환(md→hwp/hwpx) 등 다른 기능에는 관여하지 않는다.
     *
     * <p>순서: emphasis/escapeStrayAngleBrackets 이전에 적용. base64 표준 알파벳
     * ([A-Za-z0-9+/=]) 에는 * _ ~ &lt; &gt; " &amp; 가 없어 후속 변환이 data URI 를
     * 훼손하지 않고, 생성된 {@code <img} 는 letter 로 시작하므로 escapeStrayAngleBrackets
     * 가 보존한다.
     */
    private String convertCellImagesToHtml(String s) {
        if (s == null || s.isEmpty() || s.indexOf("![") < 0) return s;
        java.util.regex.Matcher m = CELL_IMG_PATTERN.matcher(s);
        StringBuffer out = new StringBuffer(s.length() + 32);
        while (m.find()) {
            String alt = m.group(1).replace("\\[", "[").replace("\\]", "]");
            String url = m.group(2);
            String altAttr = htmlAttrEscape(alt);
            String srcAttr = url.replace("&", "&amp;").replace("\"", "%22");
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    "<img src=\"" + srcAttr + "\" alt=\"" + altAttr + "\">"));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final java.util.regex.Pattern CELL_IMG_PATTERN =
            java.util.regex.Pattern.compile("!\\[((?:\\\\.|[^\\]])*)\\]\\(([^)]*)\\)");

    private String htmlAttrEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * v13.29: HTML 셀 content 내부의 "literal &lt; ... &gt;" (사용자 텍스트로 입력된
     * 꺾쇠표기) 를 HTML entity 로 escape.
     *
     * <p>HWP 원본에 "&lt;중요재산 관리 및 처분의 제한&gt;" 같은 한국어 꺾쇠표기가 있으면
     * 현재 변환은 {@code <중요재산 ...>} literal 로 내보내는데, 이를 브라우저가
     * unknown HTML element 로 파싱해 내부 content 가 렌더 안 되거나 인접 strong/td
     * 태그 경계를 망치는 문제가 생긴다.
     *
     * <p>규칙: {@code <} 직후 문자가 ASCII letter / {@code /} / {@code !} / {@code ?} 가
     * 아니면 {@code <} 는 사용자 텍스트 → {@code &lt;} 로 변환. 유효한 HTML 태그
     * ({@code <br>}, {@code <table>}, {@code <strong>}, {@code </u>}, 등) 는 보존.
     *
     * <p>그에 상응하는 {@code >} 는 약간 보수적으로 남겨둔다 (HTML parser 가 stray
     * {@code >} 는 대부분 literal 로 렌더).
     */
    private String escapeStrayAngleBrackets(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length() + 16);
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '<' && i + 1 < n) {
                char nxt = s.charAt(i + 1);
                // 유효한 태그 시작: ASCII letter, /, !, ? 중 하나로 시작
                boolean isValidTagStart = (nxt >= 'a' && nxt <= 'z')
                        || (nxt >= 'A' && nxt <= 'Z')
                        || nxt == '/' || nxt == '!' || nxt == '?';
                if (!isValidTagStart) {
                    out.append("&lt;");
                    continue;
                }
            } else if (c == '<' && i + 1 >= n) {
                out.append("&lt;");
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * GFM 셀용 인라인 내용 렌더링. 파이프 표 한 칸에 들어갈 수 있도록 줄바꿈을
     * 제거하고 블록 요소(표/이미지) 는 평면화된 대체 표기로 변환한다.
     */
    private String renderCellContentInline(MdContext ctx, TableCell cell) {
        if (cell.paragraphs == null || cell.paragraphs.isEmpty()) return "";
        StringBuilder tmp = new StringBuilder();
        for (Paragraph p : cell.paragraphs) {
            InlineResult ir = renderInline(ctx, p, 1);
            String s = ir.text.toString().trim();
            if (!s.isEmpty()) {
                if (tmp.length() > 0) tmp.append("<br>");
                tmp.append(s);
            }
            for (Object b : ir.blocks) {
                if (tmp.length() > 0) tmp.append("<br>");
                String bs = b.toString();
                // 블록(표/이미지) 이 셀에 들어오면 내용을 요약
                if (bs.startsWith("<table") || bs.startsWith("|")) {
                    tmp.append("[표 생략]");
                } else {
                    tmp.append(bs);
                }
            }
        }
        return tmp.toString();
    }

    /**
     * HTML 셀용 블록 내용 렌더링. 중첩 표, 여러 문단, 이미지를 온전히 출력한다.
     *
     * <p>v13.21: 단락별 정렬 정보를 반영해 중앙/우측 정렬된 단락은 {@code <div align="...">}
     * 로 감싸고, 빈 단락은 {@code <br>} 로 유지해 원본 HWP 간격을 보존한다.
     */
    private String renderCellContentBlock(MdContext ctx, TableCell cell, int nestLevel) {
        return renderCellContentBlock(ctx, cell, nestLevel, 0);
    }

    /**
     * v14.45 (task1): 셀 시각적 alignment 를 fallback 으로 받는 변형.
     * @param fallbackAlign 셀 단위로 결정된 정렬 (0=none, 2=center, 3=right). 단락의
     *   ParaShape 정렬이 0(left/justify) 일 때 적용되어 c1 과 동일한 {@code <div align>}
     *   wrapper 를 emit 한다. 정렬을 반영하지 않으려면 0 전달.
     */
    private String renderCellContentBlock(MdContext ctx, TableCell cell, int nestLevel, int fallbackAlign) {
        if (cell.paragraphs == null || cell.paragraphs.isEmpty()) return "";
        StringBuilder tmp = new StringBuilder();
        boolean hasPrevContent = false;  // 이전에 실제 내용이 출력되었는지
        int pendingBlankLines = 0;        // 연속된 빈 단락 수 (원본 간격 보존)

        for (Paragraph p : cell.paragraphs) {
            InlineResult ir = renderInline(ctx, p, nestLevel);
            String s = ir.text.toString().trim();
            int pAlign = getParagraphAlign(ctx.doc, p);
            // v14.45 (task1): 단락의 ParaShape 정렬이 0(left/justify) 이면 cell-level
            //   fallback 정렬을 적용. (case12 처럼 ParaShape alignment 가 누락되어
            //   appendHtmlRow 의 휴리스틱으로만 center 가 결정된 경우 c1 과 동일하게 마크업)
            int effectiveAlign = (pAlign == 0) ? fallbackAlign : pAlign;

            if (!s.isEmpty() || !ir.blocks.isEmpty()) {
                if (hasPrevContent) {
                    // 단락 구분자: <br> 하나 + 빈 단락당 <br> 추가
                    tmp.append("<br>\n");
                    for (int k = 0; k < pendingBlankLines; k++) tmp.append("<br>\n");
                }
                pendingBlankLines = 0;

                if (!s.isEmpty()) {
                    s = s.replace("  \n", "<br>\n");
                    // v14.44 (task2): v13.41 거동 — 셀 내부에서도 center/right 단락은
                    //   <div align="..."> 으로 감싸 시각적 정렬 유지.
                    if (effectiveAlign == 2) {
                        tmp.append("<div align=\"center\">").append(s).append("</div>");
                    } else if (effectiveAlign == 3) {
                        tmp.append("<div align=\"right\">").append(s).append("</div>");
                    } else {
                        tmp.append(s);
                    }
                    hasPrevContent = true;
                }
                for (Object b : ir.blocks) {
                    if (hasPrevContent) tmp.append('\n');
                    tmp.append(b.toString());
                    hasPrevContent = true;
                }
            } else if (hasPrevContent) {
                pendingBlankLines++;
            }
        }
        return tmp.toString();
    }

    /** v13.31: 셀의 모든 단락의 visible char 수 합산. */
    private int cellVisibleLen(TableCell cell) {
        if (cell == null || cell.paragraphs == null) return 0;
        int n = 0;
        for (Paragraph p : cell.paragraphs) {
            if (p.paraText != null) n += countVisibleChars(p.paraText.rawBytes);
        }
        return n;
    }

    /** v13.26: 셀 내부의 비어있지 않은 단락 수. */
    private int countNonEmptyParagraphs(TableCell cell) {
        if (cell == null || cell.paragraphs == null) return 0;
        int n = 0;
        for (Paragraph p : cell.paragraphs) {
            if (p.paraText != null && countVisibleChars(p.paraText.rawBytes) > 0) n++;
        }
        return n;
    }

    /** v13.18: 셀에 block 요소(중첩표) 가 있는지 검사. */
    private boolean cellContainsBlockElements(TableCell cell) {
        if (cell == null || cell.paragraphs == null) return false;
        for (Paragraph p : cell.paragraphs) {
            if (p.controls == null) continue;
            for (Control c : p.controls) {
                if (c instanceof CtrlTable) return true;
            }
        }
        return false;
    }

    /**
     * GFM 표 한 칸 내용 정제: 파이프·줄바꿈 안전 처리.
     * v13.18: HTML 문자({@code <}, {@code >}, {@code &}) 를 엔티티로 변환.
     *   GFM 셀 안의 {@code <password>} 같은 리터럴 텍스트를 Markdown 파서가
     *   HTML 태그로 오해하면 행 전체 구조가 깨질 수 있으므로 셀 안에서만
     *   엔티티 처리한다. 단, 우리가 직접 추가한 포맷 태그({@code <br>},
     *   {@code <u>}, {@code </u>}) 는 placeholder 로 보존 후 복원한다.
     */
    private String sanitizeForGfmCell(String s) {
        // 1) 우리가 의도적으로 넣은 HTML 포맷 태그를 BMP PUA 문자로 임시 치환
        String t = s.replace("<br>", "\uE001")
                    .replace("<u>",  "\uE002")
                    .replace("</u>", "\uE003");
        // 2) 나머지 HTML 특수문자 엔티티화 (리터럴 텍스트 보호)
        t = t.replace("&", "&amp;")
             .replace("<", "&lt;")
             .replace(">", "&gt;");
        // 3) 포맷 태그 복원
        t = t.replace("\uE001", "<br>")
             .replace("\uE002", "<u>")
             .replace("\uE003", "</u>");
        // 4) 줄바꿈·파이프 처리
        return t.replace("\n", "<br>")
                .replace("\r", "")
                .replace("|", "\\|");
    }

    private String repeatSpaces(int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }

    private String indentLines(String block, String indent) {
        StringBuilder sb = new StringBuilder(block.length() + 32);
        boolean first = true;
        for (String line : block.split("\n", -1)) {
            if (!first) sb.append('\n');
            sb.append(indent).append(line);
            first = false;
        }
        return sb.toString();
    }

    // =========================================================
    //  이미지 렌더링
    // =========================================================

    /**
     * v14.24: HWP/HWPX → MD 변환 시 이미지를 별도 파일로 추출하지 않고 base64
     * data URI 로 MD 본문에 직접 임베드한다. MD 한 파일에 모든 이미지가 포함되므로
     * 사이드카 폴더가 필요 없고, 단일 파일 공유에 편리하다.
     *
     * 형식: ![alt](data:image/png;base64,iVBORw0KGgoAAA...)
     */
    private String renderImage(MdContext ctx, CtrlPicture pic) {
        BinDataItem bin = findBinData(ctx.doc, pic.binItemId);
        if (bin == null) return null;
        // 외부 링크 (절대 경로 BinData) 인 경우 그대로 링크 유지
        if ((bin.data == null || bin.data.length == 0)
                && bin.absolutePath != null && !bin.absolutePath.isEmpty()) {
            return "![" + escapeLinkText(safeAlt(pic)) + "]("
                    + escapeLinkUrl(bin.absolutePath) + ")";
        }
        if (bin.data == null || bin.data.length == 0) return null;
        // base64 임베드 (외부 imgDir 가 있으면 파일 추출도 같이 — 호환성 유지)
        try {
            String ext = (bin.extension != null && !bin.extension.isEmpty())
                    ? normalizeExt(bin.extension) : guessExt(bin.data);
            String mime = mimeFromExt(ext);
            String b64 = java.util.Base64.getEncoder().encodeToString(bin.data);
            String dataUri = "data:" + mime + ";base64," + b64;
            return "![" + escapeLinkText(safeAlt(pic)) + "](" + dataUri + ")";
        } catch (Exception e) {
            System.err.println("[HwpMdConverter] 이미지 base64 인코딩 실패 (binDataId="
                    + pic.binItemId + "): " + e.getMessage());
            return null;
        }
    }

    /** 확장자 → MIME type 매핑. */
    private String mimeFromExt(String ext) {
        String e = ext.toLowerCase();
        if (e.startsWith(".")) e = e.substring(1);
        switch (e) {
            case "png":  return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "gif":  return "image/gif";
            case "bmp":  return "image/bmp";
            case "svg":  return "image/svg+xml";
            case "webp": return "image/webp";
            default:     return "application/octet-stream";
        }
    }

    private String safeAlt(CtrlPicture pic) {
        if (pic.description != null && !pic.description.isEmpty()) {
            // v14.26: collapse ALL whitespace (including newlines) to single space.
            // Markdown image alt text MUST be single-line; multi-line alt breaks
            // rendering in VS Code/GitHub/Typora preview.
            String desc = pic.description
                    .replaceAll("\\s+", " ")
                    .trim();
            if (!desc.isEmpty()) return desc;
        }
        return "image-" + pic.binItemId;
    }

    private String normalizeExt(String ext) {
        String e = ext.toLowerCase();
        if (!e.startsWith(".")) e = "." + e;
        return e;
    }

    private String guessExt(byte[] data) {
        if (data.length >= 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return ".png";
        if (data.length >= 3
                && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) return ".jpg";
        if (data.length >= 6
                && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8') return ".gif";
        if (data.length >= 2 && data[0] == 'B' && data[1] == 'M') return ".bmp";
        return ".bin";
    }

    /**
     * [v14.86 task1] 그리기 도형 (rect/ellipse/line/polygon 등) 의 drawText 본문을
     * bordered &lt;div&gt; 로 렌더링. MdToHwpRich 가 이 div 의 텍스트를 추출해
     * HWP 로 라운드트립 시 해당 단락이 본문에 보존됨 (도형 형태는 단순 단락으로 평탄화).
     *
     * [v14.87 task2/3] 각 paragraph 별 ParaShape 정렬을 보존 — 이전 v14.86 은 wrapper 만
     *   center 로 고정하여 모든 본문이 중앙정렬됨. 이제 paragraph 마다 align="left|center|right"
     *   을 emit, MdToHwpRich.parseMarkdown 가 이를 인식해 HWP 라운드트립에 정렬 보존.
     */
    private String renderShapeText(MdContext ctx, CtrlPicture shape, int nestLevel) {
        StringBuilder body = new StringBuilder();
        for (Paragraph p : shape.textboxParagraphs) {
            InlineResult ir = renderInline(ctx, p, nestLevel);
            String s = ir.text.toString().trim();
            if (s.isEmpty()) continue;
            int al = getParagraphAlign(ctx.doc, p);   // 0=left/justify, 2=center, 3=right
            String alignAttr = (al == 2) ? "center" : (al == 3) ? "right" : "left";
            if (body.length() > 0) body.append('\n');
            body.append("<p align=\"").append(alignAttr).append("\">").append(s).append("</p>");
        }
        if (body.length() == 0) return null;
        return "<div style=\"border: 1px solid #000; padding: 8px; margin: 8px 0;\">\n"
                + body + "\n</div>";
    }

    private BinDataItem findBinData(HwpDocument doc, int binDataId) {
        for (BinDataItem it : doc.binDataItems) {
            if (it.binDataId == binDataId) return it;
        }
        return null;
    }

    /**
     * [v14.85 task1] 셀의 BorderFill 에 image fill 이 설정되어 있으면
     * background-image: url(data:...) 스타일 단편을 반환. 없으면 빈 문자열.
     *
     * cell.borderFillId 는 HWPX borderFillIDRef (1-base) 이므로 list index = bfId-1.
     * 빈 셀에서도 이미지가 보이도록 min-width/min-height 명시.
     */
    private String cellBgImageStyle(MdContext ctx, kr.n.nframe.hwplib.model.TableCell cell) {
        if (cell == null || ctx == null || ctx.doc == null) return "";
        int bfId = cell.borderFillId;
        int idx = bfId - 1;
        if (idx < 0 || idx >= ctx.doc.borderFills.size()) return "";
        kr.n.nframe.hwplib.model.BorderFill bf = ctx.doc.borderFills.get(idx);
        if (bf == null || (bf.fillType & 2) == 0) return "";
        BinDataItem bin = findBinData(ctx.doc, bf.imgBinItemId);
        if (bin == null || bin.data == null || bin.data.length == 0) return "";
        try {
            String ext = (bin.extension != null && !bin.extension.isEmpty())
                    ? normalizeExt(bin.extension) : guessExt(bin.data);
            String mime = mimeFromExt(ext);
            String b64 = java.util.Base64.getEncoder().encodeToString(bin.data);
            // background-size: contain — 이미지 비율 유지하며 셀 크기에 맞춤
            // min-width/min-height — 빈 셀에서도 가시화
            return " background-image: url(data:" + mime + ";base64," + b64
                    + "); background-size: contain; background-repeat: no-repeat;"
                    + " background-position: center; min-width: 120px; min-height: 80px;";
        } catch (Exception e) {
            return "";
        }
    }

    // =========================================================
    //  휴리스틱: 제목/리스트 감지
    // =========================================================

    private int detectHeadingLevel(HwpDocument doc, Paragraph p) {
        if (p.styleId >= 0 && p.styleId < doc.styles.size()) {
            Style s = doc.styles.get(p.styleId);
            if (s != null) {
                int lvl = matchHeadingLevel(s.englishName);
                if (lvl > 0) return lvl;
                lvl = matchHeadingLevel(s.localName);
                if (lvl > 0) return lvl;
            }
        }
        return detectHeadingByCharShape(doc, p);
    }

    /**
     * v13.18: 스타일 정의 없이 수동 포맷팅만으로 표제를 강조한 문단을 heading 으로 인식.
     *
     * <p>판정 기준:
     * <ul>
     *   <li>문단 내 모든 실제 문자의 첫 CharShape 가 bold 이고</li>
     *   <li>baseSize 가 1500 (= 15pt) 이상이면 heading</li>
     *   <li>22pt 이상 → h1, 18pt 이상 → h2, 15pt 이상 → h3</li>
     * </ul>
     * 텍스트가 짧고 단일 포맷일 때만 작동 (본문 안의 강조는 heading 취급 안 함).
     */
    private int detectHeadingByCharShape(HwpDocument doc, Paragraph p) {
        if (p.paraText == null || p.paraText.rawBytes == null) return 0;
        // v13.18: 가시 문자만 카운트 (secPr/tab/field 등 제어 문자 제외)
        int visibleLen = countVisibleChars(p.paraText.rawBytes);
        if (visibleLen < 1 || visibleLen > 40) return 0;
        if (p.charShapeRefs == null || p.charShapeRefs.isEmpty()) return 0;

        // 파라그래프 안의 모든 CharShape 중 가장 큰 bold charShape 기준으로 판정
        // (첫 ref 는 secPr 의 기본 charShape 일 수 있어 실제 텍스트 스타일과 다를 수 있음)
        int maxSize = 0;
        boolean maxBold = false;
        for (CharShapeRef ref : p.charShapeRefs) {
            int id = (int) ref.charShapeId;
            if (id < 0 || id >= doc.charShapes.size()) continue;
            CharShape cs = doc.charShapes.get(id);
            if (cs == null) continue;
            boolean b = (cs.property & CHARPROP_BOLD) != 0;
            if (cs.baseSize > maxSize) {
                maxSize = cs.baseSize;
                maxBold = b;
            }
        }
        if (maxSize >= 2800) return 1;
        if (!maxBold) return 0;
        if (maxSize >= 2200)      return 1;
        else if (maxSize >= 1800) return 2;
        else if (maxSize >= 1500) return 3;
        return 0;
    }

    /** v13.21: 문자열 앞부분의 공백/탭/NBSP 개수 — 우측 정렬 감지용. */
    private int countLeadingWhitespace(String s) {
        if (s == null) return 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\u00A0' || c == '\u3000') i++;
            else break;
        }
        return i;
    }

    /** v13.18: ParaText 의 가시 문자(일반 BMP + astral) 개수만 계산. 제어 문자는 제외. */
    private int countVisibleChars(byte[] raw) {
        if (raw == null || raw.length < 2) return 0;
        int wcharCount = raw.length / 2;
        int visible = 0;
        int pos = 0;
        while (pos < wcharCount) {
            char c = (char) (((raw[pos * 2] & 0xFF)) | ((raw[pos * 2 + 1] & 0xFF) << 8));
            if (c == CH_PARA_END) break;
            if (isExtendedControlChar(c)) { pos += 8; continue; }
            if (c == CH_FIELD_END || c == CH_INLINE_END || c == CH_TAB) {
                // 8-WCHAR tab
                if (c == CH_TAB && pos + 7 < wcharCount) {
                    char c7 = (char) (((raw[(pos + 7) * 2] & 0xFF)) | ((raw[(pos + 7) * 2 + 1] & 0xFF) << 8));
                    if (c7 == CH_TAB) { pos += 8; continue; }
                }
                pos += 3; continue;
            }
            if (c < 0x0020) { pos++; continue; }
            visible++;
            pos++;
        }
        return visible;
    }

    private int matchHeadingLevel(String name) {
        if (name == null) return 0;
        String n = name.trim();
        if (n.isEmpty()) return 0;
        String lower = n.toLowerCase();
        String[] prefixes = {"heading", "outline", "개요", "제목"};
        for (String pref : prefixes) {
            if (lower.startsWith(pref)) {
                String rest = n.substring(pref.length()).trim();
                if (rest.isEmpty()) return 0;
                try {
                    int lvl = Integer.parseInt(rest);
                    if (lvl < 1) return 0;
                    return Math.min(lvl, 6);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    private int detectListLevel(HwpDocument doc, Paragraph p) {
        if (p.paraShapeId < 0 || p.paraShapeId >= doc.paraShapes.size()) return 0;
        ParaShape ps = doc.paraShapes.get(p.paraShapeId);
        if (ps == null) return 0;
        return ps.numberingId > 0 ? 1 : 0;
    }

    private boolean isOrderedList(HwpDocument doc, Paragraph p) {
        if (p.paraShapeId < 0 || p.paraShapeId >= doc.paraShapes.size()) return false;
        ParaShape ps = doc.paraShapes.get(p.paraShapeId);
        if (ps == null) return false;
        return ps.numberingId > 0 && ps.numberingId <= doc.numberings.size();
    }

    // =========================================================
    //  포맷 상태
    // =========================================================

    private FormatState nextFormat(HwpDocument doc, CharShapeRef ref) {
        if (ref == null) return null;
        int id = (int) ref.charShapeId;
        if (id < 0 || id >= doc.charShapes.size()) return null;
        CharShape cs = doc.charShapes.get(id);
        if (cs == null) return null;
        FormatState f = new FormatState();
        f.italic    = (cs.property & CHARPROP_ITALIC) != 0;
        f.bold      = (cs.property & CHARPROP_BOLD)   != 0;
        f.underline = (cs.property & CHARPROP_UL_TYPE_MASK) != 0;
        f.strike    = (cs.property & CHARPROP_STRIKE_MASK) != 0;
        return f.isEmpty() ? null : f;
    }

    private boolean formatEquals(FormatState a, FormatState b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.bold == b.bold && a.italic == b.italic
                && a.strike == b.strike && a.underline == b.underline;
    }

    // =========================================================
    //  유틸
    // =========================================================

    private boolean isExtendedControlChar(char c) {
        return c == CH_SECD || c == CH_FIELD_BEGIN || c == CH_OBJECT
                || c == CH_HIDDEN_CMT || c == CH_HDR_FTR || c == CH_FOOTNOTE
                || c == CH_AUTO_NUMBER || c == CH_PAGE_NUM_POS
                || c == CH_BOOKMARK || c == CH_RUBY_TEXT;
    }

    /**
     * v13.16: Markdown 에 그대로 옮기기 부적절한 비표시 문자 감지.
     *
     * <p>HWP 는 목차의 페이지번호 placeholder, 북마크 앵커, 내부 마킹 등에
     * 특수 LATIN-Extended/Combining/PUA 문자를 사용하는 경우가 있다.
     * 예: {@code U+0203 (ȃ)} 은 목차의 탭 리더 뒤 페이지번호 자리 표시로 관찰됨.
     * 이런 문자들은 Markdown 에 남으면 의미 없는 기호로 보이므로 걸러낸다.
     *
     * <p>필터 기준:
     * <ul>
     *   <li>HWP 내부 마킹에서 관찰된 LATIN Extended-B 일부 ({@code U+0180–U+024F})</li>
     *   <li>Private Use Area ({@code U+E000–U+F8FF})</li>
     *   <li>Specials — Object Replacement Character ({@code U+FFFC})</li>
     *   <li>Combining diacritics 중 HWP 가 placeholder 로 쓰는 범위</li>
     * </ul>
     */
    private boolean isNonPrintableDisplayChar(char c) {
        // Object Replacement Character (표/개체 자리 표시)
        if (c == 0xFFFC) return true;
        // HWP 가 목차/색인 placeholder 로 쓰는 관찰된 LATIN Extended-B 범위
        if (c >= 0x0180 && c <= 0x024F) return true;
        // Private Use Area BMP
        if (c >= 0xE000 && c <= 0xF8FF) return true;
        // Combining diacritics — 단독으로 의미가 없는 결합 문자가 placeholder 로 온 경우
        if (c >= 0x0300 && c <= 0x036F) return true;
        return false;
    }

    /**
     * v13.17: HWP 한글 문서는 "HY견고딕" 등 HWP 전용 폰트의 PUA (Supplementary PUA-A
     * U+F0000~U+FFFFD) 영역에 원문자·화살표·도장 등 커스텀 글리프를 할당한다.
     * Markdown 으로 그대로 옮기면 ? 기호로 보이므로, 관찰된 매핑을 Unicode 표준
     * 대체 문자로 바꿔 출력한다.
     *
     * <p>매핑 원천: dummy 시험 파일에서 직접 관찰된 코드 포인트 + HWP 5.0 매뉴얼의
     * 폰트 레이아웃. 매핑이 없으면 null 반환 → 호출자가 필터 처리.
     */
    private String mapHwpPuaCodePoint(int cp) {
        // Supplementary PUA-A U+F0000~U+F8FFF 이외는 매핑 대상 아님
        if (cp < 0xF0000 || cp > 0xFFFFD) return null;

        switch (cp) {
            // 화살표 (HWP PUA-A 0xF003A~0xF003D)
            case 0xF003A: return "←";
            case 0xF003B: return "↓";
            case 0xF003C: return "↑";
            case 0xF003D: return "→";

            // 도장/인장 (HWP PUA-A 0xF00E0~0xF00E1)
            // v13.21: 한자 "印" 이 원(원문자)으로 감싸진 "㊞" (U+329E CIRCLED IDEOGRAPH SEAL)
            //         으로 교체. 원본 도장 자리표시와 시각적으로 가장 유사한 단일 글리프.
            case 0xF00E0: return "㊞";
            case 0xF00E1: return "㊞";

            // 원문자 ①~⑳ (HWP PUA-A 0xF02B1 base)
            case 0xF02B1: return "①";
            case 0xF02B2: return "②";
            case 0xF02B3: return "③";
            case 0xF02B4: return "④";
            case 0xF02B5: return "⑤";
            case 0xF02B6: return "⑥";
            case 0xF02B7: return "⑦";
            case 0xF02B8: return "⑧";
            case 0xF02B9: return "⑨";
            case 0xF02BA: return "⑩";
            case 0xF02BB: return "⑪";
            case 0xF02BC: return "⑫";
            case 0xF02BD: return "⑬";
            case 0xF02BE: return "⑭";
            case 0xF02BF: return "⑮";
            case 0xF02C0: return "⑯";
            case 0xF02C1: return "⑰";
            case 0xF02C2: return "⑱";
            case 0xF02C3: return "⑲";
            case 0xF02C4: return "⑳";

            // 추가적인 PUA 는 향후 관찰 시 확장
            default: return null;
        }
    }

    private boolean endsWithBlankLine(StringBuilder sb) {
        int n = sb.length();
        if (n == 0) return true;
        if (sb.charAt(n - 1) != '\n') return false;
        if (n < 2) return true;
        return sb.charAt(n - 2) == '\n';
    }

    private boolean hasAnyImage(HwpDocument doc) {
        for (BinDataItem it : doc.binDataItems) {
            if (it.data != null && it.data.length > 0) return true;
            if (it.absolutePath != null && !it.absolutePath.isEmpty()) return true;
        }
        return false;
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String rtrim(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    private String trimTrailingBlankLines(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\n') end--;
        return s.substring(0, end);
    }

    private String extractHyperlinkUrl(String cmd) {
        if (cmd == null) return null;
        int best = -1;
        for (int i = 0; i < cmd.length(); i++) {
            char ch = cmd.charAt(i);
            if (ch == '\u0001' || ch == ';') { best = i; break; }
        }
        String url = best >= 0 ? cmd.substring(0, best) : cmd;
        url = url.trim();
        // v13.16: HWP 는 URL 앞에 제어 escape 역슬래시를 붙이거나 http\:// 처럼
        // 콜론 앞에 `\` 를 넣기도 한다. Markdown 링크에서는 불필요한 `\` 를 정리한다.
        url = url.replace("\\:", ":").replace("\\/", "/");
        if (url.startsWith("\\")) url = url.substring(1);
        return url;
    }

    private void logDocStats(HwpDocument doc) {
        System.out.println("[HwpMdConverter] Sections: " + doc.sections.size()
                + ", Styles: " + doc.styles.size()
                + ", CharShapes: " + doc.charShapes.size()
                + ", BinData: " + doc.binDataItems.size());
    }

    /**
     * v13.17: 입력 파일 존재 확인 + 확장자 검사.
     * 잘못된 경로 · 따옴표 누락 등으로 파일이 없을 때 명확한 한글 메시지 제공.
     */
    private static void ensureInputExists(String inputPath, String expectedExt) {
        if (inputPath == null || inputPath.isEmpty()) {
            throw new IllegalArgumentException("[HwpMdConverter] 입력 파일 경로가 비어 있습니다.");
        }
        Path p = Paths.get(inputPath);
        if (!Files.exists(p)) {
            throw new IllegalArgumentException(
                    "[HwpMdConverter] 입력 파일을 찾을 수 없습니다: " + inputPath + "\n"
                    + "  · 파일 경로가 올바른지 확인하세요.\n"
                    + "  · 경로에 공백·한글·특수문자가 있으면 반드시 큰따옴표(\") 로 감쌌는지 확인하세요.\n"
                    + "  · 여는 따옴표와 닫는 따옴표의 쌍이 맞는지 확인하세요.");
        }
        if (Files.isDirectory(p)) {
            throw new IllegalArgumentException(
                    "[HwpMdConverter] 입력이 디렉터리입니다. 파일 경로를 지정하세요: " + inputPath);
        }
        if (expectedExt != null && !inputPath.toLowerCase().endsWith(expectedExt)) {
            throw new IllegalArgumentException(
                    "[HwpMdConverter] 입력 확장자가 " + expectedExt + " 가 아닙니다: " + inputPath);
        }
    }

    private static void ensureDistinctPaths(String in, String out) {
        if (in == null || out == null) return;
        Path inP = Paths.get(in);
        Path outP = Paths.get(out);
        try {
            if (Files.exists(inP) && Files.exists(outP) && Files.isSameFile(inP, outP)) {
                throw new IllegalArgumentException("입력과 출력이 같은 파일을 가리킵니다: " + in);
            }
        } catch (IOException ignored) {
        }
        if (inP.toAbsolutePath().normalize().equals(outP.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("입력과 출력 경로가 동일합니다 (정규화 후): " + in);
        }
    }

    private static void ensureParentDir(String outputPath) throws IOException {
        if (outputPath == null) return;
        File out = new File(outputPath);
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent == null) return;
        if (parent.exists()) {
            if (!parent.isDirectory()) {
                throw new IOException("출력 경로의 부모가 디렉터리가 아닙니다: " + parent);
            }
            return;
        }
        if (!parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("출력 디렉터리 생성 실패: " + parent);
        }
    }

    // =========================================================
    //  내부 구조체
    // =========================================================

    private static class MdContext {
        final HwpDocument doc;
        final StringBuilder out = new StringBuilder(4096);
        final Path imgDir;
        final Path outDir;
        // v13.25: 연속된 TOC entry 를 누적해 단일 <table> 로 한 번에 flush
        final List<TocEntry> pendingToc = new ArrayList<>();
        // v13.26: 연속된 우측정렬 단락을 누적해 단일 <pre> 로 flush
        final List<String> pendingRightLines = new ArrayList<>();
        // v13.26: TOC 시작 직전에 추출한 1x1 강조 박스를 TOC 뒤에 재배치
        String deferredBoxAfterToc = null;

        MdContext(HwpDocument doc, Path imgDir, Path outDir) {
            this.doc = doc;
            this.imgDir = imgDir;
            this.outDir = outDir;
        }
    }

    /** v13.25: TOC 항목 임시 저장 구조체. */
    private static class TocEntry {
        final String title;
        final String page;
        final int level;
        final boolean major;  // 로마숫자 (Ⅰ/Ⅱ/Ⅲ…) 상위 섹션
        TocEntry(String title, String page, int level, boolean major) {
            this.title = title;
            this.page = page;
            this.level = level;
            this.major = major;
        }
    }

    private static class InlineResult {
        final StringBuilder text = new StringBuilder();
        final List<Object> blocks = new ArrayList<>();
    }

    private static class FormatState {
        boolean bold;
        boolean italic;
        boolean strike;
        boolean underline;
        boolean isEmpty() { return !bold && !italic && !strike && !underline; }
    }

    private static class LinkState {
        final String url;
        final StringBuilder text = new StringBuilder();
        LinkState(String url) { this.url = url; }
    }
}
