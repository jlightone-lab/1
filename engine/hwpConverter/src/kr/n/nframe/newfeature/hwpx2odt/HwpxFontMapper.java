package kr.n.nframe.newfeature.hwpx2odt;

import kr.dogfoot.hwpxlib.object.content.header_xml.RefList;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.Fontfaces;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.Fontface;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.fontface.Font;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.charpr.ValuesByLanguage;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.UnderlineType;

import kr.n.nframe.newfeature.odtcommon.StyleRegistry;
import kr.n.nframe.newfeature.odtcommon.Units;

/**
 * HWPX CharPr(글자모양) → ODT text:style 매핑. 폰트(라틴/CJK 분리)·크기·굵게·기울임·밑줄·색·자간·장평.
 *
 * <p>fontRef 는 언어별 폰트 id 를 담고, id 는 해당 언어 Fontface 안의 Font.id 를 가리킨다.
 */
public final class HwpxFontMapper {

    private final RefList refList;
    private final StyleRegistry styles;

    public HwpxFontMapper(RefList refList, StyleRegistry styles) {
        this.refList = refList;
        this.styles = styles;
    }

    /** charPrIDRef → 등록된 text 스타일 이름. 없으면 null(기본 글꼴). */
    public String styleFor(String charPrIDRef) {
        CharPr cs = charPr(charPrIDRef);
        if (cs == null) return null;

        String latin = null, asian = null;
        ValuesByLanguage<String> fr = cs.fontRef();
        if (fr != null) {
            asian = resolveFont(fr.hangul(), true);
            latin = resolveFont(fr.latin(), false);
        }
        if (latin == null) latin = asian;

        Integer height = cs.height();           // 1/100 pt
        String sizePt = (height != null && height > 0) ? Units.baseSizeToPt(height) : null;
        double fontPt = (height != null) ? height / 100.0 : 0.0;

        boolean bold = cs.bold() != null;
        boolean ital = cs.italic() != null;
        boolean ul = cs.underline() != null && cs.underline().type() != null
                  && cs.underline().type() != UnderlineType.NONE;
        String color = colorHex(cs.textColor());

        String letterSpacing = letterSpacing(cs, fontPt);
        String textScale = textScale(cs);

        return styles.textStyle(latin, asian, sizePt, bold, ital, ul, color, letterSpacing, textScale, null);
    }

    /** 자간(% of em, 부호 보존) → fo:letter-spacing 절대 pt. 0 이면 null. */
    private static String letterSpacing(CharPr cs, double fontPt) {
        ValuesByLanguage<Short> sp = cs.spacing();
        if (sp == null || sp.hangul() == null) return null;
        int pct = sp.hangul();
        if (pct == 0 || fontPt <= 0) return null;
        return Units.signedPt((pct / 100.0) * fontPt);
    }

    /** 장평(% 직접, 100=기본) → style:text-scale. */
    private static String textScale(CharPr cs) {
        ValuesByLanguage<Short> r = cs.ratio();
        if (r == null || r.hangul() == null) return null;
        int scale = r.hangul();
        if (scale <= 0 || scale == 100) return null;
        return scale + "%";
    }

    /**
     * task1/2: charPrIDRef 의 글자크기(em) HWPUNIT. CharPr.height 단위=1/100pt, 1pt=100HWPUNIT
     * 이므로 em(HWPUNIT) == height 값. 미상이면 기본 1000(=10pt).
     */
    public int emHwpUnit(String charPrIDRef) {
        CharPr cs = charPr(charPrIDRef);
        if (cs != null && cs.height() != null && cs.height() > 0) return cs.height();
        return 1000;
    }

    private CharPr charPr(String idRef) {
        if (refList == null || idRef == null || refList.charProperties() == null) return null;
        try {
            int id = Integer.parseInt(idRef.trim());
            if (id >= 0 && id < refList.charProperties().count()) return refList.charProperties().get(id);
        } catch (NumberFormatException ignore) {}
        for (CharPr cp : refList.charProperties().items()) {
            if (idRef.equals(cp.id())) return cp;
        }
        return null;
    }

    /** 폰트 id → face 이름. hangul=true 면 한글 Fontface 우선, 그 외 라틴. 못 찾으면 전체 탐색. */
    private String resolveFont(String fontId, boolean hangul) {
        if (refList == null || fontId == null || fontId.isEmpty()) return null;
        Fontfaces ffs = refList.fontfaces();
        if (ffs == null) return null;
        String hit = faceIn(hangul ? ffs.hangulFontface() : ffs.latinFontface(), fontId);
        if (hit != null) return hit;
        for (Fontface ff : ffs.fontfaces()) {
            hit = faceIn(ff, fontId);
            if (hit != null) return hit;
        }
        return null;
    }

    private static String faceIn(Fontface ff, String fontId) {
        if (ff == null) return null;
        for (Font f : ff.fonts()) {
            if (fontId.equals(f.id())) {
                String face = f.face();
                return (face == null || face.isEmpty()) ? null : face;
            }
        }
        return null;
    }

    /** 검정(#000000)·none·빈값은 기본색으로 보고 null(스타일 노이즈 감소). */
    private static String colorHex(String c) {
        if (c == null || c.isEmpty()) return null;
        String v = c.trim();
        if (v.equalsIgnoreCase("none")) return null;
        if (v.equalsIgnoreCase("#000000") || v.equalsIgnoreCase("#000")) return null;
        if (v.charAt(0) != '#') v = "#" + v;
        return v;
    }
}
