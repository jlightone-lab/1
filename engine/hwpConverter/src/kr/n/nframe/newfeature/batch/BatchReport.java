package kr.n.nframe.newfeature.batch;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 배치 변환 결과 누적 + CSV 리포트 작성(하린 UX §리포트, 17컬럼 UTF-8 BOM).
 *
 * <p>파일명 규칙: {@code 변환결과_YYYYMMDD_HHmmss.csv}. 마지막에 [요약] 행 1줄.
 * 한국어 헤더/값 보존을 위해 UTF-8 BOM 을 선두에 붙여 Excel 한글 깨짐을 막는다.
 */
public final class BatchReport {

    /** SUCCESS=정상 변환, FAIL=변환 실패, SKIP=대상 아님/건너뜀. */
    public enum Status { SUCCESS, FAIL, SKIP }

    /** CSV 한 행. */
    public static final class Row {
        public String name;          // 파일명
        public String inPath;        // 입력경로
        public String outPath;       // 출력경로
        public long inKB;            // 입력크기_KB
        public long outKB;           // 출력크기_KB
        public Status status;        // SUCCESS/FAIL/SKIP
        public String valid = "-";   // OK/WARN/FAIL/-
        public int paragraphs;       // 문단수
        public int tables;           // 표수
        public int images;           // 그림수
        public int hyperlinks;       // 하이퍼링크수
        public int bookmarks;        // 북마크수
        public boolean header;       // 머리말 Y/N
        public boolean footer;       // 꼬리말 Y/N
        public long elapsedMs;       // 소요시간_ms
        public String errorCode = "";    // 에러코드
        public String errorSummary = ""; // 에러요약
    }

    private static final String[] HEADERS = {
        "파일명", "입력경로", "출력경로", "입력크기_KB", "출력크기_KB",
        "status", "valid", "문단수", "표수", "그림수",
        "하이퍼링크수", "북마크수", "머리말", "꼬리말", "소요시간_ms",
        "에러코드", "에러요약"
    };

    private final List<Row> rows = new ArrayList<>();

    public void add(Row r) { rows.add(r); }
    public List<Row> rows() { return rows; }

    public int countOf(Status s) {
        int n = 0;
        for (Row r : rows) if (r.status == s) n++;
        return n;
    }

    /** 출력 폴더에 타임스탬프 CSV 를 쓰고 그 경로를 반환. */
    public Path write(Path outDir) throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path csv = outDir.resolve("변환결과_" + ts + ".csv");
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(csv), StandardCharsets.UTF_8))) {
            w.write('﻿'); // UTF-8 BOM (Excel 한글)
            writeRow(w, HEADERS);
            for (Row r : rows) {
                writeRow(w, new String[]{
                    r.name, r.inPath, r.outPath,
                    Long.toString(r.inKB), Long.toString(r.outKB),
                    r.status.name(), r.valid,
                    Integer.toString(r.paragraphs), Integer.toString(r.tables),
                    Integer.toString(r.images), Integer.toString(r.hyperlinks),
                    Integer.toString(r.bookmarks),
                    r.header ? "Y" : "N", r.footer ? "Y" : "N",
                    Long.toString(r.elapsedMs),
                    r.errorCode, r.errorSummary
                });
            }
            // 요약 행: [요약],전체:N,성공:N,실패:N,건너뜀:N
            int total = rows.size();
            String summary = "[요약],전체:" + total
                    + ",성공:" + countOf(Status.SUCCESS)
                    + ",실패:" + countOf(Status.FAIL)
                    + ",건너뜀:" + countOf(Status.SKIP);
            w.write(summary);
            w.newLine();
        }
        return csv;
    }

    private static void writeRow(BufferedWriter w, String[] cells) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csvField(cells[i]));
        }
        w.write(sb.toString());
        w.newLine();
    }

    /** RFC4180 따옴표 처리: ,"개행 포함 시 큰따옴표로 감싸고 내부 따옴표는 두 번. */
    private static String csvField(String s) {
        if (s == null) s = "";
        boolean needQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needQuote) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
