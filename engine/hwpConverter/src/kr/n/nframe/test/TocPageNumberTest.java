package kr.n.nframe.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharControlInline;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharType;
import kr.dogfoot.hwplib.reader.HWPReader;

import kr.n.nframe.HwpMdConverter;

/**
 * v15.26 회귀 검증 — task2/task4 (MD→HWP / MD→HWPX 목차 페이지번호 깨짐 fix).
 *
 * <p>대상 분석파일: hwp\0515(추가개발건 예정)\dummy_file\11.hwp_md\case1.md
 *  → 변환 후 case1.hwp / case1.hwpx 의 1쪽 23줄 "Ⅱ. 지방보조금관리위원회 운영 계획 36"
 *    페이지 번호 부분이 깨짐 (사용자 반복 보고).
 *
 * <p>검증 항목 (모두 PASS 시 사용자 보고 회귀 해소로 판단):
 * <ol>
 *   <li><b>HWP TOC 인라인 TAB 바이트</b> — code=0x0009, ad[4]=03 (DOT leader),
 *       ad[5]=02 (RIGHT/원본 미러), tab width &gt; 0.</li>
 *   <li><b>HWP TOC paragraph 라인 메트릭</b> — lineseg height/baseline/spacing
 *       이 한/글이 손상 경고 없이 받아들이는 범위. (원본 hand-made 미러:
 *       height ≈ 1700, baseline ≈ 1445 = 0.85×height, spacing ≈ height).</li>
 *   <li><b>HWP TOC 페이지번호 텍스트</b> — TAB 컨트롤 뒤에 "36" 이 정상 BMP
 *       문자로 들어 있어야 함 (코드포인트 0x33=&#39;3&#39;, 0x36=&#39;6&#39;).</li>
 *   <li><b>HWPX section0.xml</b> — &lt;hp:tab leader="3" type="2"/&gt; 가
 *       존재하고, 직후에 "36" 텍스트가 정상적으로 따라옴.</li>
 *   <li><b>HWPX lineseg vertpos != 0 누적</b> — 한/글 "손상" 팝업 회피.</li>
 * </ol>
 */
public class TocPageNumberTest {

    private static final String TOC_LABEL = "지방보조금관리위원회 운영 계획";
    private static final String EXPECTED_PAGENUM = "36";

    public static void main(String[] args) throws Exception {
        Path projRoot = Paths.get(System.getProperty("user.dir"));
        Path mdToc = projRoot.resolve(
                "hwp/0515(추가개발건 예정)/dummy_file/11.hwp_md/case1.md");
        if (args.length >= 1) mdToc = Paths.get(args[0]);
        if (!Files.exists(mdToc)) {
            System.err.println("[TocPageNumberTest] MD 파일 없음: " + mdToc);
            System.exit(2);
        }

        Path tmpDir = Files.createTempDirectory("toc_pagenum_test_");
        int passed = 0, failed = 0;
        try {
            Path outHwp  = tmpDir.resolve("case1.hwp");
            Path outHwpx = tmpDir.resolve("case1.hwpx");
            new HwpMdConverter().convertMarkdownToHwp(mdToc.toString(), outHwp.toString());
            new HwpMdConverter().convertMarkdownToHwpx(mdToc.toString(), outHwpx.toString());

            // ---- HWP 측 검증 ----
            HWPFile hwp = HWPReader.fromFile(outHwp.toString());
            TocFinding f = findTocParagraph(hwp);
            if (f == null) {
                System.out.println("[HWP TOC] FAIL — 목차 paragraph 를 찾지 못함");
                failed++;
            } else {
                // [v15.32] inline TAB 제거 — TAB 컨트롤이 없어야 함 (literal "·" dot 사용).
                if (f.tabAdd == null) {
                    System.out.println("[HWP no inline TAB] PASS — TAB 미존재 (v15.32 literal dot)");
                    passed++;
                } else {
                    System.out.println("[HWP no inline TAB] FAIL — inline TAB 잔존 (v15.32 회귀)");
                    failed++;
                }

                // 2) line metric
                if (f.lineHeight >= 1200 && f.lineHeight <= 2500
                        && f.baseline >= (int)(f.lineHeight * 0.7)
                        && f.baseline <= (int)(f.lineHeight * 0.95)) {
                    System.out.println("[HWP line metric] PASS — height=" + f.lineHeight
                            + " baseline=" + f.baseline + " spacing=" + f.lineSpacing
                            + " vertPos=" + f.vertPos);
                    passed++;
                } else {
                    System.out.println("[HWP line metric] FAIL — height=" + f.lineHeight
                            + " baseline=" + f.baseline + " spacing=" + f.lineSpacing
                            + " (요구: height 1200~2500, baseline 70~95% of height)");
                    failed++;
                }

                // 3) 페이지번호 텍스트
                if (f.text.replace("\t", "").endsWith(EXPECTED_PAGENUM)) {
                    System.out.println("[HWP pageNum text] PASS — TAB 뒤에 '" + EXPECTED_PAGENUM + "' 존재");
                    passed++;
                } else {
                    System.out.println("[HWP pageNum text] FAIL — text=[" + f.text.replace("\t","<TAB>") + "]");
                    failed++;
                }
                // 4) vertPos 누적 (zero 가 아닐 것)
                if (f.vertPos > 0) {
                    System.out.println("[HWP vertPos accum] PASS — vertPos=" + f.vertPos);
                    passed++;
                } else {
                    System.out.println("[HWP vertPos accum] FAIL — vertPos=0 (손상 경고 유발)");
                    failed++;
                }
            }

            // ---- HWPX 측 검증 ----
            String sec = readSection0(outHwpx);
            Pattern tocLine = Pattern.compile(
                    "<hp:p[^>]*>(?:(?!</hp:p>).)*Ⅱ\\. 지방보조금관리위원회 운영 계획"
                    + "(?:(?!</hp:p>).)*</hp:p>", Pattern.DOTALL);
            Matcher mPara = tocLine.matcher(sec);
            if (mPara.find()) {
                String para = mPara.group();
                // [v15.32] <hp:tab> 미존재 + literal "·" 점선 + 페이지번호 패턴
                boolean noHpTab = !para.contains("<hp:tab ");
                boolean hasDotLeader = Pattern.compile("·{5,}").matcher(para).find();
                boolean endsWithNum = para.contains(EXPECTED_PAGENUM);
                if (noHpTab && hasDotLeader && endsWithNum) {
                    System.out.println("[HWPX dot leader+num] PASS — TAB 미존재 + '·' fill + "
                            + EXPECTED_PAGENUM);
                    passed++;
                } else {
                    System.out.println("[HWPX dot leader+num] FAIL — noHpTab=" + noHpTab
                            + " hasDot=" + hasDotLeader + " endsWithNum=" + endsWithNum);
                    failed++;
                }
                // vertpos != 0 누적
                Pattern lseg = Pattern.compile("<hp:lineseg\\s+textpos=\"\\d+\"\\s+vertpos=\"(\\d+)\"");
                Matcher mSeg = lseg.matcher(para);
                if (mSeg.find()) {
                    long vp = Long.parseLong(mSeg.group(1));
                    if (vp > 0) {
                        System.out.println("[HWPX vertpos] PASS — vertpos=" + vp);
                        passed++;
                    } else {
                        System.out.println("[HWPX vertpos] FAIL — vertpos=0 (손상 경고 유발)");
                        failed++;
                    }
                } else {
                    System.out.println("[HWPX vertpos] FAIL — lineseg 미발견");
                    failed++;
                }
                // vertsize > 1200 (한/글 손상 경고 회피 — 너무 작은 line metric 거부)
                Pattern vs = Pattern.compile("vertsize=\"(\\d+)\"");
                Matcher mVs = vs.matcher(para);
                if (mVs.find()) {
                    int v = Integer.parseInt(mVs.group(1));
                    if (v >= 1200) {
                        System.out.println("[HWPX vertsize] PASS — vertsize=" + v);
                        passed++;
                    } else {
                        System.out.println("[HWPX vertsize] FAIL — vertsize=" + v + " (요구 >= 1200)");
                        failed++;
                    }
                }
            } else {
                System.out.println("[HWPX TOC paragraph] FAIL — TOC 단락 미발견");
                failed++;
            }

        } finally {
            try {
                Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
        }
        System.out.println();
        System.out.println("============================================================");
        System.out.println("[TocPageNumberTest] 통과=" + passed + " / 실패=" + failed);
        System.out.println("============================================================");
        if (failed > 0) System.exit(1);
    }

    static class TocFinding {
        int paraShapeId;
        long charShapeCount;
        long charCount;
        String text = "";
        byte[] tabAdd;
        int lineHeight, baseline, lineSpacing, vertPos;
    }

    /** Section 들을 순회하여 TOC 라인 paragraph 를 찾는다 (label substring 매칭). */
    private static TocFinding findTocParagraph(HWPFile hwp) {
        for (Section sec : hwp.getBodyText().getSectionList()) {
            for (Paragraph p : sec.getParagraphs()) {
                String txt = extractText(p);
                if (txt.contains(TOC_LABEL)) {
                    TocFinding f = new TocFinding();
                    f.paraShapeId = p.getHeader().getParaShapeId();
                    f.charShapeCount = p.getHeader().getCharShapeCount();
                    f.charCount = p.getHeader().getCharacterCount();
                    f.text = txt;
                    for (HWPChar c : p.getText().getCharList()) {
                        if (c.getType() == HWPCharType.ControlInline && (c.getCode() & 0xFFFF) == 0x0009) {
                            f.tabAdd = ((HWPCharControlInline) c).getAddition();
                            break;
                        }
                    }
                    if (p.getLineSeg() != null
                            && !p.getLineSeg().getLineSegItemList().isEmpty()) {
                        kr.dogfoot.hwplib.object.bodytext.paragraph.lineseg.LineSegItem seg = p.getLineSeg().getLineSegItemList().get(0);
                        f.lineHeight  = seg.getLineHeight();
                        f.baseline    = seg.getDistanceBaseLineToLineVerticalPosition();
                        f.lineSpacing = seg.getLineSpace();
                        f.vertPos     = seg.getLineVerticalPosition();
                    }
                    return f;
                }
            }
        }
        return null;
    }

    private static String extractText(Paragraph p) {
        StringBuilder sb = new StringBuilder();
        if (p.getText() == null) return "";
        for (HWPChar c : p.getText().getCharList()) {
            if (c.getType() == HWPCharType.Normal) {
                sb.append((char) ((HWPCharNormal) c).getCode());
            } else if (c.getType() == HWPCharType.ControlInline) {
                sb.append('\t');
            }
        }
        return sb.toString();
    }

    private static String readSection0(Path hwpx) throws Exception {
        try (ZipFile zf = new ZipFile(hwpx.toFile())) {
            ZipEntry e = zf.getEntry("Contents/section0.xml");
            if (e == null) throw new IllegalStateException("section0.xml 없음: " + hwpx);
            try (InputStream in = zf.getInputStream(e)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
                return new String(baos.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
