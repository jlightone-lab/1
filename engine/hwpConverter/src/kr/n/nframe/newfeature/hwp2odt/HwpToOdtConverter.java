package kr.n.nframe.newfeature.hwp2odt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;

import kr.n.nframe.newfeature.odtcommon.OdtBuildContext;
import kr.n.nframe.newfeature.odtcommon.OdtWriter;
import kr.n.nframe.newfeature.odtcommon.OdtZipPackager;

/**
 * HWP → ODT 단건 직접 변환 진입점(B안: 중간 포맷 없음).
 *
 * <p>HWPReader 는 객체모델 로딩 전용(Hwp2Hwpx 호출 안 함). 출력은 OpenDocument 1.2.
 * CLI: hwp2odt.bat &lt;입력.hwp&gt; [&lt;출력.odt&gt;]
 */
public final class HwpToOdtConverter {
    private HwpToOdtConverter() {}

    /** 단건 변환 진입 메서드(task3/4 배치에서 재사용). */
    public static Result convertOne(Path inHwp, Path outOdt, Options opts) {
        if (opts == null) opts = Options.defaults();
        outOdt = kr.n.nframe.newfeature.OutputNaming.unique(outOdt); // v16t42 비덮어쓰기
        try {
            HWPFile hwp = HWPReader.fromFile(inHwp.toAbsolutePath().toString());
            OdtBuildContext ctx = new OdtBuildContext();

            String base = inHwp.getFileName().toString();
            int dot = base.lastIndexOf('.');
            ctx.setTitle(dot > 0 ? base.substring(0, dot) : base);

            Result result = Result.success(inHwp, outOdt);
            HwpDocumentTraverser traverser = new HwpDocumentTraverser(hwp, ctx, result);
            traverser.run();

            OdtWriter writer = new OdtWriter(ctx);
            OdtZipPackager.write(outOdt, writer, ctx.pictures, ctx.maths);

            return result;
        } catch (Throwable t) {
            return Result.failure(inHwp, outOdt, t.toString());
        }
    }

    public static void main(String[] args) {
        // 인자 개수 분기: 1개(입력만) / 2개(입력+출력). 그 외는 사용법.
        if (args.length < 1 || args.length > 2) {
            printUsage();
            System.exit(2);
            return;
        }

        // 입력 경로 정규화(절대/상대·한국어 경로 모두 허용)
        Path in = Paths.get(args[0]).toAbsolutePath().normalize();
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
        if (!hasHwpExtension(in)) {
            System.err.println("[오류] HWP 파일(.hwp)이 아닙니다: " + in.getFileName());
            System.exit(1);
            return;
        }

        String stem = stemOf(in.getFileName().toString());

        // 출력 경로 결정
        Path out;
        if (args.length == 2) {
            Path outArg = Paths.get(args[1]).toAbsolutePath().normalize();
            if (Files.isDirectory(outArg) || isDirectoryLike(args[1])) {
                out = outArg.resolve(stem + ".odt");   // 폴더 지정 → 그 안에 <원본명>.odt
            } else {
                out = outArg;                          // 파일 지정 → 그대로
            }
        } else {
            Path parent = in.getParent();
            out = (parent == null ? Paths.get(stem + ".odt") : parent.resolve(stem + ".odt"));
        }

        // 출력 폴더 보장(없으면 생성). 권한/생성 실패는 한국어 안내.
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
        System.err.println("사용법: hwp2odt <입력.hwp> [<출력.odt | 출력폴더>]");
        System.err.println("  · 인자 1개: 입력과 같은 폴더에 <원본명>.odt 생성");
        System.err.println("  · 인자 2개: 출력이 폴더면 그 안에 <원본명>.odt, 파일(.odt)이면 그대로 사용");
    }

    private static boolean hasHwpExtension(Path p) {
        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".hwp");
    }

    private static String stemOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 존재하지 않는 출력 인자를 폴더로 볼지 판단: 구분자로 끝나거나 확장자가 없으면 폴더로 간주. */
    private static boolean isDirectoryLike(String arg) {
        if (arg.endsWith("/") || arg.endsWith("\\")) return true;
        String last = Paths.get(arg).getFileName().toString();
        return last.lastIndexOf('.') <= 0; // 확장자 없음 → 폴더로 간주
    }
}
