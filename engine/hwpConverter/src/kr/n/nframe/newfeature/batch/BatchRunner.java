package kr.n.nframe.newfeature.batch;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import kr.n.nframe.newfeature.hwp2odt.Options;
import kr.n.nframe.newfeature.hwp2odt.Result;

/**
 * 폴더 안 HWP/HWPX 를 일괄 ODT 변환(task3/4 공용).
 *
 * <p>CLI: {@code BatchRunner <입력폴더> <출력폴더> --to-odt [--glob "*.hwp"] [--threads N]}
 * <ul>
 *   <li>비재귀 열거 + 이름 정렬, 사이드카(.origin.*) 제외, .hwp/.hwpx 만 대상.</li>
 *   <li>실패 격리: 한 파일 실패가 다음 파일 변환을 막지 않음(CSV status=FAIL + 에러요약).</li>
 *   <li>진행 표시: {@code [i/n] 원본명 → 출력명 [OK] (NNNms)} / {@code [실패] 에러} / {@code [건너뜀]}.</li>
 *   <li>리포트: 출력폴더에 {@code 변환결과_YYYYMMDD_HHmmss.csv}(17컬럼, UTF-8 BOM).</li>
 *   <li>종료코드: 0 전체성공 · 2 부분실패(일부 FAIL) · 1 치명(인자/입력폴더 오류).</li>
 * </ul>
 * threads 기본 1(순차). >1 이면 파일 단위 병렬(각 변환은 독립 컨텍스트라 안전).
 * 엔진(hwp2odt/hwpx2odt/odtcommon)·v16t23 베이스라인 무수정 — 본 패키지(batch)만 신규.
 */
public final class BatchRunner {
    private BatchRunner() {}

    private static final PrintStream OUT = utf8PrintStream(System.out);
    private static final PrintStream ERR = utf8PrintStream(System.err);

    /** Java8 호환: PrintStream(OutputStream,boolean,Charset) 은 Java10+ 이라 charsetName 오버로드 사용.
     *  "UTF-8" 은 항상 지원되므로 UnsupportedEncodingException 은 실제로 발생하지 않는다. */
    private static PrintStream utf8PrintStream(java.io.OutputStream os) {
        try {
            return new PrintStream(os, true, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (FatalException fe) {
            ERR.println("[오류] " + fe.getMessage());
            code = 1;
        } catch (Throwable t) {
            ERR.println("[오류] 예기치 못한 실패: " + t);
            code = 1;
        }
        System.exit(code);
    }

    /** 종료코드 반환(테스트에서 직접 호출 가능). */
    public static int run(String[] args) throws Exception {
        if (args.length < 2) {
            printUsage();
            return 1;
        }
        Path inDir = Paths.get(nfc(args[0])).toAbsolutePath().normalize();
        Path outDir = Paths.get(nfc(args[1])).toAbsolutePath().normalize();

        String glob = null;
        int threads = 1;
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--to-odt")) {
                // 출력 방향(현재 ODT 단일). 호환을 위해 수용.
            } else if (a.equals("--glob")) {
                if (i + 1 < args.length) glob = stripQuotes(args[++i]);
            } else if (a.startsWith("--glob=")) {
                glob = stripQuotes(a.substring("--glob=".length()));
            } else if (a.equals("--threads")) {
                if (i + 1 < args.length) threads = parseThreads(args[++i]);
            } else if (a.startsWith("--threads=")) {
                threads = parseThreads(a.substring("--threads=".length()));
            }
            // 알 수 없는 옵션은 무시(상위 호환)
        }

        if (!Files.exists(inDir)) throw new FatalException("입력 폴더를 찾을 수 없습니다: " + inDir);
        if (!Files.isDirectory(inDir)) throw new FatalException("입력이 폴더가 아닙니다: " + inDir);
        if (!Files.exists(outDir)) {
            try { Files.createDirectories(outDir); }
            catch (Exception e) { throw new FatalException("출력 폴더를 만들 수 없습니다: " + outDir + " (" + e.getMessage() + ")"); }
        }
        if (!Files.isWritable(outDir)) throw new FatalException("출력 폴더에 쓰기 권한이 없습니다: " + outDir);

        List<Path> targets = enumerate(inDir, glob);
        BatchReport report = new BatchReport();
        final int total = targets.size();

        if (total == 0) {
            if (glob != null && !glob.isEmpty()) {
                OUT.println("[안내] 매칭되는 파일 0개. 글로브 패턴: \"" + glob + "\"");
                OUT.println("       · 폴더 안에 .hwp/.hwpx 가 있는지, 패턴이 맞는지 확인하세요.");
                OUT.println("       · 시도: --glob *.hwp  또는  --glob *.hwpx  (또는 --glob 생략 시 둘 다 대상)");
            } else {
                OUT.println("[안내] 변환 대상(.hwp/.hwpx)이 없습니다: " + inDir);
            }
            Path csv = report.write(outDir);
            OUT.println("[리포트] " + csv);
            return 0;
        }

        OUT.println("[시작] 대상 " + total + "개 · 입력 " + inDir + " · 출력 " + outDir
                + (threads > 1 ? " · 스레드 " + threads : ""));

        AtomicInteger seq = new AtomicInteger(0);
        if (threads <= 1) {
            for (Path in : targets) {
                BatchReport.Row row = convert(in, outDir, seq.incrementAndGet(), total);
                report.add(row);
            }
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<BatchReport.Row>> futures = new ArrayList<>(total);
                for (Path in : targets) {
                    final Path f = in;
                    futures.add(pool.submit((Callable<BatchReport.Row>) () ->
                            convert(f, outDir, seq.incrementAndGet(), total)));
                }
                for (Future<BatchReport.Row> fut : futures) report.add(fut.get());
            } finally {
                pool.shutdown();
            }
        }

        Path csv = report.write(outDir);
        int ok = report.countOf(BatchReport.Status.SUCCESS);
        int fail = report.countOf(BatchReport.Status.FAIL);
        int skip = report.countOf(BatchReport.Status.SKIP);
        OUT.println("[완료] 전체 " + total + " · 성공 " + ok + " · 실패 " + fail + " · 건너뜀 " + skip);
        OUT.println("[리포트] " + csv);

        if (fail > 0) return 2;   // 부분 실패
        return 0;                 // 전체 성공(건너뜀만 있어도 0)
    }

    /** 단일 파일 변환 + 진행 출력 + Row 작성(실패 격리: 예외는 Row 로 흡수). */
    private static BatchReport.Row convert(Path in, Path outDir, int idx, int total) {
        BatchReport.Row row = new BatchReport.Row();
        row.name = in.getFileName().toString();
        row.inPath = in.toString();
        try { row.inKB = (Files.size(in) + 1023) / 1024; } catch (Exception ignore) {}

        String stem = stemOf(row.name);
        Path out = outDir.resolve(stem + OdtConversionRouter.outExtFor(in));
        row.outPath = out.toString();
        String outName = out.getFileName().toString();

        String prefix = "[" + idx + "/" + total + "] " + row.name + " → " + outName;

        long t0 = System.nanoTime();
        Result r;
        try {
            Options opts = Options.defaults();
            r = OdtConversionRouter.convertOne(in, out, opts);
        } catch (Throwable t) {
            r = Result.failure(in, out, t.toString());
        }
        row.elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        if (r != null && r.ok) {
            row.status = BatchReport.Status.SUCCESS;
            row.paragraphs = r.paragraphs;
            row.tables = r.tables;
            row.images = r.images;
            row.hyperlinks = r.hyperlinks;
            row.bookmarks = r.bookmarks;
            row.header = r.header;
            row.footer = r.footer;
            try { row.outKB = Files.exists(out) ? (Files.size(out) + 1023) / 1024 : 0; } catch (Exception ignore) {}
            row.valid = odtValid(out);
            OUT.println(prefix + " [OK] (" + row.elapsedMs + "ms)");
        } else {
            row.status = BatchReport.Status.FAIL;
            String msg = (r == null) ? "결과 없음" : (r.message == null ? "알 수 없는 실패" : r.message);
            row.errorCode = classify(msg, in);
            row.errorSummary = oneLine(msg);
            row.valid = "-";
            OUT.println(prefix + " [실패] " + row.errorCode + ": " + row.errorSummary);
        }
        return row;
    }

    // ---- 열거 ----

    private static List<Path> enumerate(Path inDir, String glob) throws Exception {
        // 비재귀(단일 폴더) 매칭이므로 패턴의 디렉터리 부분은 떼고 파일명 패턴만 사용
        //   (예: "**/*.hwp", "sub/*.hwp" → "*.hwp"). 대소문자 무시 위해 패턴/파일명 모두 소문자.
        String fnGlob = basenameGlob(glob);
        PathMatcher matcher = (fnGlob == null || fnGlob.isEmpty())
                ? null : FileSystems.getDefault().getPathMatcher("glob:" + fnGlob.toLowerCase(Locale.ROOT));
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inDir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) continue;
                String name = p.getFileName().toString();
                if (isSidecar(name)) continue;
                if (matcher != null) {
                    if (!matcher.matches(Paths.get(name.toLowerCase(Locale.ROOT)))) continue;
                } else {
                    if (!OdtConversionRouter.isSupported(p)) continue;
                }
                out.add(p);
            }
        }
        out.sort((a, b) -> a.getFileName().toString()
                .compareToIgnoreCase(b.getFileName().toString()));
        return out;
    }

    /** 사이드카 파일(.origin.* 등 숨김 보조파일) 제외. */
    private static boolean isSidecar(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith(".") || n.contains(".origin.");
    }

    // ---- ODT 구조 자가검증(외부 validator 미사용) ----

    /** mimetype 첫 엔트리(STORED) + content.xml + META-INF/manifest.xml 존재 확인. OK/WARN/FAIL. */
    private static String odtValid(Path odt) {
        if (odt == null || !Files.exists(odt)) return "FAIL";
        try (ZipFile zf = new ZipFile(odt.toFile())) {
            ZipEntry mimetype = zf.getEntry("mimetype");
            boolean content = zf.getEntry("content.xml") != null;
            boolean manifest = zf.getEntry("META-INF/manifest.xml") != null;
            if (mimetype == null || !content || !manifest) return "FAIL";
            boolean stored = mimetype.getMethod() == ZipEntry.STORED;
            String mt;
            try (java.io.InputStream is = zf.getInputStream(mimetype)) {
                byte[] b = kr.n.nframe.newfeature.IoCompat.readAllBytes(is);
                mt = new String(b, StandardCharsets.US_ASCII).trim();
            }
            boolean typeOk = "application/vnd.oasis.opendocument.text".equals(mt);
            if (stored && typeOk) return "OK";
            return "WARN"; // 구조는 있으나 mimetype 저장방식/값이 권장과 다름
        } catch (Exception e) {
            return "FAIL";
        }
    }

    // ---- 에러 분류(하린 §에러코드) ----

    private static String classify(String msg, Path in) {
        if (msg == null) return "E_UNKNOWN";
        String m = msg.toLowerCase(Locale.ROOT);
        try { if (Files.exists(in) && Files.size(in) == 0) return "E_FILE_CORRUPT"; } catch (Exception ignore) {}
        if (m.contains("nosuchfile") || m.contains("filenotfound") || m.contains("찾을 수 없"))
            return "E_FILE_NOT_FOUND";
        if (m.contains("outofmemory")) return "E_OUT_OF_MEMORY";
        if (m.contains("being used by another") || m.contains("사용 중") || m.contains("locked")
                || m.contains("filelock")) return "E_FILE_LOCKED";
        if (m.contains("malformedinput") || m.contains("unmappable") || m.contains("charset")
                || m.contains("encoding")) return "E_ENCODING";
        if (m.contains("accessdenied") || m.contains("쓰기 권한") || m.contains("readonly"))
            return "E_OUTPUT_WRITE";
        if (m.contains("unable to read") || m.contains("expected 512") || m.contains("corrupt")
                || m.contains("eofexception") || m.contains("not a hwp") || m.contains("invalid")
                || m.contains("bad ") || m.contains("zip") || m.contains("header"))
            return "E_FILE_CORRUPT";
        if (m.contains("exception")) return "E_CONVERT";
        return "E_UNKNOWN";
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String one = s.replace('\r', ' ').replace('\n', ' ').trim();
        return one.length() > 300 ? one.substring(0, 300) + "…" : one;
    }

    // ---- 유틸 ----

    /**
     * 글로브 패턴의 외곽 따옴표 제거. Windows cmd/.bat 가 {@code --glob "*.hwp"} 를
     * 따옴표 포함('"*.hwp"')으로 JVM 에 전달해 PathMatcher 매칭이 실패하는 문제 방지.
     */
    private static String stripQuotes(String s) {
        if (s == null) return null;
        String t = s.trim();
        while (t.length() >= 2) {
            char a = t.charAt(0), b = t.charAt(t.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                t = t.substring(1, t.length() - 1).trim();
            } else break;
        }
        return t;
    }

    // 글로브의 디렉터리 부분 제거 → 파일명 패턴만 반환(비재귀 매칭용). 예: 더블스타-슬래시-별표.hwp → 별표.hwp
    private static String basenameGlob(String glob) {
        if (glob == null) return null;
        String g = glob;
        int slash = Math.max(g.lastIndexOf('/'), g.lastIndexOf('\\'));
        if (slash >= 0) g = g.substring(slash + 1);
        return g;
    }

    private static int parseThreads(String s) {
        try {
            int n = Integer.parseInt(s.trim());
            return n < 1 ? 1 : Math.min(n, 16);
        } catch (NumberFormatException e) { return 1; }
    }

    private static String nfc(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    private static String stemOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void printUsage() {
        ERR.println("사용법: BatchRunner <입력폴더> <출력폴더> --to-odt [--glob \"*.hwp\"] [--threads N]");
        ERR.println("  · 입력폴더 안의 .hwp/.hwpx 를 각각 <원본명>.odt 로 출력폴더에 변환");
        ERR.println("  · --glob 미지정 시 .hwp 와 .hwpx 모두 대상(파일별 엔진 자동 선택)");
        ERR.println("  · --threads 기본 1(순차). 종료코드 0=전체성공 2=부분실패 1=치명오류");
    }

    /** 인자/입력폴더 등 복구 불가 오류. */
    private static final class FatalException extends RuntimeException {
        FatalException(String m) { super(m); }
    }
}
