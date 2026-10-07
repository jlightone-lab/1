package kr.n.nframe.newfeature.odtcommon;

import java.util.LinkedHashMap;
import java.util.Map;

import static kr.n.nframe.newfeature.odtcommon.OdtEmitter.attr;
import static kr.n.nframe.newfeature.odtcommon.OdtEmitter.esc;

/**
 * content.xml 의 &lt;office:automatic-styles&gt; 자동 스타일 등록/중복제거.
 *
 * <p>family·properties 시그니처가 같으면 같은 이름 재사용 → 스타일 폭증 방지.
 * 이름 접두어: P(문단) T(텍스트) Tab(표) Col(열) Cell(셀).
 */
public final class StyleRegistry {

    /** family|propsXml 시그니처 → 부여된 style:name */
    private final Map<String, String> bySignature = new LinkedHashMap<>();
    /** style:name → 완성된 <style:style ...> XML */
    private final Map<String, String> emitted = new LinkedHashMap<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();

    /** 문단 정렬 스타일. align: left/center/right/justify (null/빈값이면 기본 P 없음→null 반환). */
    public String paragraphAlign(String align) {
        if (align == null || align.isEmpty()) return null;
        StringBuilder p = new StringBuilder("<style:paragraph-properties");
        attr(p, "fo:text-align", align);
        p.append("/>");
        return register("paragraph", "P", p.toString());
    }

    /**
     * 문단 스타일(정렬·여백·들여쓰기·문단간격·하단테두리·탭정지점).
     * 각 인자 null 이면 해당 속성 미발화. tabStopsXml 은 <style:tab-stops>...</style:tab-stops> 통째.
     */
    public String paragraphStyle(String align, String marginLeft, String textIndent,
                                 String marginTop, String marginBottom,
                                 String borderBottom, String tabStopsXml) {
        return paragraphStyle(align, marginLeft, textIndent, marginTop, marginBottom,
                borderBottom, tabStopsXml, null, false);
    }

    public String paragraphStyle(String align, String marginLeft, String textIndent,
                                 String marginTop, String marginBottom,
                                 String borderBottom, String tabStopsXml, String bgHex) {
        return paragraphStyle(align, marginLeft, textIndent, marginTop, marginBottom,
                borderBottom, tabStopsXml, bgHex, false);
    }

    /**
     * 문단 스타일 + 문단 배경색(bgHex) + 페이지 나눔(breakBeforePage).
     * breakBeforePage=true 면 fo:break-before="page" 출력.
     */
    public String paragraphStyle(String align, String marginLeft, String textIndent,
                                 String marginTop, String marginBottom,
                                 String borderBottom, String tabStopsXml, String bgHex,
                                 boolean breakBeforePage) {
        boolean hasBg = bgHex != null && !bgHex.isEmpty();
        boolean any = align != null || marginLeft != null || textIndent != null
                   || marginTop != null || marginBottom != null || borderBottom != null
                   || (tabStopsXml != null && !tabStopsXml.isEmpty()) || hasBg || breakBeforePage;
        if (!any) return null;
        StringBuilder p = new StringBuilder("<style:paragraph-properties");
        if (align != null)        attr(p, "fo:text-align", align);
        if (marginLeft != null)   attr(p, "fo:margin-left", marginLeft);
        if (textIndent != null)   attr(p, "fo:text-indent", textIndent);
        if (marginTop != null)    attr(p, "fo:margin-top", marginTop);
        if (marginBottom != null) attr(p, "fo:margin-bottom", marginBottom);
        if (borderBottom != null) attr(p, "fo:border-bottom", borderBottom);
        if (hasBg)                attr(p, "fo:background-color", bgHex);
        if (breakBeforePage)      attr(p, "fo:break-before", "page");
        if (tabStopsXml != null && !tabStopsXml.isEmpty()) {
            p.append(">").append(tabStopsXml).append("</style:paragraph-properties>");
        } else {
            p.append("/>");
        }
        return register("paragraph", "P", p.toString());
    }

    /**
     * 텍스트(글자) 스타일.
     * @param asianFont CJK 폰트(style:font-name-asian) — 서연 리서치: CJK 별도 지정 필수
     * @param colorHex  "#RRGGBB" 또는 null
     */
    public String textStyle(String fontName, String asianFont, String sizePt,
                            boolean bold, boolean italic, boolean underline, String colorHex,
                            String letterSpacing, String textScale, String bgHex) {
        // v16t52 T1-폰트: 덕수 결정 — FontPolicy.mapName 임의 대체 제거 (휴먼명조→바탕 치환 등).
        //   orig 폰트명 그대로 수출·미설치 fallback 은 한컴 위임 (스펙 §11-1' (a)항).
        //   svg:font-family chain·panose 등 generic-family 안전망(OdtWriter 측)은 유지.
        // 종전(v16t33 task1/2): fontName/asianFont = FontPolicy.mapName(...) — v52 에서 제거.
        StringBuilder t = new StringBuilder("<style:text-properties");
        if (fontName != null && !fontName.isEmpty()) {
            attr(t, "style:font-name", fontName);
        }
        if (asianFont != null && !asianFont.isEmpty()) {
            attr(t, "style:font-name-asian", asianFont);
        }
        if (sizePt != null && !sizePt.isEmpty()) {
            attr(t, "fo:font-size", sizePt);
            attr(t, "style:font-size-asian", sizePt);
        }
        // 자간(절대길이) / 장평(백분율)
        if (letterSpacing != null && !letterSpacing.isEmpty()) {
            attr(t, "fo:letter-spacing", letterSpacing);
        }
        if (textScale != null && !textScale.isEmpty()) {
            attr(t, "style:text-scale", textScale);
        }
        if (bold) {
            attr(t, "fo:font-weight", "bold");
            attr(t, "style:font-weight-asian", "bold");
        }
        if (italic) {
            attr(t, "fo:font-style", "italic");
            attr(t, "style:font-style-asian", "italic");
        }
        if (underline) {
            attr(t, "style:text-underline-style", "solid");
            attr(t, "style:text-underline-width", "auto");
            attr(t, "style:text-underline-color", "font-color");
        }
        if (colorHex != null && !colorHex.isEmpty()) {
            attr(t, "fo:color", colorHex);
        }
        if (bgHex != null && !bgHex.isEmpty()) {
            attr(t, "fo:background-color", bgHex);
        }
        t.append("/>");
        return register("text", "T", t.toString());
    }

    /** v16t34 문제3: 표 위 여백 복원값(원본 한컴 최대 빈출값 0.35cm). */
    public static final String TABLE_MARGIN_TOP = "0.35cm";

    /** 표 전체 스타일(폭 cm, null 가능). 윗여백은 기본 복원값 적용. 정렬 left. */
    public String tableStyle(String widthCm) {
        return tableStyle(widthCm, TABLE_MARGIN_TOP, "left");
    }

    /** 표 전체 스타일 + 가로정렬(left/center/right). 윗여백 기본 복원값. (task5/6) */
    public String tableStyleAligned(String widthCm, String align) {
        return tableStyle(widthCm, TABLE_MARGIN_TOP, align);
    }

    /**
     * 표 전체 스타일. marginTop 으로 표 위 여백 제어(v16t34 문제3 — 표 위 답답함 해소).
     * margin-bottom 은 0 유지(기존 회귀선). null/빈값이면 윗여백 미발화.
     * align(table:align): 소스 호스트 문단 정렬에서 도출(left/center/right). center/right 는
     * style:width 가 함께 있어야 ODF 상 정렬이 동작한다(task5/6).
     */
    public String tableStyle(String widthCm, String marginTop, String align) {
        StringBuilder p = new StringBuilder("<style:table-properties");
        if (widthCm != null && !widthCm.isEmpty()) {
            attr(p, "style:width", widthCm);
        }
        attr(p, "table:align", (align != null && !align.isEmpty()) ? align : "left");
        if (marginTop != null && !marginTop.isEmpty()) {
            attr(p, "fo:margin-top", marginTop);
        }
        attr(p, "fo:margin-bottom", "0cm");
        p.append("/>");
        return register("table", "Tab", p.toString());
    }

    /** 열 폭 스타일. */
    public String columnStyle(String widthCm) {
        StringBuilder p = new StringBuilder("<style:table-column-properties");
        if (widthCm != null && !widthCm.isEmpty()) {
            attr(p, "style:column-width", widthCm);
        }
        p.append("/>");
        return register("table-column", "Col", p.toString());
    }

    /**
     * task2(v16t39): 행 고정높이 스타일. 표지 '장식 띠' 행(채움+빈문단)만 사용.
     * min-row-height(최소값)만 주면 LO 가 1pt 빈문단 줄높이+패딩이 그 최소를 넘을 때 행을 더
     * 키워 띠가 원본보다 두꺼워진다 → row-height(정확높이)로 고정하고 use-optimal-row-height=false
     * 로 콘텐츠 기반 자동확장을 막는다. min-row-height 도 병기(렌더러 호환). null/빈값이면 미생성.
     */
    public String tableRowFixedHeight(String heightCm) {
        if (heightCm == null || heightCm.isEmpty()) return null;
        return register("table-row", "ro",
                "<style:table-row-properties style:row-height=\"" + heightCm + "\""
              + " style:min-row-height=\"" + heightCm + "\""
              + " style:use-optimal-row-height=\"false\"/>");
    }

    /**
     * task3/4(v16t38): 장식 띠 셀의 빈 문단 스타일 — 빈 줄 높이를 원본 글자크기(보통 1pt)로
     * 억제한다. 한컴이 row 높이를 콘텐츠(빈 문단 폰트)로 산정해도 띠가 부풀지 않게 하는 char레벨
     * 폴백(한컴 확실 honor). fontSizePt null 이면 폰트 미발화(=호출측이 띠가 아님).
     */
    public String emptyBandParaStyle(String fontSizePt) {
        if (fontSizePt == null || fontSizePt.isEmpty()) return null;
        String props = "<style:text-properties fo:font-size=\"" + fontSizePt + "\""
                + " style:font-size-asian=\"" + fontSizePt + "\"/>";
        return register("paragraph", "P", props);
    }

    /**
     * 셀 스타일(테두리 4변 개별·배경·세로정렬).
     * 각 테두리 인자는 "0.5pt solid #000000" 형식 또는 null(해당 변 없음).
     * @param bgHex   "#RRGGBB" 또는 null
     * @param valign  top/middle/bottom 또는 null
     */
    public String cellStyle(String left, String right, String top, String bottom,
                            String bgHex, String valign) {
        return cellStyle(left, right, top, bottom, bgHex, valign, null, null, null, false);
    }

    /**
     * 셀 스타일 + 셀 배경 이미지(imgBrush). bgImageHref 가 있으면 단색 bgHex 대신
     * &lt;style:background-image&gt; 자식을 발화한다(이미지 우선, 상호배타).
     * @param bgImageHref "Pictures/imageN.ext" 또는 null
     * @param repeat   no-repeat/repeat/stretch 또는 null
     * @param position "center"/"left top" 등 또는 null
     */
    public String cellStyle(String left, String right, String top, String bottom,
                            String bgHex, String valign,
                            String bgImageHref, String repeat, String position) {
        return cellStyle(left, right, top, bottom, bgHex, valign,
                bgImageHref, repeat, position, false);
    }

    /**
     * 셀 스타일 + 좁은 셀 패딩 축소(narrow). v16t36 task3/4: 흐름도 연결셀(열폭 ~0.55cm)은
     * 기본 좌0.15·우0.05cm 패딩이 가용폭을 잠식해 →/⇒ 글리프가 셀 경계에 잘린다.
     * narrow=true 이면 좌우 패딩을 0.02cm 로 낮춰 글리프 가용폭을 확보(상/하는 0.05cm 유지).
     * 일반 셀(narrow=false)은 G/H 좌0.15cm 패딩 유지 → 무회귀.
     */
    public String cellStyle(String left, String right, String top, String bottom,
                            String bgHex, String valign,
                            String bgImageHref, String repeat, String position,
                            boolean narrow) {
        boolean hasImg = bgImageHref != null && !bgImageHref.isEmpty();
        StringBuilder p = new StringBuilder("<style:table-cell-properties");
        // G/H(T13/14-clip): 좌측 패딩이 0.05cm 로 과소해 셀 글자가 좌변에 붙어 앞부분이 잘려 보임.
        // 한컴 기본 셀 안쪽여백에 맞춰 좌측만 상향(상/우/하는 기존 0.05cm 유지 → 무회귀).
        attr(p, "fo:padding-top", "0.05cm");
        attr(p, "fo:padding-right", narrow ? "0.02cm" : "0.05cm");
        attr(p, "fo:padding-bottom", "0.05cm");
        attr(p, "fo:padding-left", narrow ? "0.02cm" : "0.15cm");
        if (left   != null) attr(p, "fo:border-left", left);
        if (right  != null) attr(p, "fo:border-right", right);
        if (top    != null) attr(p, "fo:border-top", top);
        if (bottom != null) attr(p, "fo:border-bottom", bottom);
        if (!hasImg && bgHex != null && !bgHex.isEmpty()) {
            attr(p, "fo:background-color", bgHex);
        }
        if (valign != null && !valign.isEmpty()) {
            attr(p, "style:vertical-align", valign);
        }
        if (hasImg) {
            p.append('>');
            p.append("<style:background-image");
            attr(p, "xlink:href", bgImageHref);
            attr(p, "xlink:type", "simple");
            attr(p, "xlink:actuate", "onLoad");
            if (repeat   != null && !repeat.isEmpty())   attr(p, "style:repeat", repeat);
            if (position != null && !position.isEmpty()) attr(p, "style:position", position);
            p.append("/>");
            p.append("</style:table-cell-properties>");
        } else {
            p.append("/>");
        }
        return register("table-cell", "Cell", p.toString());
    }

    private String register(String family, String prefix, String propsXml) {
        String sig = family + "|" + propsXml;
        String existing = bySignature.get(sig);
        if (existing != null) return existing;
        int n = counters.merge(prefix, 1, Integer::sum);
        String name = prefix + n;
        bySignature.put(sig, name);
        StringBuilder sb = new StringBuilder("<style:style");
        attr(sb, "style:name", name);
        attr(sb, "style:family", family);
        sb.append('>').append(propsXml).append("</style:style>");
        emitted.put(name, sb.toString());
        return name;
    }

    /** &lt;office:automatic-styles&gt; 내부에 들어갈 모든 스타일 XML을 이어붙여 반환. */
    public String emit() {
        StringBuilder sb = new StringBuilder();
        for (String xml : emitted.values()) sb.append(xml);
        return sb.toString();
    }

    boolean isEmpty() { return emitted.isEmpty(); }

    /** esc 재노출(다른 빌더가 안전하게 쓰도록). */
    static String escAttr(String s) { return esc(s); }
}
