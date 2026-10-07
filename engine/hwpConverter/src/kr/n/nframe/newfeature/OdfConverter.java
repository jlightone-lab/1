package kr.n.nframe.newfeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import kr.n.nframe.HwpConverter;
import kr.n.nframe.HwpMdConverter;
import kr.n.nframe.OdtPostHwpEnhancer;
// v16t41 결정: 마크다운 ↔ ODT 기능 비활성(odt→md 미사용). OdtToMdConverter 호출경로 없음 — import 주석처리.
// import kr.n.nframe.OdtToMdConverter;

/**
 * v15.29-newfeature : ODF 양방향 변환 CLI.
 *
 * <p>v14.98 의 ODT → HWP/HWPX 단방향 기능을 그대로 활용하면서,
 * 누락되어 있던 역방향(HWP/HWPX → ODT) 을 본 newfeature 패키지에서
 * 신규로 추가한다.
 *
 * <p>구현 전략 (B 옵션 1 — Markdown 중간표현 경유):
 * <pre>
 *   .odt  → .hwp / .hwpx
 *      OdtToMdConverter → temp_newfeature/*.md → HwpMdConverter.convertMarkdownToHwp(x)
 *      → OdtPostHwpEnhancer (어두운 셀 흰글자, 머리/꼬리말 인젝션)
 *
 *   .hwp / .hwpx → .odt
 *      HwpMdConverter.convertHwp(x)ToMarkdown → temp_newfeature/*.md
 *      → MdToOdtWriter (신규)
 * </pre>
 */
public class OdfConverter {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            printUsage();
            System.exit(args.length == 0 ? 0 : 2);
            return;
        }
        String input  = args[0].trim();
        String output = args[1].trim();

        File in = new File(input);
        if (!in.exists() || !in.isFile()) {
            System.err.println("[오류] 입력 파일을 찾을 수 없습니다: " + input);
            System.exit(2); return;
        }

        String inExt  = ext(input);
        String outExt = ext(output);

        OdfConverter c = new OdfConverter();
        try {
            switch (inExt + "->" + outExt) {
                case "odt->hwp":   c.convertOdtToHwp(input, output);   break;
                case "odt->hwpx":  c.convertOdtToHwpx(input, output);  break;
                case "hwp->odt":   c.convertHwpToOdt(input, output);   break;
                case "hwpx->odt":  c.convertHwpxToOdt(input, output);  break;
                default:
                    System.err.println("[오류] 지원하지 않는 변환 방향: ." + inExt + " → ." + outExt);
                    System.err.println("       지원: .odt ↔ .hwp, .odt ↔ .hwpx");
                    System.exit(2); return;
            }
        } catch (Exception e) {
            System.err.println("[오류] 변환 실패: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
        System.out.println("[OdfConverter] 변환 완료: " + new File(output).getAbsolutePath());
    }

    // =========================================================
    //  공개 API — ODT → HWP / HWPX
    // =========================================================

    /**
     * ODT → HWP : v16.00 부터 MD 미경유 직접변환({@link OdtDirectConverter}) 으로 위임.
     *   기존 외부 API 시그니처는 유지하되, 내부 흐름에서 임시 .md 를 만들지 않는다.
     *   사용자 요구 "odf > md 변환 기능 제거" 충족.
     */
    public void convertOdtToHwp(String odtPath, String hwpPath) throws Exception {
        System.out.println("[OdfConverter] ODT → HWP (직접변환, MD 미경유): " + hwpPath);
        new OdtDirectConverter().convertOdtToHwp(odtPath, hwpPath);
    }

    /**
     * ODT → HWPX : v16.00 부터 MD 미경유 직접변환({@link OdtDirectConverter}) 으로 위임.
     *   기존 외부 API 시그니처는 유지하되, 내부 흐름에서 임시 .md 를 만들지 않는다.
     */
    public void convertOdtToHwpx(String odtPath, String hwpxPath) throws Exception {
        System.out.println("[OdfConverter] ODT → HWPX (직접변환, MD 미경유): " + hwpxPath);
        new OdtDirectConverter().convertOdtToHwpx(odtPath, hwpxPath);
    }

    // =========================================================
    //  공개 API — HWP / HWPX → ODT  (신규)
    // =========================================================

    /** HWP → ODT : v16t44 부터 MD 미경유 직접변환(hwp2odt)으로 위임. */
    public void convertHwpToOdt(String hwpPath, String odtPath) throws Exception {
        odtPath = OutputNaming.unique(odtPath); // v16t42 비덮어쓰기
        System.out.println("[OdfConverter] HWP → ODT (직접변환, MD 미경유): " + odtPath);
        kr.n.nframe.newfeature.hwp2odt.Result r =
                kr.n.nframe.newfeature.hwp2odt.HwpToOdtConverter.convertOne(
                        java.nio.file.Paths.get(hwpPath), java.nio.file.Paths.get(odtPath), null);
        if (!r.ok) throw new RuntimeException(r.message);
    }

    /** HWPX → ODT : v16t44 부터 MD 미경유 직접변환(hwpx2odt)으로 위임. */
    public void convertHwpxToOdt(String hwpxPath, String odtPath) throws Exception {
        odtPath = OutputNaming.unique(odtPath); // v16t42 비덮어쓰기
        System.out.println("[OdfConverter] HWPX → ODT (직접변환, MD 미경유): " + odtPath);
        kr.n.nframe.newfeature.hwp2odt.Result r =
                kr.n.nframe.newfeature.hwpx2odt.HwpxToOdtConverter.convertOne(
                        java.nio.file.Paths.get(hwpxPath), java.nio.file.Paths.get(odtPath), null);
        if (!r.ok) throw new RuntimeException(r.message);
    }

    // =========================================================
    //  공개 API — 폴더 배치 (v15.35-newfeature)
    // =========================================================

    /**
     * 폴더 배치 : {@code srcDir} 안의 모든 .odt 를 {@code dstDir} 의
     *   .hwp 또는 .hwpx ({@code targetExt} 로 결정) 로 변환.
     */
    public void batchOdtToHwp(String srcDir, String dstDir, String targetExt) throws Exception {
        File src = new File(srcDir);
        if (!src.isDirectory()) {
            throw new IllegalArgumentException("입력 디렉토리가 아닙니다: " + srcDir);
        }
        File dst = new File(dstDir);
        if (!dst.exists() && !dst.mkdirs()) {
            throw new IOException("출력 디렉토리 생성 실패: " + dstDir);
        }
        String tgt = targetExt == null ? "" : targetExt.toLowerCase(Locale.ROOT);
        if (tgt.startsWith(".")) tgt = tgt.substring(1);
        if (!"hwp".equals(tgt) && !"hwpx".equals(tgt)) {
            throw new IllegalArgumentException("targetExt 는 'hwp' 또는 'hwpx' 만 가능: " + targetExt);
        }
        File[] odts = src.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".odt"));
        if (odts == null || odts.length == 0) {
            System.out.println("[OdfBatch] .odt 파일 없음 : " + src.getAbsolutePath());
            return;
        }
        int ok = 0, fail = 0;
        System.out.println("[OdfBatch] ODT → " + tgt.toUpperCase(Locale.ROOT)
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
                System.err.println("[OdfBatch] 실패: " + f.getName() + " → " + e.getMessage());
            }
        }
        System.out.println("[OdfBatch] 완료 — 성공=" + ok + " 실패=" + fail
                + " → " + dst.getAbsolutePath());
    }

    /**
     * 폴더 배치 : {@code srcDir} 안의 모든 .hwp / .hwpx 를 {@code dstDir} 의 .odt 로 변환.
     */
    public void batchAnyToOdt(String srcDir, String dstDir) throws Exception {
        File src = new File(srcDir);
        if (!src.isDirectory()) {
            throw new IllegalArgumentException("입력 디렉토리가 아닙니다: " + srcDir);
        }
        File dst = new File(dstDir);
        if (!dst.exists() && !dst.mkdirs()) {
            throw new IOException("출력 디렉토리 생성 실패: " + dstDir);
        }
        File[] files = src.listFiles((d, n) -> {
            String lo = n.toLowerCase(Locale.ROOT);
            return lo.endsWith(".hwp") || lo.endsWith(".hwpx");
        });
        if (files == null || files.length == 0) {
            System.out.println("[OdfBatch] .hwp / .hwpx 파일 없음 : " + src.getAbsolutePath());
            return;
        }
        int ok = 0, fail = 0;
        System.out.println("[OdfBatch] HWP/HWPX → ODT : " + files.length
                + " files in " + src.getAbsolutePath());
        for (File f : files) {
            String name = f.getName();
            String base = name.replaceFirst("\\.[^.]+$", "");
            String outPath = new File(dst, base + ".odt").getAbsolutePath();
            try {
                if (name.toLowerCase(Locale.ROOT).endsWith(".hwp")) {
                    convertHwpToOdt(f.getAbsolutePath(), outPath);
                } else {
                    convertHwpxToOdt(f.getAbsolutePath(), outPath);
                }
                ok++;
            } catch (Exception e) {
                fail++;
                System.err.println("[OdfBatch] 실패: " + f.getName() + " → " + e.getMessage());
            }
        }
        System.out.println("[OdfBatch] 완료 — 성공=" + ok + " 실패=" + fail
                + " → " + dst.getAbsolutePath());
    }

    // =========================================================
    //  유틸
    // =========================================================

    /** 확장자 (소문자, 점 없음). 확장자가 없으면 빈 문자열. */
    private static String ext(String path) {
        int i = path.lastIndexOf('.');
        if (i < 0 || i == path.length() - 1) return "";
        return path.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static void printUsage() {
        System.out.println();
        System.out.println("  OdfConverter — ODF 양방향 변환 (v15.29-newfeature)");
        System.out.println();
        System.out.println("  사용법:");
        System.out.println("    java -cp odfConverter.jar;lib\\* kr.n.nframe.newfeature.OdfConverter <input> <output>");
        System.out.println();
        System.out.println("  지원 변환 방향 (확장자 기준 자동 판별):");
        System.out.println("    .odt  → .hwp     ODT → HWP   (v14.98 기능)");
        System.out.println("    .odt  → .hwpx    ODT → HWPX  (v14.98 기능)");
        System.out.println("    .hwp  → .odt     HWP → ODT   (신규)");
        System.out.println("    .hwpx → .odt     HWPX → ODT  (신규)");
        System.out.println();
        System.out.println("  예시:");
        System.out.println("    OdfConverter.bat \"sample.odt\"  \"sample.hwp\"");
        System.out.println("    OdfConverter.bat \"sample.hwpx\" \"sample.odt\"");
        System.out.println();
    }
    /** 단위테스트/스크립트가 호출할 수 있도록 main 과 동일한 라우팅을 메서드로도 노출. */
    public void convert(String input, String output) throws Exception {
        String inExt  = ext(input);
        String outExt = ext(output);
        switch (inExt + "->" + outExt) {
            case "odt->hwp":   convertOdtToHwp(input, output);   break;
            case "odt->hwpx":  convertOdtToHwpx(input, output);  break;
            case "hwp->odt":   convertHwpToOdt(input, output);   break;
            case "hwpx->odt":  convertHwpxToOdt(input, output);  break;
            default:
                throw new IllegalArgumentException("지원하지 않는 변환 방향: ." + inExt + " → ." + outExt);
        }
    }
}
