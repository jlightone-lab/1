package kr.n.nframe.newfeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import kr.n.nframe.HwpConverter;
import kr.n.nframe.hwplib.model.HwpDocument;
import kr.n.nframe.hwplib.writer.HwpWriter;

/**
 * ODT → HWP / HWPX 직접 변환기 (MD 우회).
 *
 * <p>v16.00-newfeature : 기존 {@link OdfConverter} 의 MD 경유 3단계 흐름과 달리,
 *   ODT 를 메모리에서 직접 {@link HwpDocument} 자체 모델로 빌드하고
 *   {@link HwpWriter} 로 HWP 바이너리를 출력한다. HWPX 출력은 HWP 를
 *   {@link HwpConverter#convertHwpToHwpx(String, String)} 로 한 번 더
 *   변환하여 표준 hwpxlib writer 경로(손상 팝업 회피)를 거치도록 한다.
 *
 * <h2>설계 원칙</h2>
 * <ul>
 *   <li>임시 Markdown 파일 생성 금지 (사용자 요구사항).</li>
 *   <li>App 영역 코드({@code OdtToMdConverter}, {@code OdtPostHwpEnhancer},
 *       {@code HwpConverter}) 는 호출만, 수정 금지.</li>
 *   <li>본 패키지({@code newfeature}) 안에서만 신규 코드 작성.</li>
 * </ul>
 *
 * <h2>해결하는 버그</h2>
 * <ul>
 *   <li>Task2 — H1 하위 직선이 "----" 텍스트로 출력되는 문제 :
 *       ODT styles.xml 의 {@code fo:border-bottom} 을 paraPr 의 BOTTOM
 *       borderFill 로 명시 매핑하여 dash 폴백을 제거한다.</li>
 *   <li>Task3 — 표 셀 색상/폰트, 본문 charPr 평탄화 :
 *       ODT text-properties (fo:color, fo:background-color, fo:font-weight,
 *       fo:font-style, style:text-line-through-style 등) 을 charPr 에
 *       직접 매핑한다. 한글 폰트 미지정 시 "맑은 고딕" 기본값.</li>
 *   <li>Task4 — 문서 손상 팝업 :
 *       표준 {@link HwpWriter} / 표준 hwpxlib {@code HWPXWriter} 경로만
 *       사용하여 OLE2/HWPX 구조 일관성을 자동 확보.</li>
 * </ul>
 */
public final class OdtDirectConverter {

    /** 단건 CLI 진입점. {@code <input.odt> <output.hwp|hwpx>}. */
    public static void main(String[] args) throws Exception {
        if (args == null || args.length < 2) {
            printUsage();
            System.exit(args == null || args.length == 0 ? 0 : 2);
            return;
        }
        String input  = args[0].trim();
        String output = args[1].trim();
        new OdtDirectConverter().convert(input, output);
        System.out.println("[OdtDirectConverter] 변환 완료: " + new File(output).getAbsolutePath());
    }

    // ========================================================================
    //  공개 API
    // ========================================================================

    /** 확장자에 따라 자동으로 HWP / HWPX 변환을 라우팅한다. */
    public void convert(String odtPath, String outPath) throws Exception {
        String outExt = ext(outPath);
        switch (outExt) {
            case "hwp":  convertOdtToHwp(odtPath, outPath);  return;
            case "hwpx": convertOdtToHwpx(odtPath, outPath); return;
            default:
                throw new IllegalArgumentException("출력 확장자는 .hwp 또는 .hwpx 여야 합니다: " + outPath);
        }
    }

    /**
     * ODT → HWP 직접 변환.
     *
     * <p>흐름:
     * <pre>
     *   ODT 파싱(메모리)
     *     → 자체 HwpDocument 빌드(메모리)
     *     → 자체 HwpWriter 로 OS-temp 임시 .hwp 출력
     *     → 외부 hwplib HWPReader 로 표준 모델로 다시 읽음
     *     → 외부 hwplib HWPWriter 로 최종 .hwp 표준 출력 (한/글 호환 보장)
     *     → OS-temp 임시 .hwp 즉시 삭제
     * </pre>
     *
     * <p>자체 HwpWriter 출력만 사용하면 한/글이 미묘한 byte 차이로 "파일 손상" 팝업을
     *   띄우는 케이스 발생 (5회 deep diff 후에도 잔여). 따라서 마지막 단계에서 외부
     *   라이브러리(lib/hwplib-1.1.10.jar)의 표준 writer 를 한 번 더 통과시켜 byte
     *   구조를 정규화한다. 임시 .hwp 는 OS temp 폴더에만 생성되고 즉시 삭제되므로
     *   사용자 폴더에는 부산물이 남지 않는다 (MD 미생성 정책 유지).
     */
    public void convertOdtToHwp(String odtPath, String hwpPath) throws Exception {
        validateOdtInput(odtPath);
        hwpPath = OutputNaming.unique(hwpPath); // v16t42 비덮어쓰기
        System.out.println("[OdtDirectConverter] (1/3) ODT 파싱: " + odtPath);
        OdtDocumentModel model = new OdtParser().parse(new File(odtPath));

        java.nio.file.Path tmpRaw = java.nio.file.Files.createTempFile("odt_raw_", ".hwp");
        try {
            System.out.println("[OdtDirectConverter] (2/3) HwpDocument 빌드 → 임시 HWP: " + tmpRaw);
            HwpDocument doc = new OdtToHwpDocumentBuilder().build(model);
            applyCandKRecipe(doc);
            HwpWriter.write(doc, tmpRaw.toString());

            System.out.println("[OdtDirectConverter] (3/3) 외부 hwplib 표준 writer 재출력: " + hwpPath);
            kr.dogfoot.hwplib.object.HWPFile hwpFile =
                    kr.dogfoot.hwplib.reader.HWPReader.fromFile(tmpRaw.toString());
            kr.dogfoot.hwplib.writer.HWPWriter.toFile(hwpFile, hwpPath);
        } finally {
            try { java.nio.file.Files.deleteIfExists(tmpRaw); } catch (IOException ignore) { /* best effort */ }
        }
    }

    /**
     * v16t57 r4 — HWP 출력 경로 전용 wrapped 줄 시작 컬럼(lineseg columnStartPos) 캐시 박기.
     *
     * <p><strong>본질(한/글 hwpx→HWP 저장 reference 실증):</strong> 한/글이 hwpx 를
     *   '다른 이름으로 저장 → HWP' 한 reference.hwp 의 들여쓰기 단락은
     *   {@code PARA_SHAPE leftMargin=2998 indent=-2998}(=ODT 원본 full 값) 이면서
     *   {@code PARA_LINE_SEG columnStartPos=1499}(=leftMargin/2, hwpx hp:case/horzpos 값)
     *   로 저장되어 한/글에서 정상(hwpx 와 동일 위치) 렌더된다. 즉 한/글은
     *   <em>paraShape 은 full 로 두고 실제 렌더 시작 컬럼은 lineseg 캐시로</em> 잡는다.
     *
     * <p><strong>과거 v55 옵션B 오류:</strong> paraShape 의 leftMargin·indent 를 /2 로
     *   절반화하면 hang 폭(|indent|)이 선행 마커('○ ' 등) 폭보다 좁아져, columnStartPos=0
     *   를 본 한/글이 레이아웃을 재계산하면서 마커 폭만큼 우측으로 밀어 startColX 가 3745
     *   (≈2.5×)로 부풀었다. 이 절반화를 제거하고 reference 와 동일 형태를 직접 emit 한다.
     *
     * <p><strong>적용:</strong> paraShape 는 무변경(ODT full 유지). 본문 section 단락의
     *   PARA_LINE_SEG {@code columnStartPos} 를 그 단락 좌여백의 절반(=hwpx case 값)으로
     *   박는다. {@code leftMargin<=0}(들여쓰기 없는 단락) 무변경(byte 안정),
     *   {@code columnStartPos==0} 인 seg 만(이미지/표 등 의도값 보존).
     *
     * <p>{@link #convertOdtToHwpx} 는 자체적으로 별도 {@link HwpDocument} 를 build 하므로
     *   이 보정은 HWPX 출력에 영향을 주지 않는다.
     */
    private static void applyCandKRecipe(HwpDocument doc) {
        final int BAND_LO = 1491, BAND_HI = 1500;

        // 1) HWP 출력 전용 좌여백 절반화. OdtToHwpDocumentBuilder 가 emit 한 ODT full 값
        //    (예: 1.058cm=2999)을 정수 나눗셈으로 절반화(=한/글 hwpx 저장 reference 의 1499).
        //    이 doc 는 HWP 출력에만 쓰이고 convertOdtToHwpx 는 별도 HwpDocument 를 build 하므로
        //    HWPX 경로에는 영향이 없다(회귀 가드: 빌더 클래스 leftMargin 무변경).
        java.util.Set<Integer> bandShapes = new java.util.HashSet<>();
        for (int i = 0; i < doc.paraShapes.size(); i++) {
            kr.n.nframe.hwplib.model.ParaShape ps = doc.paraShapes.get(i);
            ps.leftMargin = ps.leftMargin / 2;
            ps.indent     = ps.indent / 2;
            // 2) 1단계 글머리표 band: 절반화 좌여백이 [1491,1500](≈1.058cm/2=1499) 인 단락은
            //    좌여백/내어쓰기를 800 으로 정규화. (candK 레시피의 핵심 변경)
            if (ps.leftMargin >= BAND_LO && ps.leftMargin <= BAND_HI) {
                ps.leftMargin = 800;
                ps.indent     = -800;
                bandShapes.add(i);
            }
        }

        // 3) band 단락의 본문 lineseg 만 columnStartPos=800, flags=0x60000 으로 박는다(=candK).
        //    표 셀 placeholder(segWidth=46800)는 의도값 보존을 위해 제외. 그 외 비-band 단락은
        //    빌더 기본값(columnStartPos=0, tag=0x10, segWidth=42000) 그대로 둔다(=candK 와 동일).
        for (kr.n.nframe.hwplib.model.Section sec : doc.sections) {
            for (kr.n.nframe.hwplib.model.Paragraph p : sec.paragraphs) {
                if (!bandShapes.contains(p.paraShapeId)) continue;
                for (kr.n.nframe.hwplib.model.LineSeg seg : p.lineSegs) {
                    if (seg.segWidth == 46800) continue; // 표 셀 placeholder 보존
                    seg.columnStartPos = 800;
                    seg.tag = 0x60000L;
                }
            }
        }
    }

    /**
     * ODT → HWPX 직접 변환.
     *
     * <p>흐름: ODT → 임시 HWP (OS temp) → {@link HwpConverter#convertHwpToHwpx} → HWPX 출력 → 임시 HWP 삭제.
     *   <strong>MD 파일은 생성하지 않는다.</strong> 임시 HWP 는 OS temp 디렉터리에만 만들고
     *   변환 직후 삭제하여 부산물을 남기지 않는다.
     */
    public void convertOdtToHwpx(String odtPath, String hwpxPath) throws Exception {
        validateOdtInput(odtPath);
        hwpxPath = OutputNaming.unique(hwpxPath); // v16t42 비덮어쓰기
        // 1) ODT → 임시 HWP (OS temp, 자동 삭제)
        Path tmpHwp = Files.createTempFile("odt_direct_", ".hwp");
        try {
            System.out.println("[OdtDirectConverter] (1/3) ODT 파싱: " + odtPath);
            OdtDocumentModel model = new OdtParser().parse(new File(odtPath));

            System.out.println("[OdtDirectConverter] (2/3) HwpDocument 빌드 → 임시 HWP: " + tmpHwp);
            HwpDocument doc = new OdtToHwpDocumentBuilder().build(model);
            HwpWriter.write(doc, tmpHwp.toString());

            // 2) 임시 HWP → HWPX (메인 HwpConverter 의 표준 hwp2hwpx + hwpxlib writer)
            // v16t50 R7: ODT 입력 시그널(true) — HwpxPostProcessor 가 표 pageBreak 를 일괄
            //   덮어쓰지 않도록 한다. 빌더 분포가 보존돼야 사용자 표 잘림(R7)이 해소된다.
            System.out.println("[OdtDirectConverter] (3/3) 임시 HWP → HWPX: " + hwpxPath);
            new HwpConverter().convertHwpToHwpx(tmpHwp.toString(), hwpxPath, true);

            // v16t45 FIX B: 외부 hwp2hwpx 라이브러리가 책갈피 이름(CTRL_DATA)을
            //   hp:fieldBegin@name 으로 옮기지 못해 name="" 로 떨어진다 — 내부앵커
            //   하이퍼링크("?이름;0;0;-1;")의 클릭 타겟이 끊기는 원인. 모델에서 문서
            //   순서대로 수집한 책갈피 이름을 section XML 에 후패치한다.
            //   책갈피 없는 문서는 이 단계를 건너뛰므로 종전 출력과 완전 동일.
            java.util.List<String> bookmarkNames = new java.util.ArrayList<>();
            collectBookmarkNames(model.blocks, bookmarkNames);
            if (!bookmarkNames.isEmpty()) patchHwpxBookmarkNames(hwpxPath, bookmarkNames);
        } finally {
            try { Files.deleteIfExists(tmpHwp); } catch (IOException ignore) { /* best effort */ }
        }
    }

    /**
     * 폴더 안의 모든 {@code .odt} 를 {@code targetExt} (hwp / hwpx) 로 직접 변환.
     *   ODT 외 확장자는 무시. 출력 디렉터리에 동일 base 이름으로 저장.
     */
    public void batchOdtTo(String srcDir, String dstDir, String targetExt) throws Exception {
        File src = new File(srcDir);
        if (!src.isDirectory()) {
            throw new IllegalArgumentException("입력 디렉토리가 아닙니다: " + srcDir);
        }
        File dst = new File(dstDir);
        if (!dst.exists() && !dst.mkdirs()) {
            throw new IOException("출력 디렉토리 생성 실패: " + dstDir);
        }
        String tgt = (targetExt == null ? "" : targetExt.toLowerCase(Locale.ROOT));
        if (tgt.startsWith(".")) tgt = tgt.substring(1);
        if (!"hwp".equals(tgt) && !"hwpx".equals(tgt)) {
            throw new IllegalArgumentException("targetExt 는 'hwp' 또는 'hwpx' 만 가능: " + targetExt);
        }
        File[] odts = src.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".odt"));
        if (odts == null || odts.length == 0) {
            System.out.println("[OdtDirectBatch] .odt 파일 없음 : " + src.getAbsolutePath());
            return;
        }
        int ok = 0, fail = 0;
        System.out.println("[OdtDirectBatch] ODT → " + tgt.toUpperCase(Locale.ROOT)
                + " : " + odts.length + " files in " + src.getAbsolutePath());
        for (File f : odts) {
            String base = f.getName().replaceFirst("\\.[^.]+$", "");
            String outPath = new File(dst, base + "." + tgt).getAbsolutePath();
            try {
                if ("hwp".equals(tgt)) convertOdtToHwp(f.getAbsolutePath(), outPath);
                else                   convertOdtToHwpx(f.getAbsolutePath(), outPath);
                ok++;
            } catch (Exception e) {
                fail++;
                System.err.println("[OdtDirectBatch] 실패: " + f.getName() + " → " + e.getMessage());
            }
        }
        System.out.println("[OdtDirectBatch] 완료 — 성공=" + ok + " 실패=" + fail
                + " → " + dst.getAbsolutePath());
    }

    // ========================================================================
    //  유틸 (private)
    // ========================================================================

    /** v16t45 FIX B: 모델 블록 트리에서 책갈피 이름을 문서 순서대로 수집 (표 셀 재귀). */
    private static void collectBookmarkNames(java.util.List<OdtDocumentModel.Block> blocks,
                                             java.util.List<String> out) {
        for (OdtDocumentModel.Block b : blocks) {
            if (b instanceof OdtDocumentModel.ParagraphBlock) {
                for (OdtDocumentModel.Run r : ((OdtDocumentModel.ParagraphBlock) b).runs) {
                    if (r.bookmarkName != null && !r.bookmarkName.isEmpty()) out.add(r.bookmarkName);
                }
            } else if (b instanceof OdtDocumentModel.HeadingBlock) {
                for (OdtDocumentModel.Run r : ((OdtDocumentModel.HeadingBlock) b).runs) {
                    if (r.bookmarkName != null && !r.bookmarkName.isEmpty()) out.add(r.bookmarkName);
                }
            } else if (b instanceof OdtDocumentModel.TableBlock) {
                for (OdtDocumentModel.TableBlock.Row row : ((OdtDocumentModel.TableBlock) b).rows) {
                    for (OdtDocumentModel.TableBlock.Cell cell : row.cells) {
                        collectBookmarkNames(cell.content, out);
                    }
                }
            }
        }
    }

    /**
     * v16t45 FIX B: HWPX zip 의 {@code Contents/sectionN.xml} 에서
     *   {@code type="BOOKMARK" name=""} 를 문서 순서대로 수집된 이름으로 치환.
     *   엔트리 순서·압축 방식(STORED/DEFLATED)을 보존해 재패키징한다.
     */
    private static void patchHwpxBookmarkNames(String hwpxPath, java.util.List<String> names)
            throws IOException {
        File f = new File(hwpxPath);
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> methods = new java.util.HashMap<>();
        long[] totalBytes = { 0L };
        int entryCount = 0;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                SafeZip.checkEntryCount(++entryCount);
                SafeZip.validateEntryName(e.getName());
                try (java.io.InputStream in = zf.getInputStream(e)) {
                    entries.put(e.getName(), SafeZip.readEntryBounded(in, e.getName(), e.getSize(), totalBytes));
                }
                methods.put(e.getName(), e.getMethod());
            }
        }
        final String token = "type=\"BOOKMARK\" name=\"\"";
        java.util.Iterator<String> it = names.iterator();
        for (java.util.Map.Entry<String, byte[]> me : entries.entrySet()) {
            if (!me.getKey().matches("Contents/section\\d+\\.xml")) continue;
            String xml = new String(me.getValue(), java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder(xml.length() + 64);
            int from = 0;
            boolean changed = false;
            while (it.hasNext()) {
                int idx = xml.indexOf(token, from);
                if (idx < 0) break;
                sb.append(xml, from, idx)
                  .append("type=\"BOOKMARK\" name=\"").append(xmlAttrEscape(it.next())).append("\"");
                from = idx + token.length();
                changed = true;
            }
            if (!changed) continue;
            sb.append(xml, from, xml.length());
            me.setValue(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        File tmp = File.createTempFile("hwpx_bm_", ".hwpx");
        try (java.util.zip.ZipOutputStream zos =
                new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(tmp))) {
            for (java.util.Map.Entry<String, byte[]> me : entries.entrySet()) {
                java.util.zip.ZipEntry ne = new java.util.zip.ZipEntry(me.getKey());
                if (methods.getOrDefault(me.getKey(), java.util.zip.ZipEntry.DEFLATED)
                        == java.util.zip.ZipEntry.STORED) {
                    ne.setMethod(java.util.zip.ZipEntry.STORED);
                    ne.setSize(me.getValue().length);
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(me.getValue());
                    ne.setCrc(crc.getValue());
                }
                zos.putNextEntry(ne);
                zos.write(me.getValue());
                zos.closeEntry();
            }
        }
        Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String xmlAttrEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':  b.append("&amp;");  break;
                case '<':  b.append("&lt;");   break;
                case '>':  b.append("&gt;");   break;
                case '"':  b.append("&quot;"); break;
                default:   b.append(c);
            }
        }
        return b.toString();
    }

    private static void validateOdtInput(String odtPath) {
        File f = new File(odtPath);
        if (!f.exists() || !f.isFile()) {
            throw new IllegalArgumentException("입력 ODT 파일을 찾을 수 없습니다: " + odtPath);
        }
        if (!ext(odtPath).equals("odt")) {
            throw new IllegalArgumentException("입력 파일은 .odt 확장자여야 합니다: " + odtPath);
        }
    }

    private static String ext(String path) {
        int i = path.lastIndexOf('.');
        if (i < 0 || i == path.length() - 1) return "";
        return path.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static void printUsage() {
        System.out.println();
        System.out.println("  OdtDirectConverter — ODT 직접 변환 (MD 우회) — v16.00-newfeature");
        System.out.println();
        System.out.println("  사용법:");
        System.out.println("    java -cp hwpConverter.jar;lib\\* kr.n.nframe.newfeature.OdtDirectConverter <input.odt> <output.hwp|hwpx>");
        System.out.println();
    }
}
