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

import kr.n.nframe.HwpMdConverter;

/**
 * v15.14 회귀 검증 — task1/2/3/4 통합.
 *
 * <ul>
 *   <li><b>task1/3 (HR 폭)</b> : 입찰공고문.md 의 110-dash HR 가 HWP/HWPX 변환 후
 *       페이지 본문 폭(41954 hwpunit) 안에 한 줄로 들어가는지 확인.</li>
 *   <li><b>task2/4 (TOC 점선)</b> : case1.md 의 목차 점선 leader 가 페이지 본문 폭에
 *       맞도록 재계산되어, 각 줄의 시각 폭이 PAGE_INNER_WIDTH 의 작은 오차 이내에 들어가
 *       페이지 번호가 우측 정렬되어 보이는지 확인.</li>
 * </ul>
 *
 * <p>HWPX 의 Contents/section0.xml 을 직접 파싱해서 {@code -{5,}} 의 최대 길이와
 *  TOC dot-leader 라인의 시각 폭을 측정한다. HWP 는 binary OLE 라 직접 파싱이 비효율적
 *  이므로 동일 path 로 처리되는 HWPX 산출물을 검증 대상으로 한다 (MdStructureConverter
 *  의 HWPX 경로가 MdToHwpRich → injectAsHwpx 라운드트립이므로 동일 텍스트 결과).
 */
public class HrTocAlignmentTest {
    /** [v15.18] template-rich.hwp 의 실제 페이지 본문 폭 (= 59528 - 8504×2 = 42520).
     *  MdRichTemplateMutator.TOC_PAGE_INNER_WIDTH 와 동기화. */
    private static final int PAGE_INNER_WIDTH = 42520;
    /** HR 한 줄 최대 char 수 (page * 0.85 / 450). MdRichParser.normalizeHr 의 결과 길이와 일치해야 함. */
    private static final int HR_MAX_CHARS = (PAGE_INNER_WIDTH * 85 / 100) / 450;  // 79
    /** TOC 라인 시각 폭이 페이지 본문 폭과 일치한다고 판단할 허용 오차 (hwpunit).
     *  [v15.30] inline TAB width 는 emit 시 정확히 계산되지만 hwp2hwpx roundtrip 의
     *  XML 직렬화 시 일부 폰트별 advance 차이로 ± 1000 hwpunit 잔차 허용. */
    private static final int TOC_VISUAL_TOLERANCE = 1000;

    public static void main(String[] args) throws Exception {
        int passed = 0, failed = 0;

        Path projRoot = Paths.get(System.getProperty("user.dir"));
        Path mdHr  = projRoot.resolve("hwp/0515(추가개발건 예정)/dummy_file/07.다건hwp_md/입찰공고문.md");
        Path mdToc = projRoot.resolve("hwp/0515(추가개발건 예정)/dummy_file/07.다건hwp_md/case1.md");
        if (args.length >= 1) mdHr  = Paths.get(args[0]);
        if (args.length >= 2) mdToc = Paths.get(args[1]);

        Path tmpDir = Files.createTempDirectory("hr_toc_test_");
        try {
            Path outHrHwpx  = tmpDir.resolve("입찰공고문.hwpx");
            Path outTocHwpx = tmpDir.resolve("case1.hwpx");
            new HwpMdConverter().convertMarkdownToHwpx(mdHr.toString(),  outHrHwpx.toString());
            new HwpMdConverter().convertMarkdownToHwpx(mdToc.toString(), outTocHwpx.toString());

            // ---- task1/3 검증: HR dash 길이 ----
            String hrSec = readSection0(outHrHwpx);
            int maxDash = maxRunLength(hrSec, '-');
            if (maxDash <= HR_MAX_CHARS && maxDash >= 70) {
                System.out.println("[HR width] PASS — max dash run = " + maxDash
                        + " (limit " + HR_MAX_CHARS + ")");
                passed++;
            } else {
                System.out.println("[HR width] FAIL — max dash run = " + maxDash
                        + " expected <= " + HR_MAX_CHARS + " (and >= 70 to confirm normalize applied)");
                failed++;
            }

            // ---- task2/4 검증: TOC 점선 라인 우측 정렬 (v15.30: inline TAB 복원) ----
            // [v15.30] TOC 라인은 다시 inline TAB 컨트롤 사용 :
            //   <hp:t>라벨 </hp:t><hp:tab width="N" leader="3" type="2"/><hp:t> 36</hp:t>
            //   검증 : 페이지 본문 폭 ± 1000 hwpunit 안에 라벨 + tab width + num 합치 시
            //   page width 와 근접.
            String tocSec = readSection0(outTocHwpx);
            int tocChecked = 0, tocOk = 0;
            int worstDelta = Integer.MIN_VALUE;
            // [v15.32] <hp:t> 안에 라벨 + literal "·" 5+ + 페이지번호 패턴.
            //  각 TOC 라인의 시각 폭이 페이지 본문 폭에 근접 (±900 hwpunit).
            Pattern tDot = Pattern.compile(
                    "<hp:t>([^<]*?·{5,}[^<]*?\\d+)</hp:t>");
            Matcher mt = tDot.matcher(tocSec);
            while (mt.find()) {
                String text = decodeXmlEntities(mt.group(1));
                if (!text.trim().matches(".*\\d+$")) continue;
                int total = visualWidth(text);
                int delta = total - PAGE_INNER_WIDTH;
                tocChecked++;
                if (Math.abs(delta) <= TOC_VISUAL_TOLERANCE) tocOk++;
                if (Math.abs(delta) > Math.abs(worstDelta)) worstDelta = delta;
            }
            if (tocChecked >= 5 && tocOk == tocChecked) {
                System.out.println("[TOC dots] PASS — " + tocOk + "/" + tocChecked
                        + " TOC lines within ±" + TOC_VISUAL_TOLERANCE + " hwpunit of page width "
                        + PAGE_INNER_WIDTH + " (worst delta " + worstDelta + ")");
                passed++;
            } else {
                System.out.println("[TOC dots] FAIL — " + tocOk + "/" + tocChecked
                        + " TOC lines aligned (worst delta " + worstDelta + " hwpunit)");
                failed++;
            }
        } finally {
            try {
                Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            } catch (Exception ignored) { }
        }

        System.out.println();
        System.out.println("============================================================");
        System.out.println("[HrTocAlignmentTest] 결과: 통과=" + passed + ", 실패=" + failed);
        System.out.println("============================================================");
        if (failed > 0) System.exit(1);
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

    private static int maxRunLength(String s, char c) {
        int max = 0, cur = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) { cur++; if (cur > max) max = cur; }
            else cur = 0;
        }
        return max;
    }

    private static int visualWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean full = (ch >= 0xAC00 && ch <= 0xD7A3)
                    || (ch >= 0x4E00 && ch <= 0x9FFF)
                    || (ch >= 0x3000 && ch <= 0x303F)
                    || (ch >= 0xFF00 && ch <= 0xFFEF)
                    || (ch >= 0x2000 && ch <= 0x27BF)
                    || (ch >= 0x3200 && ch <= 0x33FF);
            w += full ? 900 : 450;
        }
        return w;
    }

    private static String decodeXmlEntities(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&apos;", "'");
    }
}
