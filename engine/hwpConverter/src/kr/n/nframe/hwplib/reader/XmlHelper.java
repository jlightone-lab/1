package kr.n.nframe.hwplib.reader;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

/**
 * XML DOM 순회와 속성 파싱을 위한 유틸 메서드
 * HWPX reader 컴포넌트에서 사용.
 */
public final class XmlHelper {

    private XmlHelper() {}

    /**
     * 주어진 네임스페이스 URI와 local name과 일치하는 첫 자식 요소를 반환,
     * 없으면 null 반환.
     */
    public static Element getChildElement(Element parent, String nsUri, String localName) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element e = (Element) n;
                if (matches(e, nsUri, localName)) {
                    return e;
                }
            }
        }
        return null;
    }

    /**
     * 주어진 네임스페이스 URI와 local name과 일치하는 모든 자식 요소 반환.
     */
    public static List<Element> getChildElements(Element parent, String nsUri, String localName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element e = (Element) n;
                if (matches(e, nsUri, localName)) {
                    result.add(e);
                }
            }
        }
        return result;
    }

    /**
     * 네임스페이스/이름과 무관하게 모든 직접 자식 요소 반환.
     */
    public static List<Element> getAllChildElements(Element parent) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) n);
            }
        }
        return result;
    }

    private static boolean matches(Element e, String nsUri, String localName) {
        String ens = e.getNamespaceURI();
        String eln = e.getLocalName();
        // namespace를 모를 경우 tag name으로 대체
        if (eln == null) eln = stripPrefix(e.getTagName());
        if (ens == null) ens = "";
        if (nsUri == null) nsUri = "";
        return nsUri.equals(ens) && localName.equals(eln);
    }

    private static String stripPrefix(String tagName) {
        int idx = tagName.indexOf(':');
        return idx >= 0 ? tagName.substring(idx + 1) : tagName;
    }

    // ---- 속성 헬퍼 ----

    public static int getAttrInt(Element e, String name, int defaultVal) {
        if (e == null) return defaultVal;
        String v = e.getAttribute(name);
        if (v == null || v.isEmpty()) return defaultVal;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException ex) {
            return defaultVal;
        }
    }

    /**
     * {@code getAttrInt} 와 같지만 반환값을 [min, max] 로 클램프한다.
     * 악의적 HWPX 속성(예: {@code colSpan="2147483647"})이 배열 크기나 루프
     * 카운터로 사용되어 OOM/DoS 를 유발하는 것을 막기 위해 사용한다.
     */
    public static int getAttrIntClamped(Element e, String name, int defaultVal, int min, int max) {
        int v = getAttrInt(e, name, defaultVal);
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    public static long getAttrLong(Element e, String name, long defaultVal) {
        if (e == null) return defaultVal;
        String v = e.getAttribute(name);
        if (v == null || v.isEmpty()) return defaultVal;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ex) {
            return defaultVal;
        }
    }

    public static String getAttrStr(Element e, String name, String defaultVal) {
        if (e == null) return defaultVal;
        String v = e.getAttribute(name);
        if (v == null || v.isEmpty()) return defaultVal;
        return v;
    }

    public static boolean getAttrBool(Element e, String name, boolean defaultVal) {
        if (e == null) return defaultVal;
        String v = e.getAttribute(name);
        if (v == null || v.isEmpty()) return defaultVal;
        if ("0".equals(v) || "false".equalsIgnoreCase(v)) return false;
        return true;
    }

    // ---- 값 파서 ----

    /**
     * "#RRGGBB" 같은 색상 문자열을 COLORREF long(0x00BBGGRR)으로 파싱.
     * "none"은 0xFFFFFFFFL(투명/없음) 반환.
     */
    public static long parseColor(String colorStr) {
        if (colorStr == null || colorStr.isEmpty() || "none".equalsIgnoreCase(colorStr)) {
            return 0xFFFFFFFFL;
        }
        colorStr = colorStr.trim();
        if (colorStr.startsWith("#")) {
            colorStr = colorStr.substring(1);
        }
        try {
            long val = Long.parseLong(colorStr, 16);
            if (colorStr.length() > 6) {
                // 8자리 16진수: AARRGGBB -> 0xAABBGGRR로 저장 (alpha 유지)
                long a = (val >> 24) & 0xFF;
                long r = (val >> 16) & 0xFF;
                long g = (val >> 8) & 0xFF;
                long b = val & 0xFF;
                return (a << 24) | (b << 16) | (g << 8) | r;
            } else {
                // 6자리 16진수: RRGGBB -> 0x00BBGGRR로 저장
                long r = (val >> 16) & 0xFF;
                long g = (val >> 8) & 0xFF;
                long b = val & 0xFF;
                return (b << 16) | (g << 8) | r;
            }
        } catch (NumberFormatException e) {
            return 0xFFFFFFFFL;
        }
    }

    /**
     * HWPX border/line 타입 문자열을 숫자 값으로 매핑.
     */
    /**
     * HWPX line 타입을 HWP 바이너리 값으로 매핑.
     * 참조 HWP 바이너리 분석으로 확인된 매핑:
     *   NONE=0, SOLID=1, DASH=3, DOUBLE_SLIM=8
     * HWP 규격서 표에서 추정한 나머지 값 (value+1, None=0):
     *   0=None, 1=Solid, 2=LongDash, 3=Dash/Dot, 4=DashDot, 5=DashDotDot,
     *   6=LongDash2, 7=Circle, 8=DoubleSlim, 9=SlimThick, 10=ThickSlim, 11=SlimThickSlim
     */
    public static int parseLineType(String type) {
        if (type == null || type.isEmpty()) return 0;
        switch (type) {
            case "NONE":            return 0;
            case "SOLID":           return 1;
            case "LONG_DASH":       return 2;  // 규격 1(긴점선) + 1
            case "DASH":            return 3;  // 규격 2(점선/dash) + 1, verified from reference
            case "DOT":             return 4;  // 규격 3(-.-.) + 1
            case "DASH_DOT":        return 5;  // 규격 4(-..-.) + 1
            case "DASH_DOT_DOT":    return 6;  // 규격 5
            case "CIRCLE":          return 7;  // 규격 6
            case "DOUBLE_SLIM":     return 8;  // 참조 파일 대조로 확인
            case "SLIM_THICK":      return 9;
            case "THICK_SLIM":      return 10;
            case "SLIM_THICK_SLIM": return 11;
            default:                return 0;
        }
    }

    /**
     * "0.12 mm" 같은 HWPX line width 문자열을 숫자 값으로 매핑.
     */
    public static int parseLineWidth(String width) {
        if (width == null || width.isEmpty()) return 0;
        width = width.trim();
        // 숫자 부분 추출
        String numPart = width.replace("mm", "").trim();
        try {
            double v = Double.parseDouble(numPart);
            if (v <= 0.1) return 0;
            if (v <= 0.12) return 1;
            if (v <= 0.15) return 2;
            if (v <= 0.2) return 3;
            if (v <= 0.25) return 4;
            if (v <= 0.3) return 5;
            if (v <= 0.4) return 6;
            if (v <= 0.5) return 7;
            if (v <= 0.6) return 8;
            if (v <= 0.7) return 9;
            if (v <= 1.0) return 10;
            if (v <= 1.5) return 11;
            if (v <= 2.0) return 12;
            if (v <= 3.0) return 13;
            if (v <= 4.0) return 14;
            if (v <= 5.0) return 15;
            return 15;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * HWPX alignment 타입 문자열을 숫자 값으로 매핑.
     */
    public static int parseAlignType(String align) {
        if (align == null || align.isEmpty()) return 0;
        switch (align) {
            case "JUSTIFY":          return 0;
            case "LEFT":             return 1;
            case "RIGHT":            return 2;
            case "CENTER":           return 3;
            case "DISTRIBUTE":       return 4;
            case "DISTRIBUTE_SPACE": return 5;
            default:                 return 0;
        }
    }

    /**
     * HWPX numbering 포맷 타입 문자열을 숫자 값으로 매핑.
     */
    public static int parseNumberType(String type) {
        if (type == null || type.isEmpty()) return 0;
        switch (type) {
            case "DIGIT":                return 0;
            case "CIRCLED_DIGIT":        return 1;
            case "ROMAN_CAPITAL":        return 2;
            case "ROMAN_SMALL":          return 3;
            case "LATIN_CAPITAL":        return 4;
            case "LATIN_SMALL":          return 5;
            case "CIRCLED_LATIN_CAPITAL":return 6;
            case "CIRCLED_LATIN_SMALL":  return 7;
            case "HANGUL_SYLLABLE":      return 8;
            case "CIRCLED_HANGUL_SYLLABLE":return 9;
            case "HANGUL_JAMO":          return 10;
            case "CIRCLED_HANGUL_JAMO":  return 11;
            case "HANGUL_PHONETIC":      return 12;
            case "IDEOGRAPH":            return 13;
            case "CIRCLED_IDEOGRAPH":    return 14;
            default:                     return 0;
        }
    }

    /**
     * landscape 방향 문자열을 숫자 값으로 매핑.
     * "WIDELY" -> 1 (가로), "NARROWLY" -> 0 (세로).
     */
    public static int parseLandscape(String v) {
        if ("WIDELY".equals(v)) return 1;
        return 0;
    }

    /**
     * line spacing 타입 문자열을 숫자 값으로 매핑.
     */
    public static int parseLineSpacingType(String type) {
        if (type == null || type.isEmpty()) return 0;
        switch (type) {
            case "PERCENT":         return 0;
            case "FIXED":           return 1;
            case "BETWEEN_LINES":
            case "ONLY_BETWEEN":    return 2;
            case "AT_LEAST":
            case "MINIMUM":         return 3;
            default:                return 0;
        }
    }

    /**
     * tab 타입 문자열을 숫자 값으로 매핑.
     */
    public static int parseTabType(String type) {
        if (type == null || type.isEmpty()) return 0;
        switch (type) {
            case "LEFT":     return 0;
            case "RIGHT":    return 1;
            case "CENTER":   return 2;
            case "DECIMAL":  return 3;
            default:         return 0;
        }
    }

    /**
     * tab leader(채움) 타입 문자열을 숫자 값으로 매핑.
     */
    public static int parseTabLeader(String leader) {
        if (leader == null || leader.isEmpty()) return 0;
        switch (leader) {
            case "NONE":       return 0;
            case "SOLID":      return 1;
            case "DASH":       return 2;
            case "DOT":        return 3;
            case "DASH_DOT":   return 4;
            case "DASH_DOT_DOT":return 5;
            case "LONG_DASH":  return 6;
            case "CIRCLE":     return 7;
            default:           return 0;
        }
    }

    /**
     * column 타입 문자열을 property 값 비트로 매핑.
     */
    public static int parseColumnType(String type) {
        if (type == null || type.isEmpty()) return 0;
        switch (type) {
            case "NEWSPAPER":   return 0;
            case "BALANCED_NEWSPAPER": return 1;
            case "PARALLEL":    return 2;
            default:            return 0;
        }
    }

    /**
     * column layout 문자열을 property 비트로 매핑.
     */
    public static int parseColumnLayout(String layout) {
        if (layout == null || layout.isEmpty()) return 0;
        switch (layout) {
            case "LEFT":   return 0;
            case "RIGHT":  return 1;
            case "MIRROR": return 2;
            default:       return 0;
        }
    }

    /**
     * 세로 정렬 문자열을 숫자 값으로 매핑.
     */
    public static int parseVertAlign(String v) {
        if (v == null || v.isEmpty()) return 0;
        switch (v) {
            case "BASELINE": return 0;
            case "TOP":      return 1;
            case "CENTER":   return 2;
            case "BOTTOM":   return 3;
            default:         return 0;
        }
    }

    /**
     * underline/strikeout 타입 문자열 매핑.
     */
    public static int parseUnderlineType(String type) {
        if (type == null || type.isEmpty()) return 0;
        switch (type.toUpperCase()) {
            case "BOTTOM":  return 1;
            case "CENTER":  return 2;
            case "TOP":     return 3;
            case "NONE":    return 0;
            default:        return 0;
        }
    }

    /**
     * underline/strikeout 모양 문자열 매핑.
     * 참조 파일 대조 확인: SOLID=0, DASH=4, DOT=3 등.
     */
    public static int parseUnderlineShape(String shape) {
        if (shape == null || shape.isEmpty()) return 0;
        switch (shape.toUpperCase()) {
            case "SOLID":        return 0;
            case "DOUBLE":       return 2;
            case "DOT":          return 3;
            case "DASH":         return 4;
            case "DASH_DOT":
            case "DASHDOT":      return 5;
            case "DASH_DOT_DOT":
            case "DASHDOTDOT":   return 6;
            case "LONG_DASH":
            case "LONGDASH":     return 7;
            case "CIRCLE_BELLOW":
            case "CIRCLEBELLOW": return 8;
            case "SLIM_THICK":   return 8;
            case "NONE":         return 0;
            default:             return 0;
        }
    }

    /**
     * outline 타입 문자열 매핑.
     */
    public static int parseOutlineType(String type) {
        if (type == null || type.isEmpty()) return 0;
        switch (type) {
            case "None":    return 0;
            case "Solid":   return 1;
            case "Dash":    return 2;
            case "Dot":     return 3;
            case "DashDot": return 4;
            case "DashDotDot": return 5;
            case "LongDash":return 6;
            default:        return 0;
        }
    }

    /**
     * shadow 타입 문자열 매핑.
     */
    public static int parseShadowType(String type) {
        if (type == null || type.isEmpty()) return 0;
        switch (type) {
            case "None":     return 0;
            case "Drop":     return 1;
            case "Cont":     return 2;
            default:         return 0;
        }
    }

    /**
     * strikeout 타입 문자열 매핑.
     */
    public static int parseStrikeoutType(String type) {
        if (type == null || type.isEmpty()) return 0;
        switch (type) {
            case "None":    return 0;
            case "Erasing": return 1;
            case "Continuous": return 2;
            default:        return 0;
        }
    }

    /**
     * 표용 page break 타입 매핑 — OWPML pageBreak 문자열 → HWP table property bits 0-1.
     *
     * <p>[v15.36 fix] hwp2hwpx 외부 라이브러리의 매핑(HWP→HWPX 방향) 과 일관되도록
     *   역방향 매핑을 통일한다. hwp2hwpx ForTable.tablePageBreak() 매핑:
     *  <ul>
     *    <li>NoDivide (ordinal 0)     → OWPML "NONE"</li>
     *    <li>DivideByCell (ordinal 1) → OWPML "CELL"</li>
     *    <li>Divide (ordinal 2)       → OWPML "TABLE"</li>
     *  </ul>
     *
     *  한/글 UI 표시 (DivideAtPageBoundary 의 enum 이름과 일치):
     *  <ul>
     *    <li>NoDivide   = "나누지 않음" / "한 쪽으로"</li>
     *    <li>DivideByCell = "셀 단위로 나눔"</li>
     *    <li>Divide     = "쪽 경계에서 나눔" / "나눔"</li>
     *  </ul>
     *
     *  이전 매핑 (TABLE→0, CELL→1, NONE→2) 은 hwp2hwpx 와 정반대로 NoDivide ↔ Divide 가
     *  뒤집혀 HWP↔HWPX roundtrip 시 값이 토글되는 버그가 있었다.
     */
    public static int parsePageBreakType(String type) {
        if (type == null || type.isEmpty()) return 1; // 기본값: DivideByCell (Hangul UI 기본 "셀 단위로 나눔")
        switch (type) {
            case "NONE":   return 0;  // NoDivide   ("나누지 않음")
            case "CELL":   return 1;  // DivideByCell ("셀 단위로 나눔")
            case "TABLE":  return 2;  // Divide     ("쪽 경계에서 나눔" / "나눔")
            default:       return 1;
        }
    }

    /**
     * 요소의 텍스트 내용 반환 (직접 자식 텍스트 노드만).
     */
    public static String getTextContent(Element e) {
        if (e == null) return "";
        StringBuilder sb = new StringBuilder();
        NodeList children = e.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString();
    }
}
