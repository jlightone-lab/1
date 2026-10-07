package kr.n.nframe.newfeature.hwp2odt;

/** 변환 옵션(향후 확장 여지). 현재는 verbose 로깅 토글만. */
public final class Options {
    public boolean verbose = false;

    public static Options defaults() { return new Options(); }
}
