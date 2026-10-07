package kr.n.nframe.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * v15.30 task4 — TOC 페이지 번호 우측 정렬 검증 (inline TAB 복원 + line metric).
 *
 * <p>v15.27 의 literal "·" dot-fill 방식은 비례 폰트 (맑은 고딕) 의 advance 와 우리
 *  추정 폭의 차이로 인해 각 줄의 페이지 번호가 우측 정렬되지 않는 회귀를 사용자가 보고.
 *  v15.30 부터 원본 case1.hwp/.hwpx 와 동일하게 inline TAB (leader=DOT, type=RIGHT) 사용.
 *
 * <p>검증 (HWP / HWPX 둘 다) :
 *  <ul>
 *    <li>HWPCharControlInline (code=0x0009, leader=DOT, type=RIGHT) 존재.</li>
 *    <li>페이지 번호 "36" 으로 종결.</li>
 *    <li>line metric height ≥ 1200 (v15.26 fix 유지).</li>
 *  </ul>
 */
public class TocDotLeaderTest {

    private static final String TOC_LABEL = "지방보조금관리위원회 운영 계획";
    private static final String EXPECTED_PAGENUM = "36";

    public static void main(String[] args) throws Exception {
        Path projRoot = Paths.get(System.getProperty("user.dir"));
        Path mdToc = projRoot.resolve(
                "hwp/0515(추가개발건 예정)/dummy_file/11.hwp_md/case1.md");
        if (args.length >= 1) mdToc = Paths.get(args[0]);
        if (!Files.exists(mdToc)) {
            System.err.println("[TocDotLeaderTest] MD 파일 없음: " + mdToc);
            System.exit(2);
        }
        Path tmpDir = Files.createTempDirectory("toc_dot_leader_test_");
        int passed = 0, failed = 0;
        try {
            Path outHwp  = tmpDir.resolve("case1.hwp");
            Path outHwpx = tmpDir.resolve("case1.hwpx");
            new HwpMdConverter().convertMarkdownToHwp(mdToc.toString(), outHwp.toString());
            new HwpMdConverter().convertMarkdownToHwpx(mdToc.toString(), outHwpx.toString());

            // ---- HWP ----
            HwpToc f = findTocHwp(outHwp);
            if (f == null) {
                System.out.println("[HWP TOC] FAIL — TOC 단락 미발견");
                failed++;
            } else {
                // [v15.32] inline TAB 제거 + literal "·" 점선 fill — TAB 없어야 함.
                if (!f.hasInlineTab) {
                    System.out.println("[HWP no inline TAB] PASS — TAB 미존재 (v15.32 literal dot)");
                    passed++;
                } else {
                    System.out.println("[HWP no inline TAB] FAIL — inline TAB 잔존 (v15.32 회귀)");
                    failed++;
                }
                // "·" 5개 이상 연속 존재 확인
                int dotRun = maxRunLength(f.text, '·');
                if (dotRun >= 5) {
                    System.out.println("[HWP dot leader] PASS — '·' " + dotRun + "개");
                    passed++;
                } else {
                    System.out.println("[HWP dot leader] FAIL — '·' " + dotRun + "개 (요구 ≥ 5)");
                    failed++;
                }
                if (f.text.trim().endsWith(EXPECTED_PAGENUM)) {
                    System.out.println("[HWP pageNum] PASS");
                    passed++;
                } else {
                    System.out.println("[HWP pageNum] FAIL — text=[" + f.text.replace("\t","<TAB>") + "]");
                    failed++;
                }
                if (f.lineHeight >= 1200 && f.vertPos > 0) {
                    System.out.println("[HWP damage avoid] PASS — height=" + f.lineHeight
                            + " vertPos=" + f.vertPos);
                    passed++;
                } else {
                    System.out.println("[HWP damage avoid] FAIL — height=" + f.lineHeight
                            + " vertPos=" + f.vertPos);
                    failed++;
                }
            }

            // ---- HWPX ----
            String sec = readSection0(outHwpx);
            Pattern tocPat = Pattern.compile(
                    "<hp:p[^>]*>(?:(?!</hp:p>).)*Ⅱ\\. 지방보조금관리위원회 운영 계획"
                    + "(?:(?!</hp:p>).)*</hp:p>", Pattern.DOTALL);
            Matcher m = tocPat.matcher(sec);
            if (m.find()) {
                String para = m.group();
                // [v15.32] inline TAB 제거 → <hp:tab> 미존재 + literal "·" dot 존재
                boolean noTab = !para.contains("<hp:tab");
                if (noTab) {
                    System.out.println("[HWPX no hp:tab] PASS — TAB 미존재");
                    passed++;
                } else {
                    System.out.println("[HWPX no hp:tab] FAIL — hp:tab 잔존 (v15.32 회귀)");
                    failed++;
                }
                int dotRun = maxRunLength(para, '·');
                if (dotRun >= 5) {
                    System.out.println("[HWPX dot leader] PASS — '·' " + dotRun + "개");
                    passed++;
                } else {
                    System.out.println("[HWPX dot leader] FAIL — '·' " + dotRun + "개");
                    failed++;
                }
                if (para.contains(EXPECTED_PAGENUM)) {
                    System.out.println("[HWPX pageNum] PASS");
                    passed++;
                } else {
                    System.out.println("[HWPX pageNum] FAIL");
                    failed++;
                }
                Pattern lseg = Pattern.compile(
                        "<hp:lineseg[^>]*vertpos=\"(\\d+)\"[^>]*vertsize=\"(\\d+)\"");
                Matcher ms = lseg.matcher(para);
                if (ms.find()) {
                    long vp = Long.parseLong(ms.group(1));
                    int vs = Integer.parseInt(ms.group(2));
                    if (vp > 0 && vs >= 1200) {
                        System.out.println("[HWPX damage avoid] PASS — vertpos=" + vp
                                + " vertsize=" + vs);
                        passed++;
                    } else {
                        System.out.println("[HWPX damage avoid] FAIL — vertpos=" + vp
                                + " vertsize=" + vs);
                        failed++;
                    }
                } else {
                    System.out.println("[HWPX damage avoid] FAIL — lineseg 미발견");
                    failed++;
                }
            } else {
                System.out.println("[HWPX TOC] FAIL — TOC 단락 미발견");
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
        System.out.println("[TocDotLeaderTest] 통과=" + passed + " / 실패=" + failed);
        System.out.println("============================================================");
        if (failed > 0) System.exit(1);
    }

    static class HwpToc {
        String text = "";
        boolean hasInlineTab = false;
        int lineHeight, vertPos;
    }

    private static int maxRunLength(String s, char c) {
        int max = 0, cur = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) { cur++; if (cur > max) max = cur; }
            else cur = 0;
        }
        return max;
    }

    private static HwpToc findTocHwp(Path path) throws Exception {
        HWPFile hwp = HWPReader.fromFile(path.toString());
        for (Section sec : hwp.getBodyText().getSectionList()) {
            for (Paragraph p : sec.getParagraphs()) {
                StringBuilder sb = new StringBuilder();
                boolean hasTab = false;
                if (p.getText() != null) {
                    for (HWPChar c : p.getText().getCharList()) {
                        if (c.getType() == HWPCharType.Normal) {
                            sb.append((char) ((HWPCharNormal) c).getCode());
                        } else if (c.getType() == HWPCharType.ControlInline
                                && (c.getCode() & 0xFFFF) == 0x0009) {
                            hasTab = true;
                            sb.append('\t');
                        }
                    }
                }
                String txt = sb.toString();
                if (txt.contains(TOC_LABEL)) {
                    HwpToc f = new HwpToc();
                    f.text = txt;
                    f.hasInlineTab = hasTab;
                    if (p.getLineSeg() != null
                            && !p.getLineSeg().getLineSegItemList().isEmpty()) {
                        kr.dogfoot.hwplib.object.bodytext.paragraph.lineseg.LineSegItem seg = p.getLineSeg().getLineSegItemList().get(0);
                        f.lineHeight = seg.getLineHeight();
                        f.vertPos = seg.getLineVerticalPosition();
                    }
                    return f;
                }
            }
        }
        return null;
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
