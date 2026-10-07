package kr.n.nframe.newfeature.odtcommon;

/**
 * HWPUNIT ↔ ODT 단위 환산 (단일 출처 — 분산 환산 금지, 오차/회귀 위험 차단).
 *
 * <p>HWPUNIT = 1/7200 inch. ODT 는 cm / inch / pt 를 사용.
 * 폰트 크기는 hwplib CharShape.getBaseSize() 가 1/100 pt 단위(1000=10pt).
 */
public final class Units {
    private Units() {}

    public static final double HWPUNIT_PER_INCH = 7200.0;
    public static final double CM_PER_INCH = 2.54;

    /** HWPUNIT → cm 문자열 (예: "3.52cm"). */
    public static String hwpToCm(long hwpunit) {
        double cm = (hwpunit / HWPUNIT_PER_INCH) * CM_PER_INCH;
        return trim(cm) + "cm";
    }

    /** HWPUNIT → cm 문자열(음수 허용). 내어쓰기(hanging indent) 등. */
    public static String hwpToCmSigned(long hwpunit) {
        double cm = (hwpunit / HWPUNIT_PER_INCH) * CM_PER_INCH;
        String s = String.format(java.util.Locale.ROOT, "%.3f", cm);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return (s.isEmpty() ? "0" : s) + "cm";
    }

    /** HWPUNIT → inch 문자열. */
    public static String hwpToInch(long hwpunit) {
        double in = hwpunit / HWPUNIT_PER_INCH;
        return trim(in) + "in";
    }

    /** CharShape.getBaseSize() (1/100 pt) → "{n}pt". */
    public static String baseSizeToPt(int baseSize) {
        double pt = baseSize / 100.0;
        return trim(pt) + "pt";
    }

    /** 픽셀(이미지 자연 크기) → cm (96dpi 가정). 폴백 용도. */
    public static String pxToCm(long px) {
        double cm = (px / 96.0) * CM_PER_INCH;
        return trim(cm) + "cm";
    }

    /** 부호 포함 pt 문자열(자간 등 음수 허용). 예: "-0.3pt". */
    public static String signedPt(double pt) {
        String s = String.format(java.util.Locale.ROOT, "%.3f", pt);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return (s.isEmpty() ? "0" : s) + "pt";
    }

    private static String trim(double v) {
        if (v < 0) v = 0;
        String s = String.format(java.util.Locale.ROOT, "%.3f", v);
        // 소수점 뒤 불필요한 0 제거
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s.isEmpty() ? "0" : s;
    }
}
