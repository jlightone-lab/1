package kr.n.nframe.newfeature.hwp2odt;

import java.util.ArrayDeque;
import java.util.Deque;

import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlField;

import kr.n.nframe.newfeature.hwp2odt.HwpDocumentTraverser.ParaState;
import kr.n.nframe.newfeature.odtcommon.OdtBuildContext;
import static kr.n.nframe.newfeature.odtcommon.OdtEmitter.esc;

/**
 * 필드 컨트롤 → ODT. 하이퍼링크(text:a) / 책갈피(text:bookmark).
 *
 * <p>하이퍼링크는 FIELD_BEGIN(확장컨트롤)에서 열고, FIELD_END(인라인 0x04)에서 닫는다.
 */
public final class FieldMapper {

    private final OdtBuildContext ctx;
    /** 열린 필드 종류 스택(현재는 hyperlink 만 열고 닫음). */
    private final Deque<String> open = new ArrayDeque<>();

    public FieldMapper(OdtBuildContext ctx) { this.ctx = ctx; }

    public void beginHyperlink(Control c, ParaState st) {
        String url = hyperlinkUrl(c);
        if (url == null || url.isEmpty()) {
            open.push("noop");
            return;
        }
        st.out.append("<text:a xlink:type=\"simple\" xlink:href=\"").append(esc(url))
              .append("\" text:style-name=\"Internet_20_Link\"")
              .append(" text:visited-style-name=\"Visited_20_Internet_20_Link\">");
        open.push("a");
    }

    /**
     * HWP 하이퍼링크 필드 command → ODF href.
     *
     * <p>형식: {@code target;type;...} (UNESCAPED ';' 구분). target 내 특수문자는
     * 역슬래시 이스케이프(\?, \:, \;, \\). type: 1=웹, 2=메일, 0=내부책갈피.
     * <ul>
     *   <li>내부 앵커: target 이 (이스케이프되지 않은) '?' 로 시작 → "#" + 책갈피명 (ODF §6.1.8)</li>
     *   <li>웹: scheme-relative "//host" → "http://host"</li>
     *   <li>메일: "user@host" → "mailto:user@host"</li>
     * </ul>
     */
    static String buildHref(String rawName) {
        if (rawName == null || rawName.isEmpty()) return null;
        String rawTarget = firstField(rawName);
        if (rawTarget.isEmpty()) return null;

        // 내부 책갈피: 이스케이프되지 않은 '?' 시작 (URL 내 리터럴 '?'는 '\?')
        if (rawTarget.charAt(0) == '?') {
            String name = unescape(rawTarget.substring(1));
            return name.isEmpty() ? null : "#" + name;
        }

        int type = parseType(rawName);
        String t = unescape(rawTarget);
        if (hasScheme(t)) return t;
        if (type == 2 || (t.indexOf('@') > 0 && t.indexOf('/') < 0)) return "mailto:" + t;
        if (t.startsWith("//")) return "http:" + t;     // scheme-relative
        return t;
    }

    /** UNESCAPED ';' 기준 첫 토큰. */
    private static String firstField(String s) {
        StringBuilder b = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { b.append('\\').append(c); esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == ';') break;
            b.append(c);
        }
        if (esc) b.append('\\');
        return b.toString();
    }

    /** 두 번째 UNESCAPED ';' 필드(type). 없으면 -1. */
    private static int parseType(String s) {
        boolean esc = false; int field = 0; StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == ';') { field++; if (field == 2) break; continue; }
            if (field == 1) b.append(c);
        }
        try { return Integer.parseInt(b.toString().trim()); } catch (Exception e) { return -1; }
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { b.append(c); esc = false; }
            else if (c == '\\') esc = true;
            else b.append(c);
        }
        return b.toString();
    }

    private static boolean hasScheme(String t) {
        int colon = t.indexOf(':');
        if (colon <= 0) return false;
        for (int i = 0; i < colon; i++) {
            char c = t.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '+' || c == '.' || c == '-')) return false;
        }
        return true; // http:, https:, mailto:, ftp:, file: ...
    }

    /** FIELD_END(0x04): 가장 최근 연 필드를 닫는다. */
    public void endField(ParaState st) {
        if (open.isEmpty()) return;
        String kind = open.pop();
        if ("a".equals(kind)) st.out.append("</text:a>");
    }

    public void bookmark(Control c, ParaState st) {
        String name = bookmarkName(c);
        if (name == null || name.isEmpty()) name = "bookmark" + ctx.nextBookmarkId();
        st.out.append("<text:bookmark text:name=\"").append(esc(name)).append("\"/>");
    }

    /** HWP 하이퍼링크 필드 getName() → ODF href. */
    private static String hyperlinkUrl(Control c) {
        try {
            if (c instanceof ControlField) {
                return buildHref(((ControlField) c).getName());
            }
        } catch (Throwable ignore) {}
        return null;
    }

    private static String bookmarkName(Control c) {
        try {
            if (c instanceof ControlField) {
                String n = ((ControlField) c).getName();
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Throwable ignore) {}
        return null;
    }
}
