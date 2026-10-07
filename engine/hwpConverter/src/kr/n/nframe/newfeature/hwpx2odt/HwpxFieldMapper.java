package kr.n.nframe.newfeature.hwpx2odt;

import java.util.ArrayDeque;
import java.util.Deque;

import kr.dogfoot.hwpxlib.object.common.parameter.Param;
import kr.dogfoot.hwpxlib.object.common.parameter.StringParam;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.FieldBegin;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.Bookmark;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.inner.Parameters;

import kr.n.nframe.newfeature.hwpx2odt.HwpxTraverser.ParaState;
import kr.n.nframe.newfeature.odtcommon.OdtBuildContext;
import static kr.n.nframe.newfeature.odtcommon.OdtEmitter.esc;

/**
 * HWPX 필드 → ODT. 하이퍼링크(text:a) / 책갈피(text:bookmark).
 *
 * <p>하이퍼링크는 FieldBegin 에서 열고, FieldEnd(같은 run 흐름의 종료)에서 닫는다.
 * URL 은 FieldBegin.parameters() 의 StringParam, 없으면 name() 에서 추출(HWP 형식 "target;type;...").
 */
public final class HwpxFieldMapper {

    private final OdtBuildContext ctx;
    private final Deque<String> open = new ArrayDeque<>();

    public HwpxFieldMapper(OdtBuildContext ctx) { this.ctx = ctx; }

    public void beginHyperlink(FieldBegin fb, ParaState st) {
        String url = buildHref(commandOf(fb));
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
     * task4: 책갈피 도착 지점 — fieldBegin type="BOOKMARK" name="..." → ODT 점 책갈피.
     * 하이퍼링크가 buildHref 로 만든 "#name" 내부 앵커의 실제 목적지가 된다.
     * 짝이 되는 fieldEnd 를 위해 noop 을 스택에 push 해 균형을 유지한다.
     */
    public void beginBookmarkField(FieldBegin fb, ParaState st) {
        String name = (fb == null) ? null : fb.name();
        if (name != null && !name.isEmpty()) {
            st.out.append("<text:bookmark text:name=\"").append(esc(name)).append("\"/>");
        }
        open.push("noop");
    }

    /** FieldEnd: 가장 최근 연 필드를 닫는다. */
    public void endField(ParaState st) {
        if (open.isEmpty()) return;
        String kind = open.pop();
        if ("a".equals(kind)) st.out.append("</text:a>");
    }

    public void bookmark(Bookmark bm, ParaState st) {
        String name = (bm == null) ? null : bm.name();
        if (name == null || name.isEmpty()) name = "bookmark" + ctx.nextBookmarkId();
        st.out.append("<text:bookmark text:name=\"").append(esc(name)).append("\"/>");
    }

    /** 하이퍼링크 명령 문자열: parameters 의 첫 비어있지 않은 StringParam, 없으면 name(). */
    private static String commandOf(FieldBegin fb) {
        Parameters ps = fb.parameters();
        if (ps != null) {
            for (Param p : ps.params()) {
                if (p instanceof StringParam) {
                    String v = ((StringParam) p).value();
                    if (v != null && !v.isEmpty()) return v;
                }
            }
        }
        return fb.name();
    }

    // ---- HWP 하이퍼링크 command → ODF href (hwp2odt.FieldMapper 와 동일 규칙, 패키지 분리로 복제) ----

    static String buildHref(String rawName) {
        if (rawName == null || rawName.isEmpty()) return null;
        String rawTarget = firstField(rawName);
        if (rawTarget.isEmpty()) return null;

        if (rawTarget.charAt(0) == '?') {
            String name = unescape(rawTarget.substring(1));
            return name.isEmpty() ? null : "#" + name;
        }

        int type = parseType(rawName);
        String t = unescape(rawTarget);
        if (hasScheme(t)) return t;
        if (type == 2 || (t.indexOf('@') > 0 && t.indexOf('/') < 0)) return "mailto:" + t;
        if (t.startsWith("//")) return "http:" + t;
        return t;
    }

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
        return true;
    }
}
