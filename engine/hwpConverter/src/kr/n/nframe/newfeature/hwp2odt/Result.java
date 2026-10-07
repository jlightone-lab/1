package kr.n.nframe.newfeature.hwp2odt;

import java.nio.file.Path;

/** 단건 변환 결과 요약(검증 매트릭스/배치 집계용). */
public final class Result {
    public final Path input;
    public final Path output;
    public final boolean ok;
    public final String message;

    public int paragraphs, tables, images, hyperlinks, bookmarks;
    public boolean header, footer;

    private Result(Path in, Path out, boolean ok, String msg) {
        this.input = in; this.output = out; this.ok = ok; this.message = msg;
    }

    public static Result success(Path in, Path out) { return new Result(in, out, true, "OK"); }
    public static Result failure(Path in, Path out, String msg) { return new Result(in, out, false, msg); }

    @Override public String toString() {
        return (ok ? "OK   " : "FAIL ") + (input == null ? "?" : input.getFileName())
             + " -> " + (output == null ? "?" : output.getFileName())
             + " [p=" + paragraphs + " tbl=" + tables + " img=" + images
             + " hl=" + hyperlinks + " bm=" + bookmarks
             + " hdr=" + header + " ftr=" + footer + "]"
             + (ok ? "" : " : " + message);
    }
}
