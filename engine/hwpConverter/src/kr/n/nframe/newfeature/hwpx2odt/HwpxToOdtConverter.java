package kr.n.nframe.newfeature.hwpx2odt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.reader.HWPXReader;

import kr.n.nframe.newfeature.hwp2odt.Options;
import kr.n.nframe.newfeature.hwp2odt.Result;
import kr.n.nframe.newfeature.odtcommon.OdtBuildContext;
import kr.n.nframe.newfeature.odtcommon.OdtWriter;
import kr.n.nframe.newfeature.odtcommon.OdtZipPackager;

/**
 * HWPX → ODT 단건 직접 변환 진입점(중간 포맷 없음, HWP 우회 없음).
 *
 * <p>HWPXReader 로 객체모델만 로딩 → HwpxTraverser 가 직접 ODT XML 발화.
 * 출력은 OpenDocument 1.2. 출력측(odtcommon)은 hwp2odt 와 공유.
 * CLI: hwpx2odt &lt;입력.hwpx&gt; [&lt;출력.odt | 출력폴더&gt;]
 */
public final class HwpxToOdtConverter {
    private HwpxToOdtConverter() {}

    /** 단건 변환 진입 메서드(배치에서 재사용). */
    public static Result convertOne(Path inHwpx, Path outOdt, Options opts) {
        if (opts == null) opts = Options.defaults();
        outOdt = kr.n.nframe.newfeature.OutputNaming.unique(outOdt); // v16t42 비덮어쓰기
        try {
            HWPXFile hwpx = HWPXReader.fromFilepath(inHwpx.toAbsolutePath().toString());
            OdtBuildContext ctx = new OdtBuildContext();

            String base = inHwpx.getFileName().toString();
            int dot = base.lastIndexOf('.');
            ctx.setTitle(dot > 0 ? base.substring(0, dot) : base);

            Result result = Result.success(inHwpx, outOdt);
            HwpxTraverser traverser = new HwpxTraverser(hwpx, ctx, result);
            traverser.run();

            OdtWriter writer = new OdtWriter(ctx);
            OdtZipPackager.write(outOdt, writer, ctx.pictures, ctx.maths);

            return result;
        } catch (Throwable t) {
            return Result.failure(inHwpx, outOdt, t.toString());
        }
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            printUsage();
            System.exit(2);
            return;
        }

        // 한글 경로 NFC 정규화(자모 분리형 NFD 입력도 합성형으로 통일).
        String inArg = nfc(args[0]);
        Path in = Paths.get(inArg).toAbsolutePath().normalize();
        if (!Files.exists(in)) {
            System.err.println("[오류] 입력 파일을 찾을 수 없습니다: " + in);
            System.exit(1);
            return;
        }
        if (!Files.isRegularFile(in)) {
            System.err.println("[오류] 입력이 일반 파일이 아닙니다(폴더 등): " + in);
            System.exit(1);
            return;
        }
        if (!hasHwpxExtension(in)) {
            System.err.println("[오류] HWPX 파일(.hwpx)이 아닙니다: " + in.getFileName());
            System.exit(1);
            return;
        }

        String stem = stemOf(in.getFileName().toString());

        Path out;
        if (args.length == 2) {
            String outArgStr = nfc(args[1]);
            Path outArg = Paths.get(outArgStr).toAbsolutePath().normalize();
            if (Files.isDirectory(outArg) || isDirectoryLike(outArgStr)) {
                out = outArg.resolve(stem + ".odt");
            } else {
                out = outArg;
            }
        } else {
            Path parent = in.getParent();
            out = (parent == null ? Paths.get(stem + ".odt") : parent.resolve(stem + ".odt"));
        }

        Path outDir = out.getParent();
        if (outDir != null && !Files.exists(outDir)) {
            try {
                Files.createDirectories(outDir);
            } catch (Exception e) {
                System.err.println("[오류] 출력 폴더를 만들 수 없습니다: " + outDir + " (" + e.getMessage() + ")");
                System.exit(1);
                return;
            }
        }
        if (outDir != null && !Files.isWritable(outDir)) {
            System.err.println("[오류] 출력 폴더에 쓰기 권한이 없습니다: " + outDir);
            System.exit(1);
            return;
        }

        Options opts = Options.defaults();
        opts.verbose = true;
        Result r = convertOne(in, out, opts);
        System.out.println(r);
        System.exit(r.ok ? 0 : 1);
    }

    private static void printUsage() {
        System.err.println("사용법: hwpx2odt <입력.hwpx> [<출력.odt | 출력폴더>]");
        System.err.println("  · 인자 1개: 입력과 같은 폴더에 <원본명>.odt 생성");
        System.err.println("  · 인자 2개: 출력이 폴더면 그 안에 <원본명>.odt, 파일(.odt)이면 그대로 사용");
    }

    /** 입력 경로 문자열을 유니코드 NFC 로 정규화(한글 NFD 분리형 → 합성형). */
    private static String nfc(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC);
    }

    private static boolean hasHwpxExtension(Path p) {
        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".hwpx");
    }

    private static String stemOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean isDirectoryLike(String arg) {
        if (arg.endsWith("/") || arg.endsWith("\\")) return true;
        String last = Paths.get(arg).getFileName().toString();
        return last.lastIndexOf('.') <= 0;
    }
}
