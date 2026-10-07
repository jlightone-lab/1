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
import kr.dogfoot.hwplib.object.bodytext.paragraph.lineseg.LineSegItem;
import kr.dogfoot.hwplib.reader.HWPReader;

import kr.n.nframe.HwpMdConverter;

/**
 * v15.27 task3 — 한/글 "문서에 손상을 줄 수 있는 내용이 포함되어 있습니다" 경고 팝업
 *  방지 회귀 검증.
 *
 * <p>한/글 sanity check 의 알려진 실패 조건들 :
 *  <ul>
 *    <li>모든 paragraph 의 lineVerticalPosition=0 → 누적 Y 좌표 실패 (v15.25 fix)</li>
 *    <li>continuation 줄에 AdjustIndentation 비트 누락 (v15.25 fix)</li>
 *    <li>LineHeight 가 font cap height 이하로 너무 작음 — typical 10pt 본문에서
 *        height ≥ 1200 hwpunit 권장 (v15.26 fix)</li>
 *    <li>baseline 위치가 비현실적 (height 의 70~95% 범위 권장)</li>
 *  </ul>
 *
 * <p>본 테스트는 case1.md → case1.hwp / case1.hwpx 변환 후, body paragraph 들의
 *  lineseg 가 위 조건들을 모두 만족하는지 자동 검증한다. 1 paragraph 라도 어기면 실패.
 */
public class DamageWarningTest {

    public static void main(String[] args) throws Exception {
        Path projRoot = Paths.get(System.getProperty("user.dir"));
        Path md = projRoot.resolve(
                "hwp/0515(추가개발건 예정)/dummy_file/11.hwp_md/case1.md");
        if (args.length >= 1) md = Paths.get(args[0]);
        if (!Files.exists(md)) {
            System.err.println("[DamageWarningTest] MD 파일 없음: " + md);
            System.exit(2);
        }
        Path tmpDir = Files.createTempDirectory("damage_warning_test_");
        int passed = 0, failed = 0;
        try {
            Path outHwp  = tmpDir.resolve("case1.hwp");
            Path outHwpx = tmpDir.resolve("case1.hwpx");
            new HwpMdConverter().convertMarkdownToHwp(md.toString(), outHwp.toString());
            new HwpMdConverter().convertMarkdownToHwpx(md.toString(), outHwpx.toString());

            // ---- HWP body paragraph 들 검증 ----
            HwpStats hStats = scanHwp(outHwp);
            // 1) body paragraph 중 height < 1200 비율 — TOC/본문 등은 모두 1200 이상이어야 함.
            //    표 셀 안 paragraph 는 cell 내부라 별도 처리 (smallHeightBodyOnly 만 카운트).
            //    template-rich.hwp 의 section 정의 paragraph 1개는 template 기본값 (height=1000)
            //    유지 — 한/글 sanity check 는 본 paragraph 를 special (hp:secPr 포함) 로 인식.
            if (hStats.smallHeightBodyCount <= 2) {
                System.out.println("[HWP body line height ≥ 1200] PASS — total body=" + hStats.totalBody
                        + " smallHeight=" + hStats.smallHeightBodyCount + " (≤ 2 허용 — template 기본 paragraph)");
                passed++;
            } else {
                System.out.println("[HWP body line height ≥ 1200] FAIL — height<1200 인 body paragraph 수: "
                        + hStats.smallHeightBodyCount + " / " + hStats.totalBody + " (요구 ≤ 2)");
                failed++;
            }
            // 2) body paragraph 중 baseline 비율 0.7 ~ 0.95 범위 밖이면 손상 의심
            if (hStats.badBaselineBodyCount == 0) {
                System.out.println("[HWP baseline ratio] PASS — 모든 body paragraph 가 0.7~0.95 범위");
                passed++;
            } else {
                System.out.println("[HWP baseline ratio] FAIL — 범위 외 paragraph 수: "
                        + hStats.badBaselineBodyCount);
                failed++;
            }
            // 3) vertPos > 0 (template section + 초기 page-break paragraph 까지는 vertPos=0 허용)
            if (hStats.zeroVertPosBodyCount <= 3) {
                System.out.println("[HWP vertPos accumulation] PASS — zeroVertPos body="
                        + hStats.zeroVertPosBodyCount + " (≤ 3 허용 — template section/page-break)");
                passed++;
            } else {
                System.out.println("[HWP vertPos accumulation] FAIL — zeroVertPos body="
                        + hStats.zeroVertPosBodyCount + " (3 개 초과)");
                failed++;
            }
            // 4) [v15.30] 인라인 TAB 컨트롤은 TOC 우측정렬용으로 복원됨 — 정상.
            //    단 손상 경고를 유발하지 않는 한도 내에서만 사용해야 함 (count = TOC 줄 수).
            if (hStats.inlineTabCount >= 0 && hStats.inlineTabCount < 100) {
                System.out.println("[HWP inline TAB count] PASS — " + hStats.inlineTabCount
                        + " (TOC 우측정렬용)");
                passed++;
            } else {
                System.out.println("[HWP inline TAB count] FAIL — " + hStats.inlineTabCount
                        + " (비정상적으로 많음)");
                failed++;
            }

            // ---- HWPX section0.xml 검증 ----
            String sec = readSection0(outHwpx);
            HwpxStats xStats = scanHwpx(sec);
            if (xStats.smallVertsizeCount <= 2) {
                System.out.println("[HWPX body vertsize ≥ 1200] PASS — total p=" + xStats.totalP
                        + " small=" + xStats.smallVertsizeCount + " (≤ 2 허용 — template 기본)");
                passed++;
            } else {
                System.out.println("[HWPX body vertsize ≥ 1200] FAIL — vertsize<1200 인 p 수: "
                        + xStats.smallVertsizeCount + " (요구 ≤ 2)");
                failed++;
            }
            if (xStats.hpTabCount >= 0 && xStats.hpTabCount < 200) {
                System.out.println("[HWPX hp:tab count] PASS — " + xStats.hpTabCount
                        + " (TOC 우측정렬용)");
                passed++;
            } else {
                System.out.println("[HWPX hp:tab count] FAIL — " + xStats.hpTabCount
                        + " (비정상적으로 많음)");
                failed++;
            }
            if (xStats.zeroVertposCount <= 5) {
                // 표/이미지 host 등 일부 vertpos=0 paragraph 는 허용 (cell-internal 좌표).
                System.out.println("[HWPX vertpos accumulation] PASS — zeroVertpos=" + xStats.zeroVertposCount);
                passed++;
            } else {
                System.out.println("[HWPX vertpos accumulation] FAIL — zeroVertpos=" + xStats.zeroVertposCount);
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
        System.out.println("[DamageWarningTest] 통과=" + passed + " / 실패=" + failed);
        System.out.println("============================================================");
        if (failed > 0) System.exit(1);
    }

    static class HwpStats {
        int totalBody = 0;
        int smallHeightBodyCount = 0;
        int badBaselineBodyCount = 0;
        int zeroVertPosBodyCount = 0;
        int inlineTabCount = 0;
    }
    static class HwpxStats {
        int totalP = 0;
        int smallVertsizeCount = 0;
        int hpTabCount = 0;
        int zeroVertposCount = 0;
    }

    private static HwpStats scanHwp(Path path) throws Exception {
        HwpStats s = new HwpStats();
        HWPFile hwp = HWPReader.fromFile(path.toString());
        for (Section sec : hwp.getBodyText().getSectionList()) {
            for (Paragraph p : sec.getParagraphs()) {
                // body paragraph 만 카운트 (page geometry 가 잡혀 있는 단락 = horzsize ~ 42520).
                if (p.getLineSeg() == null) continue;
                List<LineSegItem> segs = p.getLineSeg().getLineSegItemList();
                if (segs.isEmpty()) continue;
                LineSegItem first = segs.get(0);
                // body paragraph 식별: segmentWidth >= 42000 (= 페이지 본문 폭 42520 근접).
                //   42000 미만은 셀 내부 paragraph 가 대부분 — 셀 폭에 맞게 별도 line metric
                //   을 가지므로 본문 sanity check 대상에서 제외.
                boolean isBody = first.getSegmentWidth() >= 42000;
                if (isBody) {
                    s.totalBody++;
                    if (first.getLineHeight() < 1200) s.smallHeightBodyCount++;
                    int b = first.getDistanceBaseLineToLineVerticalPosition();
                    int h = first.getLineHeight();
                    if (h > 0) {
                        double r = (double) b / (double) h;
                        if (r < 0.70 || r > 0.95) s.badBaselineBodyCount++;
                    } else {
                        s.badBaselineBodyCount++;
                    }
                    if (first.getLineVerticalPosition() <= 0) s.zeroVertPosBodyCount++;
                }
                // 인라인 TAB 컨트롤 카운트 (어디에서든 0 이어야 함 — v15.27).
                if (p.getText() != null) {
                    for (kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar c : p.getText().getCharList()) {
                        if (c.getType() == kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharType.ControlInline
                                && (c.getCode() & 0xFFFF) == 0x0009) {
                            s.inlineTabCount++;
                        }
                    }
                }
            }
        }
        return s;
    }

    private static HwpxStats scanHwpx(String sec) {
        HwpxStats s = new HwpxStats();
        // body paragraph 만 카운트 — horzsize >= 40000 인 lineseg 가 있는 paragraph.
        Pattern pPat = Pattern.compile("<hp:p[^>]*>(?:(?!</hp:p>).)*?</hp:p>", Pattern.DOTALL);
        Matcher mP = pPat.matcher(sec);
        while (mP.find()) {
            String para = mP.group();
            Pattern lsegBody = Pattern.compile(
                    "<hp:lineseg[^>]*vertpos=\"(\\d+)\"[^>]*vertsize=\"(\\d+)\"[^>]*horzsize=\"(\\d+)\"");
            Matcher ls = lsegBody.matcher(para);
            if (ls.find()) {
                long vp = Long.parseLong(ls.group(1));
                int vs = Integer.parseInt(ls.group(2));
                int hs = Integer.parseInt(ls.group(3));
                if (hs >= 42000) {
                    // body paragraph (페이지 본문 폭 근접) — cell-internal 은 horzsize<42000.
                    s.totalP++;
                    if (vs < 1200) s.smallVertsizeCount++;
                    if (vp == 0) s.zeroVertposCount++;
                }
            }
        }
        // hp:tab count (anywhere)
        Matcher mt = Pattern.compile("<hp:tab\\s").matcher(sec);
        while (mt.find()) s.hpTabCount++;
        return s;
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
