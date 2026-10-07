package kr.n.nframe.hwplib.writer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 방금 작성된 HWPX 파일을 XML 바이트 수준에서 후처리하여 그 구조가
 * 한글 프로그램이 직접 생성하는 것과 더 가깝게 일치하도록 합니다.
 * 목표는 hwpxlib 객체 API로 쉽게 표현할 수 없는 미묘한 레이아웃 측면에 대해
 * 한글이 생성한 HWPX와의 시각적 동등성을 복원하는 것입니다.
 *
 * <p>{@code Contents/section0.xml}에 적용되는 재작성:
 * <ul>
 *   <li><b>말미 빈 run 삽입</b> — 한글은 최소 하나의 텍스트 포함 run이 있는
 *       모든 문단에서 {@code <hp:linesegarray>} 바로 앞에
 *       {@code <hp:run charPrIDRef="0"/>}을 생성합니다. 이는 줄 끝 표시자 역할을 하며
 *       한글이 재배치(reflow) 중에 문단 높이를 계산하는 방식에 영향을 줍니다.
 *       이것이 없으면 거의 빈 페이지의 긴 문단이 축소되고
 *       예상되는 시각적 빈 페이지가 사라집니다
 *       (TA-05의 의도된 빈 2페이지에서 관찰됨).</li>
 *   <li><b>{@code <hp:tc>}의 {@code name=""} 속성</b> — 한글은
 *       모든 표 셀에 빈 {@code name} 속성을 작성합니다.
 *       hwpxlib-1.0.9는 이 속성을 생략합니다. 누락된 속성은 많은 리더에서
 *       허용되지만 엄격한 리더를 혼란스럽게 할 수 있습니다.</li>
 * </ul>
 *
 * <p>또한 한글이 HWPX 출력에 항상 포함하는 {@code META-INF/container.rdf}
 * (매니페스트 RDF)를 추가합니다.
 *
 * <p>구현은 수백 킬로바이트 크기의 섹션 파일에 대해 전체 XML 파싱/직렬화
 * 왕복의 비용과 인코딩 취약성을 피하기 위해 바이트 수준 문자열 치환을
 * 사용합니다.
 */
public final class HwpxXmlRewriter {
    private HwpxXmlRewriter() {}

    /** [v15.28] 1x1 투명 PNG placeholder (한/글 Preview/PrvImage.png 누락 시 대체용). */
    private static final byte[] PRVIMAGE_PNG_1X1 = new byte[] {
        (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,                   // PNG signature
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,                          // IHDR length=13, "IHDR"
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,                          // width=1, height=1
        0x08, 0x06, 0x00, 0x00, 0x00,                                            // depth=8, RGBA, 0,0,0
        (byte)0x1F, 0x15, (byte)0xC4, (byte)0x89,                                // IHDR CRC
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54,                          // IDAT length=13, "IDAT"
        0x78, (byte)0x9C, 0x62, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,        // zlib stream
        0x0D, 0x0A, 0x2D, (byte)0xB4,                                            // (end of stream + adler32)
        0x00, 0x00, 0x00, 0x00,                                                   // IDAT CRC (placeholder)
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,                          // IEND length=0, "IEND"
        (byte)0xAE, 0x42, 0x60, (byte)0x82                                       // IEND CRC
    };

    /** [v15.28] content.hpf 의 Java Date.toString() 형식 (예: "Thu Apr 23 11:45:48 KST 2026")
     *  값을 ISO 8601 형식 (예: "2026-04-23T11:45:48Z") 으로 정규화. 원본 HWPX 와 동일 포맷. */
    static String normalizeContentHpfDates(String hpf) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(<opf:meta\\s+name=\"(?:CreatedDate|ModifiedDate)\"\\s+content=\"text\">)"
            + "([A-Z][a-z]{2}\\s+[A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}\\s+\\S+\\s+\\d{4})"
            + "(</opf:meta>)");
        java.util.regex.Matcher m = p.matcher(hpf);
        StringBuffer sb = new StringBuffer();
        java.text.SimpleDateFormat in = new java.text.SimpleDateFormat(
                "EEE MMM dd HH:mm:ss zzz yyyy", java.util.Locale.US);
        java.text.SimpleDateFormat out = new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        out.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        while (m.find()) {
            String date = m.group(2);
            String iso = date;
            try {
                java.util.Date d = in.parse(date);
                iso = out.format(d);
            } catch (Exception ignored) { /* keep original if parse fails */ }
            m.appendReplacement(sb,
                java.util.regex.Matcher.quoteReplacement(m.group(1) + iso + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static final String CONTAINER_RDF =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>"
          + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
          + "<rdf:Description rdf:about=\"\">"
          + "<ns0:hasPart xmlns:ns0=\"http://www.hancom.co.kr/hwpml/2016/meta/pkg#\""
          + " rdf:resource=\"Contents/header.xml\"/>"
          + "</rdf:Description>"
          + "<rdf:Description rdf:about=\"Contents/header.xml\">"
          + "<rdf:type rdf:resource=\"http://www.hancom.co.kr/hwpml/2016/meta/pkg#HeaderFile\"/>"
          + "</rdf:Description>"
          + "<rdf:Description rdf:about=\"\">"
          + "<ns0:hasPart xmlns:ns0=\"http://www.hancom.co.kr/hwpml/2016/meta/pkg#\""
          + " rdf:resource=\"Contents/section0.xml\"/>"
          + "</rdf:Description>"
          + "<rdf:Description rdf:about=\"Contents/section0.xml\">"
          + "<rdf:type rdf:resource=\"http://www.hancom.co.kr/hwpml/2016/meta/pkg#SectionFile\"/>"
          + "</rdf:Description>"
          + "<rdf:Description rdf:about=\"\">"
          + "<rdf:type rdf:resource=\"http://www.hancom.co.kr/hwpml/2016/meta/pkg#Document\"/>"
          + "</rdf:Description>"
          + "</rdf:RDF>";

    /**
     * {@code path}에 있는 HWPX 파일을 제자리에서 재작성합니다.
     * 어떤 단계라도 실패하면 원본 파일은 그대로 유지됩니다.
     */
    public static void rewrite(String path) {
        rewrite(path, null, null);
    }

    public static void rewrite(String path, List<Integer> astralCodePoints) {
        rewrite(path, astralCodePoints, null);
    }

    /**
     * 선택적 astral 코드 포인트 목록과 탭 설정 목록으로 재작성합니다.
     * <ul>
     *  <li>{@code astralCodePoints} — hwplib의 {@code HWPCharNormal.getCh()}가
     *      잃어버린 surrogate pair 문자를 복구합니다.</li>
     *  <li>{@code tabSettings} — 소스 순서대로 탭별
     *      {@code int[]{width,leader,type}} 목록. hwp2hwpx-1.0.0의
     *      {@code ForChars.addTab()}이 {@code width=4000 leader=0(NONE) type=1(LEFT)}로
     *      하드코딩한 원래 탭 매개변수 (목차 점 리더, 우측 정렬 페이지 번호 탭 등)를
     *      복원합니다. 소스 HWP에서 목록을 채우는 방법은
     *      {@link kr.n.nframe.HwpConverter#convertHwpToHwpx}를 참조하십시오.</li>
     * </ul>
     */
    public static void rewrite(String path, List<Integer> astralCodePoints,
                               List<int[]> tabSettings) {
        rewrite(path, astralCodePoints, tabSettings, null);
    }

    /**
     * v15.11: 책갈피 주입을 지원하는 오버로드.
     *
     * @param sectionBookmarks 섹션 0 의 paragraph 인덱스 별 책갈피 이름 리스트.
     *        null 이거나 비어있으면 책갈피 주입을 생략. hwpxlib 1.0.9 에
     *        BookmarkWriter 가 없어 hwp2hwpx 가 만든 Bookmark 객체가 XML 로
     *        직렬화되지 않으므로, 우리가 직접 &lt;hp:bookmark&gt; 를 주입한다.
     */
    public static void rewrite(String path, List<Integer> astralCodePoints,
                               List<int[]> tabSettings,
                               List<List<String>> sectionBookmarks) {
        rewrite(path, astralCodePoints, tabSettings, sectionBookmarks, false);
    }

    /**
     * v16t51 S6-B: sourceIsOdt 시그널 받는 overload — ODT 입력 경로 한정으로 footer 안
     * autoNum numType="PAGE" 2건 중 두번째를 numType="TOTAL_PAGE" 로 후패치한다.
     * 외부 hwp2hwpx-1.0.0 가 ControlAutoNumber numType=6 (TOTAL_PAGE) 을 무조건 "PAGE" 로
     * 매핑 손실하는 잠복 결함(orig.hwp→hpx 직접 변환도 동일 증상) 우회. MD/HWP 입력 경로
     * (sourceIsOdt=false) 는 종전 동작 그대로 — byte 출력 불변.
     */
    public static void rewrite(String path, List<Integer> astralCodePoints,
                               List<int[]> tabSettings,
                               List<List<String>> sectionBookmarks,
                               boolean sourceIsOdt) {
        try {
            rewriteUnchecked(path, astralCodePoints, tabSettings, sectionBookmarks, sourceIsOdt);
        } catch (Throwable t) {
            System.err.println("[HwpxXmlRewriter] Skipping (" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + ")");
        }
    }

    /** ZIP 엔트리 최대 개수. */
    private static final int MAX_ENTRIES = 10_000;
    /** 단일 엔트리 최대 크기 (section0.xml 이 140MB+ 일 수 있음 → 512MB). */
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;
    /** 모든 엔트리 누적 최대 크기. */
    private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

    private static void rewriteUnchecked(String path, List<Integer> astralCodePoints,
                                         List<int[]> tabSettings,
                                         List<List<String>> sectionBookmarks) throws IOException {
        rewriteUnchecked(path, astralCodePoints, tabSettings, sectionBookmarks, false);
    }

    private static void rewriteUnchecked(String path, List<Integer> astralCodePoints,
                                         List<int[]> tabSettings,
                                         List<List<String>> sectionBookmarks,
                                         boolean sourceIsOdt) throws IOException {
        Path src = Paths.get(path);
        // 모든 엔트리를 메모리로 읽습니다. 엔트리 개수·단일/합계 크기 상한을 적용해
        // 악성/손상 ZIP 에 의한 OOM(zip bomb) 을 방지합니다.
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        long totalBytes = 0L;
        int entryCount = 0;
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(src))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRIES) {
                    throw new IOException("HWPX entry count exceeds limit (" + MAX_ENTRIES + ")");
                }
                validateEntryName(e.getName());
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int n;
                long entryBytes = 0;
                while ((n = zin.read(buf)) > 0) {
                    entryBytes += n;
                    if (entryBytes > MAX_ENTRY_BYTES) {
                        throw new IOException("HWPX entry too large: " + e.getName());
                    }
                    if (totalBytes + entryBytes > MAX_TOTAL_BYTES) {
                        throw new IOException("HWPX total size exceeds limit ("
                                + MAX_TOTAL_BYTES + " bytes)");
                    }
                    out.write(buf, 0, n);
                }
                totalBytes += entryBytes;
                entries.put(e.getName(), out.toByteArray());
                zin.closeEntry();
            }
        }

        // section0.xml 재작성
        byte[] sec = entries.get("Contents/section0.xml");
        if (sec != null) {
            String xml = new String(sec, StandardCharsets.UTF_8);
            // 1) surrogate pair (astral) 문자 복구
            String step1 = recoverAstralChars(xml, astralCodePoints);
            // 2) 원래 탭 매개변수 복원
            String step2 = recoverTabSettings(step1, tabSettings);
            // 3) 구조적 재작성 적용 (tc name, 말미 빈 run)
            String rewritten = rewriteSection(step2);
            // [v14.97 task1] 한/글 호환성 보강 — A1: 빈 hp:p 에 hp:run 삽입,
            //   A2: 빈 hp:tr/ self-closing 제거. hwp2hwpx 가 emit 하는 패턴에 발생.
            String hangulSafe = ensureHangulSafeSection(rewritten);
            // v15.11: 책갈피 주입 — hwpxlib BookmarkWriter 부재 보강.
            String withBookmarks = injectBookmarks(hangulSafe, sectionBookmarks);
            // [v15.23 task1] TOC 인라인 TAB 의 type 보강.
            //   HWP binary ad[5] = 1 (HWP spec RIGHT) 으로 emit → hwp2hwpx 가 OWPML
            //   type="1" (TabItemType LEFT, 잘못된 의미) 로 전파. RIGHT 의미 유지 위해
            //   leader="3" type="1" 패턴을 type="2" 로 보강. 다른 leader 값의 type="1"
            //   은 정상 LEFT 탭이므로 변경 안 함.
            String fixedTabType = fixTocTabType(withBookmarks);
            // [v15.24 task1/3] MdToHwpRich 의 multi-seg LineSegItem 이 hwp2hwpx 를 통해
            //   HWPX 의 <hp:linesegarray><hp:lineseg .../>...</hp:linesegarray> 로
            //   propagate 되어야 함. linesegarray 제거하지 않고 그대로 유지.
            // v16t51 S6-B: ODT 입력 경로 한정 — hp:footer 안 autoNum numType="PAGE" 2건 중
            //   두번째를 numType="TOTAL_PAGE" 로 후패치 (외부 hwp2hwpx 의 numType=6 매핑 손실 우회).
            //   MD/HWP 경로(sourceIsOdt=false) 는 건너뜀.
            String s6Patched = sourceIsOdt ? fixOdtFooterTotalPage(fixedTabType) : fixedTabType;
            if (!s6Patched.equals(xml)) {
                entries.put("Contents/section0.xml", s6Patched.getBytes(StandardCharsets.UTF_8));
            }
            // v16t52 T7 ⓒ: 외부 hwp2hwpx 의 DASH leader → DOT 매핑 손실(잠복 결함 #5) 우회.
            //   ODT 경로 한정·header.xml 의 hh:tabPr 안 hh:tabItem leader="DOT" 중 빌더가 fillType=2(Dash)
            //   로 등록한 항목 복원. mapOdtLeaderToFillType 매핑 표에 따라 dashed=2/dotted=3 인데
            //   외부 hwp2hwpx 가 둘 다 DOT 로 출력 — section 측 hp:tab leader="2"(dashed) 시그널이
            //   inline 탭 byte 에 남아 있으면 그 tabPr 의 leader 도 DASH 로 강제 미러.
        }
        // header.xml 측 ODT 경로 후패치 (T7 ⓒ): section 측 hp:tab leader="2" 가 있으면 그 tabPr 의
        //   tabItem leader="DOT" → leader="DASH" 치환. header.xml 처리는 hwp(x) sec 분기 밖에 별도.
        byte[] hdrT7 = entries.get("Contents/header.xml");
        if (hdrT7 != null && sourceIsOdt) {
            // section0.xml 에 leader="2" 인 hp:tab 존재 여부 확인
            byte[] secBytes = entries.get("Contents/section0.xml");
            String secStr = secBytes != null ? new String(secBytes, StandardCharsets.UTF_8) : "";
            if (secStr.contains("<hp:tab ") && java.util.regex.Pattern
                    .compile("<hp:tab[^/]*leader=\"2\"").matcher(secStr).find()) {
                String hh = new String(hdrT7, StandardCharsets.UTF_8);
                String hhFixed = fixOdtTabLeaderDash(hh);
                if (!hhFixed.equals(hh)) {
                    entries.put("Contents/header.xml", hhFixed.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        // [v16t56 task2] ODT 경로 한정 — header.xml 의 hh:tabPr hp:case tabItem pos 를
        //   hp:default pos 로 복원(절반화 취소). RIGHT 탭스톱 위치 정상화 → 목차 점선 렌더.
        byte[] hdrTab = entries.get("Contents/header.xml");
        if (hdrTab != null && sourceIsOdt) {
            String h = new String(hdrTab, StandardCharsets.UTF_8);
            String hFixed = restoreOdtTabPrCasePos(h, sourceIsOdt);
            if (!hFixed.equals(h)) {
                entries.put("Contents/header.xml", hFixed.getBytes(StandardCharsets.UTF_8));
            }
        }
        // [v57 r3 task1] ODT 경로 한정 — header.xml 의 hp:paraPr hp:default margin
        //   intent/left/right 를 hp:case(HwpUnitChar) 값으로 복원(들여쓰기 절반화 취소).
        //   한/글이 문단 좌여백을 hp:default(2배) 로 렌더하는 증상 정정.
        byte[] hdrPara = entries.get("Contents/header.xml");
        if (hdrPara != null && sourceIsOdt) {
            String h = new String(hdrPara, StandardCharsets.UTF_8);
            String hFixed = restoreOdtParaPrCaseMargin(h, sourceIsOdt);
            if (!hFixed.equals(h)) {
                entries.put("Contents/header.xml", hFixed.getBytes(StandardCharsets.UTF_8));
            }
        }
        // [v14.97 task1] A3: header.xml 의 잘못된 charPrIDRef sentinel 정규화.
        byte[] hdr = entries.get("Contents/header.xml");
        if (hdr != null) {
            String h = new String(hdr, StandardCharsets.UTF_8);
            String hFixed = sanitizeHeaderRefs(h);
            if (!hFixed.equals(h)) {
                entries.put("Contents/header.xml", hFixed.getBytes(StandardCharsets.UTF_8));
            }
        }

        // 누락된 경우 container.rdf 추가
        if (!entries.containsKey("META-INF/container.rdf")) {
            entries.put("META-INF/container.rdf", CONTAINER_RDF.getBytes(StandardCharsets.UTF_8));
        }

        // [v14.95 task1] 한/글이 HWPX 를 열 때 container.xml 의 rootfile 이 가리키는 파일이
        //   실제로 ZIP 안에 있어야 한다. hwpxlib HWPXWriter 가 출력한 container.xml 은
        //   "Preview/PrvText.txt" 를 rootfile 로 명시하지만 같은 파일을 zip 에 넣지 않아
        //   한/글이 "파일을 읽거나 저장하는데 오류가 있습니다" 팝업으로 거부함. 또한
        //   "META-INF/container.rdf" rootfile 항목이 누락되어 있어 보강.
        //   1) Preview/PrvText.txt 가 entries 에 없으면 빈 placeholder 로 추가.
        //   2) container.xml 의 rootfiles 에 META-INF/container.rdf 항목이 없으면 보강.
        if (!entries.containsKey("Preview/PrvText.txt")) {
            // 한/글이 빈 파일을 거부할 가능성에 대비해 1 byte placeholder.
            entries.put("Preview/PrvText.txt", new byte[]{0x20});
        }

        // [v15.28 task1/2] content.hpf 의 CreatedDate / ModifiedDate 값을 ISO 8601
        //  형식으로 정규화 (한/글 손상 경고 회피).
        //   hwpxlib HWPXWriter 가 Java 의 Date.toString() ("Thu Apr 23 11:45:48 KST 2026")
        //   형식으로 emit → 한/글이 "잘못된 메타데이터" 로 인식 가능성. 원본 HWPX 는
        //   "2024-04-30T06:37:53Z" 형식 (ISO 8601). 본 fix 는 java Date 형식이면
        //   ISO 8601 으로 변환.
        byte[] hpfBytes = entries.get("Contents/content.hpf");
        if (hpfBytes != null) {
            String hpf = new String(hpfBytes, StandardCharsets.UTF_8);
            String fixedHpf = normalizeContentHpfDates(hpf);
            if (!fixedHpf.equals(hpf)) {
                entries.put("Contents/content.hpf", fixedHpf.getBytes(StandardCharsets.UTF_8));
            }
        }
        // [v15.28] Preview/PrvImage.png 누락 시 1x1 PNG placeholder 추가.
        //   원본 HWPX 에는 PrvImage.png 가 항상 존재. 한/글이 일부 버전에서 미존재
        //   시 손상 경고 트리거 가능성. 1x1 투명 PNG 로 안전 placeholder 추가.
        if (!entries.containsKey("Preview/PrvImage.png")) {
            entries.put("Preview/PrvImage.png", PRVIMAGE_PNG_1X1);
        }
        byte[] containerXml = entries.get("META-INF/container.xml");
        if (containerXml != null) {
            String c = new String(containerXml, StandardCharsets.UTF_8);
            if (!c.contains("META-INF/container.rdf") && c.contains("</ocf:rootfiles>")) {
                String patched = c.replace("</ocf:rootfiles>",
                        "<ocf:rootfile full-path=\"META-INF/container.rdf\""
                                + " media-type=\"application/rdf+xml\"/></ocf:rootfiles>");
                entries.put("META-INF/container.xml", patched.getBytes(StandardCharsets.UTF_8));
            }
        }

        // 임시 파일로 다시 작성한 후 원본을 원자적으로 대체합니다.
        // 예외 경로에서 tmp 가 남지 않도록 try/finally 로 정리.
        Path tmp = src.resolveSibling(src.getFileName() + ".rewrite.tmp");
        boolean moved = false;
        try {
            try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(tmp.toFile()))) {
                // [v14.96 task1] HWPX 스펙: mimetype 은 ZIP 의 첫 entry 이어야 하며,
                //   STORED (uncompressed) 방식으로 저장되어야 한다 (OpenDocument/EPUB 표준).
                //   hwpxlib HWPXWriter 가 mimetype 을 DEFLATE 으로 저장 → 한/글이 이를 감지하여
                //   "파일을 읽거나 저장하는데 오류가 있습니다" 팝업으로 거부. 여기서 재작성 시
                //   mimetype 을 명시적으로 STORED 로 작성한다.
                byte[] mimetype = entries.get("mimetype");
                if (mimetype != null) {
                    ZipEntry mz = new ZipEntry("mimetype");
                    mz.setMethod(ZipEntry.STORED);
                    mz.setSize(mimetype.length);
                    mz.setCompressedSize(mimetype.length);
                    CRC32 crc = new CRC32();
                    crc.update(mimetype);
                    mz.setCrc(crc.getValue());
                    zout.putNextEntry(mz);
                    zout.write(mimetype);
                    zout.closeEntry();
                }
                for (Map.Entry<String, byte[]> me : entries.entrySet()) {
                    if ("mimetype".equals(me.getKey())) continue;
                    ZipEntry ze = new ZipEntry(me.getKey());
                    zout.putNextEntry(ze);
                    zout.write(me.getValue());
                    zout.closeEntry();
                }
            }
            Files.move(tmp, src, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * ZIP 엔트리 이름에 traversal / 절대경로 / NUL byte / 드라이브 레터가 있는지 검사.
     * 메모리 상 map 에만 저장하는 현 구조에서도 향후 디스크 추출 확장에 대한
     * 이중 방어로 엄격히 거부한다.
     */
    private static void validateEntryName(String name) throws IOException {
        if (name == null) throw new IOException("Null ZIP entry name");
        if (name.indexOf('\0') >= 0) throw new IOException("Malicious ZIP entry name (NUL byte)");
        String norm = name.replace('\\', '/');
        if (norm.startsWith("/")
                || norm.contains("../")
                || norm.equals("..")
                || norm.endsWith("/..")
                || (norm.length() >= 2 && norm.charAt(1) == ':')) {
            throw new IOException("Malicious ZIP entry path: " + name);
        }
    }

    /**
     * 말미 빈 run과 표 셀에 {@code name=""}을 추가합니다.
     * 순수 문자열 처리 — 파일의 나머지 부분을 바이트 단위로 정확하게 유지합니다.
     */
    /**
     * hwplib의 {@code HWPCharNormal.getCh()}가 U+FFFD U+FFFD로 훼손한
     * surrogate pair (astral-plane) 문자를 복구합니다.
     *
     * <p>근본 원인: hwplib는 각 HWP UINT16 코드 유닛을 별도의 2-byte UTF-16LE
     * String으로 디코딩합니다. HWP 소스에 surrogate pair (예: U+F02B1과 같은
     * Private Use Area 코드 포인트)가 포함되어 있을 때, 쌍의 각 절반은
     * 잘못 형성된 독립 String이 되고 Java는 이를 U+FFFD (유니코드 대체 문자)로
     * 교체합니다.
     *
     * <p>전략: 호출자가 소스 HWP를 순차적으로 스캔하여 발견한 모든 astral 코드
     * 포인트 (U+10000..U+10FFFF)를 수집했습니다. 여기서는 {@code <hp:t>} 내용의
     * 모든 {@code U+FFFDU+FFFD} 출현을 해당 목록의 다음 astral 코드 포인트로
     * 순차적으로 교체하여 올바른 surrogate pair를 생성합니다.
     *
     * @param xml                 section0.xml 내용
     * @param astralCodePoints    소스 순서의 코드 포인트, 건너뛰려면 null
     */
    static String recoverAstralChars(String xml, List<Integer> astralCodePoints) {
        if (astralCodePoints == null || astralCodePoints.isEmpty()) return xml;
        // 연속된 두 개의 U+FFFD 문자가 훼손된 surrogate pair 서명을 형성합니다.
        StringBuilder out = new StringBuilder(xml.length());
        int i = 0, cpIdx = 0, n = xml.length();
        while (i < n) {
            char c = xml.charAt(i);
            if (c == 0xFFFD && i + 1 < n && xml.charAt(i + 1) == 0xFFFD
                    && cpIdx < astralCodePoints.size()) {
                int cp = astralCodePoints.get(cpIdx++);
                out.appendCodePoint(cp);
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * HWP leader byte → HWPX {@code <hp:tab leader="...">} enum 이름.
     * 값은 HWP 5.0 스펙 §tab 데이터 및 hwpxlib LineType2 enum에서 가져옴.
     */
    private static String leaderName(int leader) {
        switch (leader) {
            case 0: return "NONE";
            case 1: return "SOLID";
            case 2: return "DASH";
            case 3: return "DOT";
            case 4: return "DASH_DOT";
            case 5: return "DASH_DOT_DOT";
            case 6: return "LONG_DASH";
            case 7: return "CIRCLE";
            case 8: return "DOUBLE_SLIM";
            case 9: return "SLIM_THICK";
            case 10: return "THICK_SLIM";
            case 11: return "SLIM_THICK_SLIM";
            default: return "NONE";
        }
    }

    /**
     * HWP tab type → HWPX {@code <hp:tab type="...">} enum 이름.
     * 0=LEFT, 1=RIGHT, 2=CENTER, 3=DECIMAL.
     */
    private static String tabTypeName(int type) {
        switch (type) {
            case 0: return "LEFT";
            case 1: return "RIGHT";
            case 2: return "CENTER";
            case 3: return "DECIMAL";
            default: return "LEFT";
        }
    }

    /**
     * hwp2hwpx-1.0.0의 {@code ForChars.addTab()}이 덮어쓴 탭별
     * width/leader/type 매개변수를 복원합니다. XML의 모든
     * {@code <hp:tab .../>}를 {@code tabSettings}의 다음 요소로부터 구성된
     * 것으로 순차적으로 교체합니다.
     *
     * <p>HWPX 숫자 enum 매핑 (hwpxlib 직렬화):
     * <ul>
     *   <li>leader: LineType2 — 0 NONE, 1 SOLID, 2 DASH, 3 DOT, 4 DASH_DOT,
     *       5 DASH_DOT_DOT, 6 LONG_DASH, 등.</li>
     *   <li>type: TabItemType — 0 LEFT, 1 RIGHT, 2 CENTER, 3 DECIMAL.</li>
     * </ul>
     * hwpxlib writer는 enum의 {@code str()}이 숫자를 반환할 때 숫자 속성을
     * 생성합니다 (한글이 생성하는 {@code leader="3" type="2"} 형식) —
     * LineType2 및 TabItemType에 대해 그렇게 합니다. 따라서 숫자를 직접
     * 생성합니다.
     */
    static String recoverTabSettings(String xml, List<int[]> tabSettings) {
        if (tabSettings == null || tabSettings.isEmpty()) return xml;
        StringBuilder out = new StringBuilder(xml.length());
        final String TAB_PREFIX = "<hp:tab ";
        int i = 0, idx = 0, n = xml.length();
        while (i < n) {
            int p = xml.indexOf(TAB_PREFIX, i);
            if (p < 0) { out.append(xml, i, n); break; }
            out.append(xml, i, p);
            int end = xml.indexOf("/>", p);
            if (end < 0) { out.append(xml, p, n); break; }
            if (idx < tabSettings.size()) {
                int[] t = tabSettings.get(idx++);
                out.append("<hp:tab width=\"").append(t[0])
                   .append("\" leader=\"").append(t[1])
                   .append("\" type=\"").append(t[2])
                   .append("\"/>");
            } else {
                // 설정이 소진됨 — 기존 태그를 변경 없이 복사
                out.append(xml, p, end + 2);
            }
            i = end + 2;
        }
        return out.toString();
    }

    /**
     * v16t51 S6-B: ODT 입력 경로의 hp:footer 안 hp:autoNum numType="PAGE" 두번째 발생을
     * numType="TOTAL_PAGE" 로 치환. 외부 hwp2hwpx 가 ControlAutoNumber numType=6 을 항상
     * "PAGE" 로 매핑 손실하는 잠복 결함 우회. footer 없거나 autoNum 1건 이하면 변경 없음.
     */
    static String fixOdtFooterTotalPage(String xml) {
        java.util.regex.Pattern footerPat = java.util.regex.Pattern.compile("(<hp:footer\\b[^>]*>)(.*?)(</hp:footer>)", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = footerPat.matcher(xml);
        StringBuffer sb = new StringBuffer(xml.length());
        while (m.find()) {
            String open = m.group(1);
            String inner = m.group(2);
            String close = m.group(3);
            // autoNum 의 두번째 발생만 numType 치환
            java.util.regex.Matcher autoMatch = java.util.regex.Pattern
                    .compile("(<hp:autoNum\\b[^>]*\\bnumType=\")PAGE(\"[^/]*/>)").matcher(inner);
            StringBuffer ib = new StringBuffer(inner.length());
            int hit = 0;
            while (autoMatch.find()) {
                hit++;
                if (hit == 2) {
                    autoMatch.appendReplacement(ib, java.util.regex.Matcher.quoteReplacement(autoMatch.group(1) + "TOTAL_PAGE" + autoMatch.group(2)));
                } else {
                    autoMatch.appendReplacement(ib, java.util.regex.Matcher.quoteReplacement(autoMatch.group(0)));
                }
            }
            autoMatch.appendTail(ib);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(open + ib.toString() + close));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * v16t52 T7 ⓒ: 외부 hwp2hwpx 의 DASH leader → DOT 매핑 손실(잠복 결함 #5) 우회.
     * ODT 경로 한정·header.xml hh:tabPr 안 hh:tabItem leader="DOT" 중 같은 tabPr 안에 짝(pair)
     * 가 있고 section 의 hp:tab leader="2"(Dash) 가 짝이 되는 경우 DASH 로 강제 미러.
     * 단순 안: 모든 leader="DOT" → "DASH" 치환은 정상 DOT 도 잘못 변경. 따라서 인접 tabItem 짝에서
     * 빌더가 fillType=2(Dash) 로 만든 패턴만 식별 필요 — 1차로는 'leader="DOT"' 중 짝이 dashed
     * 인 것 자체 식별이 어려우니 v52 단계 1차는 **모든 DOT 변환 안 함**(보수). v52+ 본격 단계에서
     * paragraph paraPrIDRef → tabPrIDRef → 그 tabPr 의 inline tab leader 매핑으로 정밀 식별.
     * 본 함수는 1차 placeholder (변경 없음)·후속 확장 hook.
     */
    static String fixOdtTabLeaderDash(String xml) {
        return xml; // v16t52 1차 — 정밀 위치 식별 후속(P4 스펙 확정 후 본격 구현).
    }

    static String rewriteSection(String xml) {
        // 단일 패스 스캔 — 큰 HWPX 파일 (100MB+)에 필수. XML을 처음부터
        // 끝까지 순회하면서 subList 중첩 깊이, 문단-텍스트-존재 플래그,
        // 마지막으로 본 `</hp:run>` 위치를 추적하고 한 번의 패스로
        // 재작성을 생성합니다.
        //
        // 적용되는 재작성:
        //   · subList 내의 모든 `<hp:linesegarray>` 앞에
        //     `<hp:run charPrIDRef="0"/>` 삽입 (이전 요소가 `</hp:run>`이고,
        //     문단에 텍스트가 포함되어 있으며, 빈 run이 앞에 오지
        //     않는 경우).
        //   · `name=` 속성이 없는 모든 `<hp:tc ...>` 여는 태그에
        //     `name=""` 주입.
        final String SUB_OPEN_S  = "<hp:subList";
        final String SUB_CLOSE_S = "</hp:subList>";
        final String LSA        = "<hp:linesegarray>";
        final String END_RUN    = "</hp:run>";
        final String EMPTY_RUN  = "<hp:run charPrIDRef=\"0\"/>";
        final String P_OPEN     = "<hp:p ";
        final String T_OPEN     = "<hp:t>";
        final String TC_OPEN    = "<hp:tc ";

        StringBuilder out = new StringBuilder(xml.length() + 64 * 1024);
        int subDepth = 0;
        int lastPOpenOut = -1;   // 현재 문단이 시작된 OUTPUT에서의 offset
        int lastEndRunOut = -1;  // 마지막 </hp:run>이 끝난 OUTPUT에서의 offset
        boolean paraHasText = false;

        int i = 0;
        int n = xml.length();
        while (i < n) {
            char c = xml.charAt(i);
            if (c != '<') {
                out.append(c);
                i++;
                continue;
            }
            // 태그 시작.
            if (xml.regionMatches(i, SUB_CLOSE_S, 0, SUB_CLOSE_S.length())) {
                out.append(SUB_CLOSE_S);
                i += SUB_CLOSE_S.length();
                if (subDepth > 0) subDepth--;
            } else if (xml.regionMatches(i, SUB_OPEN_S, 0, SUB_OPEN_S.length())) {
                // <hp:subList ...>는 /> 또는 >로 닫힐 수 있음. 그대로 복사.
                int end = xml.indexOf('>', i);
                if (end < 0) { out.append(xml, i, n); break; }
                out.append(xml, i, end + 1);
                // self-closing <.../>은 깊이를 증가시키지 않음; 그렇지 않으면 증가시킴.
                boolean selfClosing = xml.charAt(end - 1) == '/';
                if (!selfClosing) subDepth++;
                i = end + 1;
            } else if (xml.regionMatches(i, P_OPEN, 0, P_OPEN.length())) {
                int end = xml.indexOf('>', i);
                if (end < 0) { out.append(xml, i, n); break; }
                out.append(xml, i, end + 1);
                lastPOpenOut = out.length();
                lastEndRunOut = -1;
                paraHasText = false;
                i = end + 1;
            } else if (xml.regionMatches(i, END_RUN, 0, END_RUN.length())) {
                out.append(END_RUN);
                lastEndRunOut = out.length();
                i += END_RUN.length();
            } else if (xml.regionMatches(i, T_OPEN, 0, T_OPEN.length())) {
                out.append(T_OPEN);
                paraHasText = true;
                i += T_OPEN.length();
            } else if (xml.regionMatches(i, LSA, 0, LSA.length())) {
                // 조건이 충족되면 말미 빈 run 삽입
                if (subDepth > 0 && paraHasText && lastEndRunOut == out.length()) {
                    // 중복 방지: 출력에서 앞에 있는 내용 확인
                    int lo = out.length() - EMPTY_RUN.length();
                    boolean dup = lo >= 0 && out.indexOf(EMPTY_RUN, lo) == lo;
                    if (!dup) out.append(EMPTY_RUN);
                }
                out.append(LSA);
                i += LSA.length();
            } else if (xml.regionMatches(i, TC_OPEN, 0, TC_OPEN.length())) {
                int end = xml.indexOf('>', i);
                if (end < 0) { out.append(xml, i, n); break; }
                String tag = xml.substring(i, end + 1);
                if (!tag.contains(" name=\"")) {
                    out.append(TC_OPEN).append("name=\"\" ").append(tag.substring(TC_OPEN.length()));
                } else {
                    out.append(tag);
                }
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * 휴리스틱: offset {@code pos}를 감싸는 문단에 비어 있지 않은
     * {@code <hp:t>} 요소가 하나 이상 포함되어 있습니까? 체크 범위를
     * 정하기 위해 가장 가까운 {@code <hp:p }까지 거꾸로 검색합니다.
     */
    private static boolean paragraphHasText(String xml, int pos) {
        int pStart = xml.lastIndexOf("<hp:p ", pos);
        if (pStart < 0) return false;
        int scan = xml.indexOf("<hp:t>", pStart);
        // 이 문단을 감싸는 <hp:run/> 체인 내에 있어야 합니다.
        return scan >= 0 && scan < pos;
    }

    /**
     * {@code pos}의 문단이 {@code <hp:subList>} 안에 있습니까?
     * subList는 표 셀 내용과 머리말/꼬리말 본문을 감싸며 — 한글이
     * 말미 빈 run을 추가하는 컨텍스트입니다.
     * 문단 시작부터 거꾸로 감싸는 subList 여는/닫는 태그를 세어
     * 감지합니다.
     */
    private static boolean isInsideSubList(String xml, int pos) {
        // pos 이전의 <hp:subList> 및 </hp:subList> 태그를 세기; open > close이면 내부에 있음.
        int depth = 0;
        int i = 0;
        while (i < pos) {
            int openPos = xml.indexOf("<hp:subList", i);
            int closePos = xml.indexOf("</hp:subList>", i);
            if (openPos < 0 && closePos < 0) break;
            if (openPos < 0 || (closePos >= 0 && closePos < openPos)) {
                if (closePos >= pos) break;
                depth--;
                i = closePos + 13;
            } else {
                if (openPos >= pos) break;
                depth++;
                i = openPos + 11;
            }
        }
        return depth > 0;
    }

    /**
     * [v14.97/v14.98 task1] 한/글 호환 보강 — section0.xml 후처리.
     * <ul>
     *  <li>A1: hp:run 자식이 없는 hp:p 에 빈 hp:run 삽입. 한/글 정상 HWPX 의 self-closing
     *      형식 {@code <hp:run charPrIDRef="0"/>} (hp:t 없음) 사용.
     *      hwp2hwpx 가 빈 paragraph (e.g. blank line) 를 hp:run 없이 emit 하면
     *      한/글이 HWPX 스키마 위반으로 거부.
     *      대상 패턴: {@code <hp:p ...><hp:linesegarray ...} (open hp:p 직후 hp:run 가 아님).</li>
     *  <li>A2: 빈 hp:tr 자체-닫힘 ({@code <hp:tr/>}) 또는 빈 시작-닫힘
     *      ({@code <hp:tr></hp:tr>}) 제거 + 부모 hp:tbl 의 rowCnt 감소 + 그 행을 가로지르는
     *      cell 의 rowSpan 감소 + 그 행 아래 cell 들의 rowAddr 감소.
     *      v14.97 까지 단순 제거 → rowCnt 와 hp:tr 수 mismatch → "파일이 손상되었습니다".</li>
     * </ul>
     */
    static String ensureHangulSafeSection(String xml) {
        // A1: <hp:p ...><hp:linesegarray ... 패턴 → <hp:p ...><hp:run charPrIDRef="0"/><hp:linesegarray ...
        StringBuilder out = new StringBuilder(xml.length() + 4096);
        int n = xml.length();
        int i = 0;
        final String P_OPEN = "<hp:p ";
        final String LSA = "<hp:linesegarray";
        final String EMPTY_RUN = "<hp:run charPrIDRef=\"0\"/>";
        while (i < n) {
            int pStart = xml.indexOf(P_OPEN, i);
            if (pStart < 0) {
                out.append(xml, i, n);
                break;
            }
            int gtPos = xml.indexOf('>', pStart);
            if (gtPos < 0) {
                out.append(xml, i, n);
                break;
            }
            out.append(xml, i, gtPos + 1);
            int after = gtPos + 1;
            int afterTrim = after;
            while (afterTrim < n && Character.isWhitespace(xml.charAt(afterTrim))) afterTrim++;
            if (afterTrim < n && xml.startsWith(LSA, afterTrim)) {
                out.append(xml, after, afterTrim);
                out.append(EMPTY_RUN);
                i = afterTrim;
            } else {
                i = gtPos + 1;
            }
        }
        String afterA1 = out.toString();
        // A2: 표 단위로 빈 hp:tr 제거 + 메타데이터 (rowCnt / rowSpan / rowAddr) 일관성 보정.
        return fixEmptyTableRows(afterA1);
    }

    /** [v14.98 task1] section0.xml 의 각 hp:tbl 안에서 빈 hp:tr 제거 시
     *  rowCnt / rowSpan / rowAddr 도 일관되게 업데이트. 한/글이 mismatch 를 감지해
     *  "파일이 손상되었습니다" 라고 거부하던 회귀 수정. */
    static String fixEmptyTableRows(String xml) {
        StringBuilder out = new StringBuilder(xml.length());
        int n = xml.length();
        int j = 0;
        while (j < n) {
            int tblStart = xml.indexOf("<hp:tbl ", j);
            if (tblStart < 0) {
                out.append(xml, j, n);
                break;
            }
            out.append(xml, j, tblStart);
            int tblEnd = findTblClose(xml, tblStart);
            if (tblEnd < 0) {
                out.append(xml, tblStart, n);
                break;
            }
            String tblBlock = xml.substring(tblStart, tblEnd);
            out.append(fixOneTable(tblBlock));
            j = tblEnd;
        }
        return out.toString();
    }

    /** Returns end index (exclusive) of the matching </hp:tbl> for hp:tbl starting at tblStart. */
    private static int findTblClose(String s, int tblStart) {
        int depth = 0;
        int i = tblStart;
        int len = s.length();
        while (i < len) {
            int o = s.indexOf("<hp:tbl ", i);
            int c = s.indexOf("</hp:tbl>", i);
            if (c < 0) return -1;
            if (o >= 0 && o < c) {
                depth++;
                i = o + 8;
            } else {
                depth--;
                if (depth == 0) return c + "</hp:tbl>".length();
                i = c + "</hp:tbl>".length();
            }
        }
        return -1;
    }

    /** Fix one hp:tbl block (entire {@code <hp:tbl ...>...</hp:tbl>}). */
    private static String fixOneTable(String tbl) {
        // 표 자체의 hp:tr 만 (nested 표의 hp:tr 제외) 처리.
        // 빈 hp:tr 의 row index 수집.
        java.util.List<int[]> trRanges = collectTopLevelHpTr(tbl);
        if (trRanges.isEmpty()) return tbl;
        java.util.List<Integer> emptyRowIndices = new java.util.ArrayList<>();
        for (int idx = 0; idx < trRanges.size(); idx++) {
            int[] r = trRanges.get(idx);
            String trBody = tbl.substring(r[0], r[1]);
            if (trBody.matches("(?s)<hp:tr\\s*/>")
                    || trBody.matches("(?s)<hp:tr\\s*>\\s*</hp:tr>")) {
                emptyRowIndices.add(idx);
            }
        }
        if (emptyRowIndices.isEmpty()) return tbl;

        // rowCnt 감소
        java.util.regex.Matcher mRC = java.util.regex.Pattern.compile(
                "(<hp:tbl[^>]*\\browCnt=\")(\\d+)(\")").matcher(tbl);
        StringBuilder phase1 = new StringBuilder(tbl.length());
        if (mRC.find() && mRC.start() < trRanges.get(0)[0]) {
            int origRowCnt = Integer.parseInt(mRC.group(2));
            int newRowCnt = Math.max(1, origRowCnt - emptyRowIndices.size());
            phase1.append(tbl, 0, mRC.start());
            phase1.append(mRC.group(1)).append(newRowCnt).append(mRC.group(3));
            phase1.append(tbl, mRC.end(), tbl.length());
        } else {
            phase1.append(tbl);
        }

        // cellAddr+cellSpan 쌍 보정. 각 hp:tc 안의 cellAddr 의 rowAddr 와 cellSpan 의 rowSpan 을
        //   비교: rowAddr > emptyIdx 면 rowAddr--; emptyIdx 가 [rowAddr+1, rowAddr+rowSpan-1] 에
        //   들어가면 rowSpan--.
        // 단순 패턴: <hp:cellAddr ... rowAddr="X"/>...<hp:cellSpan ... rowSpan="Y"/>
        String s = phase1.toString();
        StringBuilder phase2 = new StringBuilder(s.length());
        java.util.regex.Pattern pairPat = java.util.regex.Pattern.compile(
                "(<hp:cellAddr\\b[^/]*\\browAddr=\")(\\d+)(\"[^/]*/>\\s*<hp:cellSpan\\b[^/]*\\browSpan=\")(\\d+)(\"[^/]*/>)");
        java.util.regex.Matcher mp = pairPat.matcher(s);
        int last = 0;
        while (mp.find()) {
            int rowAddr = Integer.parseInt(mp.group(2));
            int rowSpan = Integer.parseInt(mp.group(4));
            int spanLast = rowAddr + rowSpan - 1;
            int spanDecr = 0;
            int addrDecr = 0;
            for (int eIdx : emptyRowIndices) {
                if (eIdx > rowAddr && eIdx <= spanLast) spanDecr++;
                if (eIdx < rowAddr) addrDecr++;
            }
            int newRowAddr = rowAddr - addrDecr;
            int newRowSpan = Math.max(1, rowSpan - spanDecr);
            phase2.append(s, last, mp.start());
            phase2.append(mp.group(1)).append(newRowAddr)
                  .append(mp.group(3)).append(newRowSpan).append(mp.group(5));
            last = mp.end();
        }
        phase2.append(s, last, s.length());

        // 마지막: 빈 hp:tr 제거
        String phase3 = phase2.toString()
                .replaceAll("<hp:tr\\s*/>", "")
                .replaceAll("<hp:tr\\s*>\\s*</hp:tr>", "");
        return phase3;
    }

    /** Collect top-level hp:tr ranges within a hp:tbl block (skip nested hp:tbl).
     *  Input {@code tbl} starts with {@code <hp:tbl ...>}. We skip past the opening tag
     *  and start scanning the body with depth=1.
     *  Returns list of [start,end) indices into the original {@code tbl} string. */
    private static java.util.List<int[]> collectTopLevelHpTr(String tbl) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        int n = tbl.length();
        // Skip past the opening <hp:tbl ...> tag so we start inside the body.
        int i = tbl.indexOf('>');
        if (i < 0) return out;
        i++; // past the '>'
        int tblDepth = 1; // inside outer hp:tbl body
        while (i < n) {
            int nextOpenTbl = tbl.indexOf("<hp:tbl ", i);
            int nextCloseTbl = tbl.indexOf("</hp:tbl>", i);
            int nextTr = tbl.indexOf("<hp:tr", i);
            int next = -1;
            String nextKind = null;
            if (nextOpenTbl >= 0 && (next < 0 || nextOpenTbl < next)) { next = nextOpenTbl; nextKind = "openTbl"; }
            if (nextCloseTbl >= 0 && (next < 0 || nextCloseTbl < next)) { next = nextCloseTbl; nextKind = "closeTbl"; }
            if (nextTr >= 0 && (next < 0 || nextTr < next)) { next = nextTr; nextKind = "tr"; }
            if (next < 0) break;
            if ("openTbl".equals(nextKind)) {
                tblDepth++;
                i = next + 8;
            } else if ("closeTbl".equals(nextKind)) {
                tblDepth--;
                i = next + "</hp:tbl>".length();
                if (tblDepth == 0) break;
            } else { // tr
                if (tblDepth == 1) {
                    // find tr range (self-closing or paired)
                    int gt = tbl.indexOf('>', next);
                    if (gt < 0) { i = next + 6; continue; }
                    if (tbl.charAt(gt - 1) == '/') {
                        // self-closing
                        out.add(new int[]{next, gt + 1});
                        i = gt + 1;
                    } else {
                        int closeTr = tbl.indexOf("</hp:tr>", gt);
                        if (closeTr < 0) { i = gt + 1; continue; }
                        out.add(new int[]{next, closeTr + "</hp:tr>".length()});
                        i = closeTr + "</hp:tr>".length();
                    }
                } else {
                    i = next + 6;
                }
            }
        }
        return out;
    }

    /**
     * [v14.97 task1] 한/글 호환 보강 — header.xml 후처리.
     * A3: hh:numbering 의 paraHead charPrIDRef 가 4294967295 (0xFFFFFFFF, hwplib 의
     *     "no ref" sentinel -1 의 unsigned 변환 결과) 인 경우 0 으로 정규화.
     *     header.xml 의 hh:charProperties itemCnt 범위를 벗어나 한/글이 거부.
     *
     * [v15.21 task1/2] HwpUnitChar 네임스페이스 분기의 &lt;hh:tabItem&gt; 에
     *   {@code unit="HWPUNIT"} 속성이 누락된 경우 보강. hwp2hwpx 가 RIGHT/DOT
     *   leader 의 TOC tabItem 을 HwpUnitChar 분기로 emit 할 때 unit 속성을 빠뜨려
     *   한/글 뷰어가 &lt;hp:default&gt; 분기의 pos 값 (2× 의 hwpunit) 으로 fallback
     *   해석 → 페이지 번호가 페이지 폭의 2배 위치에 right-align 되어 다중 자릿수
     *   페이지 번호가 깨지거나 페이지 밖으로 밀리는 회귀의 원인.
     */
    static String sanitizeHeaderRefs(String xml) {
        String s = xml.replace("charPrIDRef=\"4294967295\"", "charPrIDRef=\"0\"");
        s = ensureTabItemUnit(s);
        return s;
    }

    /**
     * [v15.31 task1/2 fix] type="1" → type="2" 변환 비활성화 — 이 변환이 오히려 페이지
     *  번호 디지트 깨짐 ("11"→"1 1", "17"→"71", "22"→"2 2", "28"→"8 2") 회귀의
     *  원인이었음. hwpxlib TabItemType enum 의 ordinal 은 LEFT=0, RIGHT=1, CENTER=2,
     *  DECIMAL=3 이므로 :
     *   - OWPML type="1" = RIGHT (hwp2hwpx 가 binary ad[5]=1 을 정확히 매핑한 결과)
     *   - 우리가 "1" → "2" 로 다시 쓰면서 RIGHT 를 CENTER 로 잘못 변환
     *   - CENTER tab + 다중 자릿수 페이지 번호 → 디지트가 tab stop 좌우로 분리 렌더
     *  본 fix 는 변환을 noop 으로 만들어 hwp2hwpx 의 원래 매핑 (type="1" = RIGHT) 을
     *  그대로 보존한다.
     */
    static String fixTocTabType(String xml) {
        // v15.31 부터 noop. 이전 동작이 디지트 깨짐을 유발했음.
        return xml;
    }

    /**
     * [v15.24] 모든 &lt;hp:linesegarray&gt;…&lt;/hp:linesegarray&gt; 를 제거.
     *
     * <p>우리 converter 는 모든 paragraph 에 단일 &lt;hp:lineseg vertpos="0"
     * horzsize="42520"/&gt; 만 emit 하므로 :
     * <ul>
     *   <li>다중 행 paragraph (텍스트 폭 &gt; horzsize) 의 wrapped 줄들이 같은 Y
     *       위치에 그려져 시각적 겹침 발생.</li>
     *   <li>RIGHT-tab 의 TOC 다중 자릿수 페이지번호 가 인접 paragraph 의 leader
     *       와 겹쳐 "1·1", "71" 등 깨짐 표시.</li>
     * </ul>
     *
     * <p>원본 hand-made 는 paragraph 별 progressive vertpos + multi-seg 로 layout
     * cache 를 가지나 우리는 정확한 line wrap 계산이 어려움. linesegarray 전체를
     * 제거하면 한/글이 layout 을 처음부터 다시 계산 → 정확한 행 wrap 과 vertpos.
     */
    static String removeLineSegArrays(String xml) {
        // <hp:linesegarray>…</hp:linesegarray> (close) + <hp:linesegarray .../> (self-close)
        // 두 변형 모두 제거.
        return xml
                .replaceAll("<hp:linesegarray>.*?</hp:linesegarray>", "")
                .replaceAll("<hp:linesegarray\\s*/>", "");
    }

    /**
     * [v15.21] HwpUnitChar 분기 내 &lt;hh:tabItem&gt; 의 {@code unit="HWPUNIT"} 보강.
     *
     * <p>패턴: {@code <hp:case hp:required-namespace="...HwpUnitChar"><hh:tabItem ...
     * (without unit=) .../></hp:case>}. 안쪽 tabItem 에 {@code unit} 속성이 없으면
     * {@code unit="HWPUNIT"} 를 닫는 {@code "/>"} 직전에 삽입.
     */
    static String ensureTabItemUnit(String xml) {
        final String CASE_OPEN = "<hp:case hp:required-namespace=\"http://www.hancom.co.kr/hwpml/2016/HwpUnitChar\">";
        final String CASE_CLOSE = "</hp:case>";
        StringBuilder out = new StringBuilder(xml.length() + 256);
        int i = 0, n = xml.length();
        while (i < n) {
            int caseStart = xml.indexOf(CASE_OPEN, i);
            if (caseStart < 0) {
                out.append(xml, i, n);
                break;
            }
            int caseEnd = xml.indexOf(CASE_CLOSE, caseStart + CASE_OPEN.length());
            if (caseEnd < 0) {
                out.append(xml, i, n);
                break;
            }
            // append up to caseStart unchanged
            out.append(xml, i, caseStart);
            // process inside the case block
            int innerEnd = caseEnd + CASE_CLOSE.length();
            String inner = xml.substring(caseStart, innerEnd);
            // 각 <hh:tabItem .../> 에 unit 속성 없으면 추가
            String patched = inner.replaceAll(
                    "(<hh:tabItem\\b[^/>]*?)(\\s*/>)",
                    "$1MARK_UNIT_$2");
            // 위 패턴은 모든 tabItem 에 MARK_UNIT_ 를 붙임. unit 속성이 이미 있는 항목은
            // 원상복구. 없는 항목만 unit="HWPUNIT" 로 치환.
            patched = patched.replaceAll(
                    "(<hh:tabItem\\b[^/>]*?unit=\"[^\"]*\"[^/>]*?)MARK_UNIT_",
                    "$1");
            patched = patched.replace("MARK_UNIT_", " unit=\"HWPUNIT\"");
            out.append(patched);
            i = innerEnd;
        }
        return out.toString();
    }

    /**
     * [v16t56 task2] ODT 경로 한정 — TOC 점선(leader) 미렌더 회피.
     *
     * <p>외부 hwp2hwpx 는 paraPr/tabPr 마다 {@code <hp:switch>} 의
     * {@code <hp:case required-namespace=".../HwpUnitChar">} 값을 {@code <hp:default>}
     * 의 정확히 절반(정수 /2)으로 자동 생성한다. 문단 좌여백은 한/글2016+ 글자단위
     * 해석이라 무해하나, <b>RIGHT 탭의 탭스톱 위치는 물리 좌표</b>라 절반화가
     * 파괴적이다: 우측여백(예 48161 ≈ 16.99cm)에 있어야 할 탭스톱이 중앙
     * (24080 ≈ 8.49cm)으로 어긋나 제목+탭이 탭스톱을 오버슈트 → 한/글이 leader
     * (점선)을 생략 → 목차 점선 미렌더.</p>
     *
     * <p>본 후패치는 header.xml 의 모든 {@code <hh:tabPr>} 블록에서 hp:case 안
     * {@code <hh:tabItem>} 의 {@code pos} 를 같은 블록 hp:default 의 동일 인덱스
     * tabItem {@code pos} 로 복원(절반화 취소)한다. {@code type}/{@code leader}/
     * {@code unit} 은 무변경. odt 출처(sourceIsOdt=true) 만 적용 — hwp→hwpx 직접
     * 변환 등은 종전 동작 유지.</p>
     */
    static String restoreOdtTabPrCasePos(String xml, boolean sourceIsOdt) {
        if (!sourceIsOdt || xml == null) return xml;
        final String TABPR_OPEN = "<hh:tabPr";
        final String TABPR_CLOSE = "</hh:tabPr>";
        StringBuilder out = new StringBuilder(xml.length() + 256);
        int i = 0, n = xml.length();
        while (i < n) {
            int s = xml.indexOf(TABPR_OPEN, i);
            if (s < 0) { out.append(xml, i, n); break; }
            int tagEnd = xml.indexOf('>', s);
            if (tagEnd < 0) { out.append(xml, i, n); break; }
            out.append(xml, i, s);
            // self-closing <hh:tabPr .../> 는 자식 tabItem 이 없으므로 그대로 통과
            if (xml.charAt(tagEnd - 1) == '/') {
                out.append(xml, s, tagEnd + 1);
                i = tagEnd + 1;
                continue;
            }
            int close = xml.indexOf(TABPR_CLOSE, tagEnd);
            if (close < 0) { out.append(xml, s, n); break; }
            int blockEnd = close + TABPR_CLOSE.length();
            out.append(patchTabPrCasePos(xml.substring(s, blockEnd)));
            i = blockEnd;
        }
        return out.toString();
    }

    /**
     * {@link #restoreOdtTabPrCasePos} 보조: 단일 hh:tabPr 블록 내 모든 hp:switch 의
     * hp:case tabItem pos 복원. 한 tabPr 안에 tabItem 마다 별도 hp:switch 가
     * 여러 개 존재할 수 있으므로 switch 단위로 순회한다(첫 switch 만 처리하면
     * 둘째 이후 case pos 가 절반값으로 잔존).
     */
    private static String patchTabPrCasePos(String block) {
        final String SW_OPEN = "<hp:switch>";
        final String SW_CLOSE = "</hp:switch>";
        StringBuilder out = new StringBuilder(block.length());
        int i = 0, n = block.length();
        while (i < n) {
            int s = block.indexOf(SW_OPEN, i);
            if (s < 0) { out.append(block, i, n); break; }
            int e = block.indexOf(SW_CLOSE, s);
            if (e < 0) { out.append(block, i, n); break; }
            int swEnd = e + SW_CLOSE.length();
            out.append(block, i, s);
            out.append(patchOneSwitchCasePos(block.substring(s, swEnd)));
            i = swEnd;
        }
        return out.toString();
    }

    /** 단일 hp:switch 블록 내 hp:case(HwpUnitChar) tabItem pos 를 hp:default pos 로 복원. */
    private static String patchOneSwitchCasePos(String block) {
        final String CASE_OPEN =
                "<hp:case hp:required-namespace=\"http://www.hancom.co.kr/hwpml/2016/HwpUnitChar\">";
        int caseStart = block.indexOf(CASE_OPEN);
        if (caseStart < 0) return block;
        int caseEnd = block.indexOf("</hp:case>", caseStart);
        if (caseEnd < 0) return block;
        int defStart = block.indexOf("<hp:default>", caseEnd);
        if (defStart < 0) return block;
        int defEnd = block.indexOf("</hp:default>", defStart);
        if (defEnd < 0) return block;
        // hp:default 의 tabItem pos 들을 순서대로 수집
        List<String> defPos = new ArrayList<>();
        java.util.regex.Matcher dm = java.util.regex.Pattern
                .compile("<hh:tabItem\\b[^>]*?\\bpos=\"(-?\\d+)\"")
                .matcher(block.substring(defStart, defEnd));
        while (dm.find()) defPos.add(dm.group(1));
        if (defPos.isEmpty()) return block;
        // hp:case 의 k 번째 tabItem pos 를 defPos[k] 로 치환
        String caseInner = block.substring(caseStart, caseEnd);
        java.util.regex.Matcher cm = java.util.regex.Pattern
                .compile("(<hh:tabItem\\b[^>]*?\\bpos=\")(-?\\d+)(\")")
                .matcher(caseInner);
        StringBuffer sb = new StringBuffer();
        int k = 0;
        while (cm.find()) {
            String rep = k < defPos.size() ? defPos.get(k) : cm.group(2);
            cm.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement(cm.group(1) + rep + cm.group(3)));
            k++;
        }
        cm.appendTail(sb);
        return block.substring(0, caseStart) + sb + block.substring(caseEnd);
    }

    /**
     * [v57 r3 task1] ODT 경로 한정 — 문단 들여쓰기(좌여백) 절반화 취소.
     *
     * <p>외부 hwp2hwpx 는 hp:paraPr 의 hp:switch 에서 hp:default margin 값을
     * hp:case(HwpUnitChar) 의 정확히 2배로 생성한다(예 case left=1499 ↔ default
     * left=2999; raw HWP=1499=case). v16t56 까지는 "한/글2016+ 가 글자단위 case 를
     * 채택하므로 문단여백 절반화는 무해"로 보았으나, 사용자 한/글 실측(v57)에서
     * hwpx 들여쓰기가 hwp(raw=case 값) 대비 약 2배 과대로 렌더됨이 확인됨 →
     * 한/글이 <b>문단 좌여백/들여쓰기는 hp:default 분기</b>를 채택함이 드러남.</p>
     *
     * <p>따라서 hp:default 의 hh:margin 안 {@code <hc:intent>}·{@code <hc:left>}·
     * {@code <hc:right>} 값을 같은 switch 의 hp:case 값으로 복원한다. 상하 문단간격
     * (prev/next)·lineSpacing 은 raw HWP 가 default 와 일치(예 next=300=raw)하므로
     * 무변경 — 증상(들여쓰기)에 한정한 최소 변경. tabPr switch 는 hh:margin 이
     * 없어 자동 제외. odt 출처(sourceIsOdt=true) 만 적용.</p>
     */
    static String restoreOdtParaPrCaseMargin(String xml, boolean sourceIsOdt) {
        if (!sourceIsOdt || xml == null) return xml;
        final String SW_OPEN = "<hp:switch>";
        final String SW_CLOSE = "</hp:switch>";
        StringBuilder out = new StringBuilder(xml.length() + 256);
        int i = 0, n = xml.length();
        while (i < n) {
            int s = xml.indexOf(SW_OPEN, i);
            if (s < 0) { out.append(xml, i, n); break; }
            int e = xml.indexOf(SW_CLOSE, s);
            if (e < 0) { out.append(xml, i, n); break; }
            int swEnd = e + SW_CLOSE.length();
            out.append(xml, i, s);
            out.append(patchOneSwitchDefaultMargin(xml.substring(s, swEnd)));
            i = swEnd;
        }
        return out.toString();
    }

    /** 단일 hp:switch 의 hp:default margin intent/left/right 를 hp:case 값으로 복원. */
    private static String patchOneSwitchDefaultMargin(String block) {
        final String CASE_OPEN =
                "<hp:case hp:required-namespace=\"http://www.hancom.co.kr/hwpml/2016/HwpUnitChar\">";
        int caseStart = block.indexOf(CASE_OPEN);
        if (caseStart < 0) return block;
        int caseEnd = block.indexOf("</hp:case>", caseStart);
        if (caseEnd < 0) return block;
        String caseInner = block.substring(caseStart, caseEnd);
        if (caseInner.indexOf("<hh:margin>") < 0) return block; // tabPr 등 margin 없는 switch 제외
        int defStart = block.indexOf("<hp:default>", caseEnd);
        if (defStart < 0) return block;
        int defEnd = block.indexOf("</hp:default>", defStart);
        if (defEnd < 0) return block;
        // hp:case margin 의 intent/left/right 값 추출
        String iv = marginValue(caseInner, "intent");
        String lv = marginValue(caseInner, "left");
        String rv = marginValue(caseInner, "right");
        if (iv == null && lv == null && rv == null) return block;
        String defBlock = block.substring(defStart, defEnd);
        String patched = defBlock;
        if (iv != null) patched = setMarginValue(patched, "intent", iv);
        if (lv != null) patched = setMarginValue(patched, "left", lv);
        if (rv != null) patched = setMarginValue(patched, "right", rv);
        if (patched.equals(defBlock)) return block;
        return block.substring(0, defStart) + patched + block.substring(defEnd);
    }

    /** {@code <hc:NAME value="X"} 의 X 추출, 없으면 null. */
    private static String marginValue(String s, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<hc:" + name + "\\b[^>]*?\\bvalue=\"(-?\\d+)\"")
                .matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** {@code <hc:NAME value="..."} 의 값을 v 로 치환(첫 1개). */
    private static String setMarginValue(String s, String name, String v) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(<hc:" + name + "\\b[^>]*?\\bvalue=\")(-?\\d+)(\")")
                .matcher(s);
        if (!m.find()) return s;
        StringBuffer sb = new StringBuffer();
        m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(1) + v + m.group(3)));
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * v15.11: 책갈피 주입.
     *
     * <p>hwpxlib 1.0.9 의 writer 는 Bookmark 객체를 XML 로 직렬화하지 않으므로
     * (BookmarkWriter 부재) ?bookmark_name 타입 HYPERLINK 가 한/글에서 클릭해도
     * 이동할 target 이 없다. 회피책으로 소스 HWP 의 paragraph 별 책갈피 이름을
     * 받아서 출력 XML 의 top-level &lt;hp:p&gt; 의 첫 &lt;hp:run&gt; 내부에
     * &lt;hp:ctrl&gt;&lt;hp:bookmark name="..."/&gt;&lt;/hp:ctrl&gt; 를 직접 삽입한다.</p>
     *
     * <p>책갈피의 정확한 character 위치를 파악하기 어려우므로 paragraph 의 첫 run
     * 시작부에 일관 배치 — 한/글의 책갈피 hyperlink 이동은 paragraph-level 정확도로
     * 충분히 작동.</p>
     *
     * @param xml 입력 section0.xml
     * @param sectionBookmarks paragraph 인덱스 → 책갈피 이름 리스트. null/빈 리스트
     *                        이면 입력 XML 을 변경 없이 반환.
     */
    static String injectBookmarks(String xml, List<List<String>> sectionBookmarks) {
        if (sectionBookmarks == null || sectionBookmarks.isEmpty()) return xml;
        // top-level paragraph 만 매칭 — &lt;hp:subList&gt; 내부 paragraph 는 카운트
        // 하지 않는다. 소스 HWP 의 collectHwpBookmarks 도 top-level section
        // paragraph 만 다루므로 인덱스가 1:1 로 정렬됨.
        StringBuilder out = new StringBuilder(xml.length() + 1024);
        final String P_OPEN  = "<hp:p ";
        final String SUB_OPEN  = "<hp:subList";
        final String SUB_CLOSE = "</hp:subList>";
        int subDepth = 0;
        int topParaIdx = -1;
        int i = 0, n = xml.length();
        while (i < n) {
            char c = xml.charAt(i);
            if (c != '<') { out.append(c); i++; continue; }
            if (xml.regionMatches(i, SUB_CLOSE, 0, SUB_CLOSE.length())) {
                out.append(SUB_CLOSE);
                i += SUB_CLOSE.length();
                if (subDepth > 0) subDepth--;
            } else if (xml.regionMatches(i, SUB_OPEN, 0, SUB_OPEN.length())) {
                int end = xml.indexOf('>', i);
                if (end < 0) { out.append(xml, i, n); break; }
                out.append(xml, i, end + 1);
                boolean selfClosing = xml.charAt(end - 1) == '/';
                if (!selfClosing) subDepth++;
                i = end + 1;
            } else if (subDepth == 0 && xml.regionMatches(i, P_OPEN, 0, P_OPEN.length())) {
                topParaIdx++;
                int end = xml.indexOf('>', i);
                if (end < 0) { out.append(xml, i, n); break; }
                out.append(xml, i, end + 1);
                i = end + 1;
                // 이 paragraph 에 책갈피가 있으면 첫 &lt;hp:run ...&gt; 다음에 주입.
                if (topParaIdx < sectionBookmarks.size()
                        && !sectionBookmarks.get(topParaIdx).isEmpty()) {
                    // 첫 &lt;hp:run ...&gt; 을 찾는다 — paragraph 내부에서.
                    int runOpen = xml.indexOf("<hp:run", i);
                    int pClose  = xml.indexOf("</hp:p>", i);
                    if (runOpen >= 0 && (pClose < 0 || runOpen < pClose)) {
                        int runEnd = xml.indexOf('>', runOpen);
                        if (runEnd > 0) {
                            // 첫 run opening 까지 그대로 출력 + run 자체 출력 + 책갈피 주입.
                            out.append(xml, i, runEnd + 1);
                            for (String name : sectionBookmarks.get(topParaIdx)) {
                                out.append("<hp:ctrl><hp:bookmark name=\"")
                                   .append(escapeXmlAttr(name))
                                   .append("\"/></hp:ctrl>");
                            }
                            i = runEnd + 1;
                        }
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String escapeXmlAttr(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
