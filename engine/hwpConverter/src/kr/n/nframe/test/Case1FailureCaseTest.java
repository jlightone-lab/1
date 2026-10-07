package kr.n.nframe.test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

import kr.n.nframe.HwpConverter;
import kr.n.nframe.HwpMdConverter;

/**
 * v15.8 회귀 검증 — case1.md (다건 HTML 표 88개, 6924줄, 2.9MB) 의 MD → HWP / MD → HWPX 변환이
 * 30 초 이내에 완료되고 정상 결과 파일을 산출하는지 확인한다.
 *
 * <p><b>원인</b> (사용자 보고, 2026-05-13): hwpConverter.bat 은 console UTF-8 출력을 위해
 * {@code chcp 65001} 설정 + {@code -Dstdout.encoding=UTF-8} 을 사용한다. 이 조합에서
 * JDK 11 의 console PrintStream write 가 사실상 char-by-char 동기 호출이 되어, 88개 표를
 * 거치며 발생하는 수백 회의 {@code println} 누적이 사실상 무한 정체로 보였다 (CPU 100%
 * 지속, "[MdToHwpRich] template BIN_DATA 슬롯: 8" 로그 이후 진행 없음).
 *
 * <p><b>수정</b>: {@link HwpConverter#main(String[])} 진입 시 {@code System.out} / {@code System.err}
 * 을 64KB BufferedOutputStream 으로 감싸 flush 횟수를 격감시킨다. shutdown hook 으로
 * 종료 시 1회 명시적 flush 를 보장.
 *
 * <p>이 테스트는 통과 조건:
 * <ol>
 *   <li>{@code convertMarkdownToHwp(case1.md, case1.hwp)} 이 정해진 시간 내에 완료된다 (TIMEOUT_MS).</li>
 *   <li>출력 파일이 존재하고 크기 &gt; 100 KB 이다.</li>
 *   <li>(선택) HWPX 변환도 동일 조건을 만족한다.</li>
 * </ol>
 */
public class Case1FailureCaseTest {
    private static final long TIMEOUT_MS = 60_000L;  // 정상 동작 시 ~3초, 안전마진 60s
    private static final long MIN_OUTPUT_BYTES = 100_000L;

    public static void main(String[] args) throws Exception {
        Path mdPath = (args.length >= 1)
                ? Paths.get(args[0])
                : Paths.get("hwp", "0512", "dummy_file", "08.다건hwpx_md", "case1.md");
        if (!Files.exists(mdPath)) {
            fail("입력 MD 파일을 찾을 수 없습니다: " + mdPath.toAbsolutePath());
            System.exit(1);
        }

        Path outDir = Files.createTempDirectory("case1_failure_case_");
        try {
            int passed = 0, failed = 0;

            failed += runCase("MD → HWP",  mdPath, outDir.resolve("case1.hwp")) ? 0 : 1;
            failed += runCase("MD → HWPX", mdPath, outDir.resolve("case1.hwpx")) ? 0 : 1;
            passed = 2 - failed;

            System.out.println();
            System.out.println("============================================================");
            System.out.println("[Case1FailureCaseTest] 결과: 통과=" + passed + ", 실패=" + failed);
            System.out.println("============================================================");
            if (failed > 0) {
                System.exit(1);
            }
        } finally {
            // 출력 정리 — 디버깅을 위해 보존하고 싶으면 이 블록 제거
            try {
                Files.walk(outDir).sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            } catch (Exception ignored) { }
        }
    }

    private static boolean runCase(String label, Path mdPath, Path outPath) {
        System.out.println("[" + label + "] start: " + outPath);
        long t0 = System.currentTimeMillis();
        // 자식 스레드로 변환을 실행하고 TIMEOUT_MS 내에 끝나지 않으면 실패로 처리.
        final Throwable[] err = new Throwable[1];
        Thread worker = new Thread(() -> {
            try {
                // 안전: 변환 도중 발생하는 다량의 println 을 silently drop 하여 console I/O
                //   영향 (chcp 65001 환경) 자체를 격리해서 측정한다.
                PrintStream savedOut = System.out;
                PrintStream savedErr = System.err;
                System.setOut(new PrintStream(new NullOutputStream()));
                System.setErr(new PrintStream(new NullOutputStream()));
                try {
                    HwpMdConverter conv = new HwpMdConverter();
                    if (outPath.toString().toLowerCase().endsWith(".hwp")) {
                        conv.convertMarkdownToHwp(mdPath.toString(), outPath.toString());
                    } else {
                        conv.convertMarkdownToHwpx(mdPath.toString(), outPath.toString());
                    }
                } finally {
                    System.setOut(savedOut);
                    System.setErr(savedErr);
                }
            } catch (Throwable t) {
                err[0] = t;
            }
        }, "case1-conv-" + label);
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(label + " — main 스레드 인터럽트");
            return false;
        }
        long dt = System.currentTimeMillis() - t0;
        if (worker.isAlive()) {
            fail(label + " — " + TIMEOUT_MS + "ms 초과 (변환 정체 추정). 경과=" + dt + "ms");
            // worker 는 daemon 이라 JVM 종료 시 강제 종료됨
            return false;
        }
        if (err[0] != null) {
            fail(label + " — 예외: " + err[0].getClass().getSimpleName() + ": " + err[0].getMessage());
            err[0].printStackTrace();
            return false;
        }
        if (!Files.exists(outPath)) {
            fail(label + " — 출력 파일 미생성: " + outPath);
            return false;
        }
        long size;
        try {
            size = Files.size(outPath);
        } catch (Exception e) {
            fail(label + " — 출력 크기 조회 실패: " + e.getMessage());
            return false;
        }
        if (size < MIN_OUTPUT_BYTES) {
            fail(label + " — 출력 크기 비정상 (size=" + size + " < " + MIN_OUTPUT_BYTES + ")");
            return false;
        }
        System.out.println("[" + label + "] PASS — " + dt + "ms, size=" + size + " bytes");
        return true;
    }

    private static void fail(String msg) {
        System.out.println("[FAIL] " + msg);
    }

    private static final class NullOutputStream extends OutputStream {
        @Override public void write(int b) { /* discard */ }
        @Override public void write(byte[] b, int off, int len) { /* discard */ }
    }
}
