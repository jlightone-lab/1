package kr.n.nframe.newfeature.odtcommon;

/**
 * XML 직렬화 보조 — StringBuilder 싱크 + ODT/XML escape.
 *
 * <p>제어문자(0x00~0x1F)는 XML 1.0 에서 \t\n\r 만 허용되므로 나머지는 제거.
 * 서로게이트 등 0x20 이상은 그대로 통과(UTF-8 로 인코딩됨).
 */
public final class OdtEmitter {
    private OdtEmitter() {}

    /** XML 텍스트/속성값 escape. null → "". */
    public static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        // task9/10: HWP 전용 폰트 PUA 글리프(원문자·화살표·도장)를 표준 Unicode 로 치환(양 파이프라인 공유).
        s = PuaCharMapper.sanitize(s);
        if (s.isEmpty()) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':  b.append("&amp;");  break;
                case '<':  b.append("&lt;");   break;
                case '>':  b.append("&gt;");   break;
                case '"':  b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default:
                    if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
                        b.append(c);
                    }
                    // else: 허용되지 않는 제어문자 → 제거
            }
        }
        return b.toString();
    }

    /** 속성 한 쌍을 ` name="value"` 형태로(value escape 포함). */
    public static void attr(StringBuilder sb, String name, String value) {
        sb.append(' ').append(name).append("=\"").append(esc(value)).append('"');
    }
}
