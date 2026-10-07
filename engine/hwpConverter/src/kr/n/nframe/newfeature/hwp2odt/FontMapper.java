package kr.n.nframe.newfeature.hwp2odt;

import java.util.List;

import kr.dogfoot.hwplib.object.docinfo.CharShape;
import kr.dogfoot.hwplib.object.docinfo.DocInfo;
import kr.dogfoot.hwplib.object.docinfo.FaceName;
import kr.dogfoot.hwplib.object.docinfo.charshape.CharSpaces;
import kr.dogfoot.hwplib.object.docinfo.charshape.FaceNameIds;
import kr.dogfoot.hwplib.object.docinfo.charshape.Ratios;
import kr.dogfoot.hwplib.object.docinfo.charshape.UnderLineSort;
import kr.dogfoot.hwplib.object.etc.Color4Byte;

import kr.n.nframe.newfeature.odtcommon.StyleRegistry;
import kr.n.nframe.newfeature.odtcommon.Units;

/**
 * CharShape(글자모양) → ODT text:style 매핑. 폰트(라틴/CJK 분리)·크기·굵게·기울임·밑줄·색.
 */
public final class FontMapper {

    private final DocInfo di;
    private final StyleRegistry styles;

    public FontMapper(DocInfo di, StyleRegistry styles) {
        this.di = di;
        this.styles = styles;
    }

    /** charShapeId → 등록된 text 스타일 이름. 잘못된 id 면 null(기본 글꼴). */
    public String styleFor(int charShapeId) {
        List<CharShape> list = di.getCharShapeList();
        if (list == null || charShapeId < 0 || charShapeId >= list.size()) return null;
        CharShape cs = list.get(charShapeId);
        if (cs == null) return null;

        FaceNameIds f = cs.getFaceNameIds();
        String latin  = nameOf(di.getEnglishFaceNameList(), f == null ? -1 : f.getLatin());
        String asian  = nameOf(di.getHangulFaceNameList(),  f == null ? -1 : f.getHangul());
        if (latin == null) latin = asian;

        String sizePt = Units.baseSizeToPt(cs.getBaseSize());
        boolean bold = cs.getProperty() != null && cs.getProperty().isBold();
        boolean ital = cs.getProperty() != null && cs.getProperty().isItalic();
        boolean ul   = cs.getProperty() != null
                    && cs.getProperty().getUnderLineSort() != null
                    && cs.getProperty().getUnderLineSort() != UnderLineSort.None;
        String color = colorHex(cs.getCharColor());

        double fontPt = cs.getBaseSize() / 100.0;
        String letterSpacing = letterSpacing(cs, fontPt);
        String textScale = textScale(cs);
        String shade = shadeHex(cs.getShadeColor());

        return styles.textStyle(latin, asian, sizePt, bold, ital, ul, color, letterSpacing, textScale, shade);
    }

    /**
     * 자간 → fo:letter-spacing(절대길이). HWP CharSpaces 는 em(글자폭=글꼴크기) 대비 백분율.
     * ODT 는 절대 길이를 받으므로 pt = (자간% / 100) × 글꼴pt 로 환산. 0 이면 null.
     */
    private static String letterSpacing(CharShape cs, double fontPt) {
        CharSpaces sp = cs.getCharSpaces();
        if (sp == null) return null;
        int pct = sp.getHangul();           // 대표값(한글). 0=기본
        if (pct == 0) return null;
        return Units.signedPt((pct / 100.0) * fontPt);
    }

    /** 장평 → style:text-scale(백분율). HWP Ratios 는 이미 백분율(100=기본). */
    private static String textScale(CharShape cs) {
        Ratios r = cs.getRatios();
        if (r == null) return null;
        int scale = r.getHangul();
        if (scale <= 0 || scale == 100) return null;
        return scale + "%";
    }

    private static String nameOf(List<FaceName> list, int idx) {
        if (list == null || idx < 0 || idx >= list.size()) return null;
        FaceName fn = list.get(idx);
        if (fn == null) return null;
        String n = fn.getName();
        return (n == null || n.isEmpty()) ? null : n;
    }

    /** 검정(0,0,0)은 기본색으로 보고 null 반환(스타일 노이즈 감소). */
    private static String colorHex(Color4Byte c) {
        if (c == null) return null;
        int r = c.getR() & 0xFF, g = c.getG() & 0xFF, b = c.getB() & 0xFF;
        if (r == 0 && g == 0 && b == 0) return null;
        return String.format("#%02X%02X%02X", r, g, b);
    }

    /** 글자 음영/배경색 → "#RRGGBB". 흰색(255,255,255)은 "배경 없음" 센티넬이므로 null. */
    private static String shadeHex(Color4Byte c) {
        if (c == null) return null;
        int r = c.getR() & 0xFF, g = c.getG() & 0xFF, b = c.getB() & 0xFF;
        if (r == 255 && g == 255 && b == 255) return null;
        return String.format("#%02X%02X%02X", r, g, b);
    }
}
