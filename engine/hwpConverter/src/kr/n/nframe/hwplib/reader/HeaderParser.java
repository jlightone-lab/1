package kr.n.nframe.hwplib.reader;

import kr.n.nframe.hwplib.constants.HwpxNs;
import kr.n.nframe.hwplib.model.*;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

import static kr.n.nframe.hwplib.reader.XmlHelper.*;

/**
 * header.xml의 hh:head 요소를 파싱해
 * 문서 수준 속성, 폰트, border fill, 글자 모양,
 * 탭 정의, 번호/글머리 속성, 문단 모양, 스타일을 채운다.
 */
public class HeaderParser {

    private static final String HH = HwpxNs.HEAD;
    private static final String HC = HwpxNs.CORE;

    /**
     * 루트 &lt;hh:head&gt; 요소를 파싱해 문서 모델을 채운다.
     */
    public static void parse(Element headElement, HwpDocument doc) {
        if (headElement == null) return;

        // 루트 속성의 secCnt
        doc.docProperties.sectionCount = getAttrInt(headElement, "secCnt", 1);

        // hh:beginNum
        Element beginNum = getChildElement(headElement, HH, "beginNum");
        if (beginNum != null) {
            doc.docProperties.pageStartNum     = getAttrInt(beginNum, "page", 1);
            doc.docProperties.footnoteStartNum = getAttrInt(beginNum, "footnote", 1);
            doc.docProperties.endnoteStartNum  = getAttrInt(beginNum, "endnote", 1);
            doc.docProperties.pictureStartNum  = getAttrInt(beginNum, "pic", 1);
            doc.docProperties.tableStartNum    = getAttrInt(beginNum, "tbl", 1);
            doc.docProperties.equationStartNum = getAttrInt(beginNum, "equation", 1);
        }

        // hh:refList
        Element refList = getChildElement(headElement, HH, "refList");
        if (refList == null) return;

        parseFontfaces(refList, doc);
        parseBorderFills(refList, doc);
        parseCharProperties(refList, doc);
        parseTabProperties(refList, doc);
        parseNumberingProperties(refList, doc);
        parseBulletProperties(refList, doc);
        parseParaProperties(refList, doc);
        parseStyles(refList, doc);

        // 호환 문서 / 레이아웃 플래그 (refList 외부)
        parseCompatibleDocument(headElement, doc);

        // IdMappings 개수 계산
        computeIdMappings(doc);
    }

    /**
     * Parse {@code <hh:compatibleDocument>} and {@code <hh:layoutCompatibility>}
     * from {@code header.xml}. Map HWPX enum to the HWP side:
     * <pre>
     *   HWP201X → 0 (HWPCurrent)
     *   HWP200X → 1 (HWP2007)
     *   MS_WORD → 2 (MSWord)
     * </pre>
     * Missing compatibleDocument element defaults to HWPCurrent/all-zero.
     * For layoutCompatibility, we mirror Hangul's observed behaviour:
     * when MS_WORD profile has the standard 35-flag block, emit non-zero
     * level masks. When empty (self-closing), keep zeros.
     */
    private static void parseCompatibleDocument(Element headEl, HwpDocument doc) {
        Element cd = getChildElement(headEl, HH, "compatibleDocument");
        if (cd == null) { doc.compatTargetProgram = 0; return; }
        String tp = getAttrStr(cd, "targetProgram", "HWP201X");
        switch (tp) {
            case "HWP200X": doc.compatTargetProgram = 1; break;
            case "MS_WORD": doc.compatTargetProgram = 2; break;
            case "HWP201X": default: doc.compatTargetProgram = 0; break;
        }
        Element lc = getChildElement(cd, HH, "layoutCompatibility");
        if (lc == null) return;
        int flagCount = 0;
        org.w3c.dom.NodeList kids = lc.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element) flagCount++;
        }
        if (flagCount > 0) {
            // 한글에서 관측된 MS_WORD 프로파일 레이아웃 레벨
            // (letter/para/section/object/field) — values chosen to match
            // hwplib가 한글 산출 HWP에서 다시 읽어들이는 값
            // 전체 35-플래그 블록. 정확히 일치할 필요는 없으나
            // 한글 렌더링용. 0이 아닌 마스크는 호환 모드를 전환시킴.
            doc.layoutCompatLevels = new long[] {
                    0x00_0001_E3L, // letterLevelFormat
                    0x00_CFF8_FFL, // paragraphLevelFormat
                    0x00_0000_4FL, // sectionLevelFormat
                    0x00_0001_ECL, // objectLevelFormat
                    0x00_0000_00L  // fieldLevelFormat
            };
        } else {
            doc.layoutCompatLevels = new long[] { 0, 0, 0, 0, 0 };
        }
    }

    // ---- 폰트 ----

    private static void parseFontfaces(Element refList, HwpDocument doc) {
        Element fontfaces = getChildElement(refList, HH, "fontfaces");
        if (fontfaces == null) return;

        // 7개 언어 그룹 초기화
        doc.faceNames.clear();
        for (int i = 0; i < 7; i++) {
            doc.faceNames.add(new ArrayList<>());
        }

        List<Element> faceList = getChildElements(fontfaces, HH, "fontface");
        for (Element fontface : faceList) {
            String lang = getAttrStr(fontface, "lang", "");
            int langIdx = langToIndex(lang);

            List<Element> fonts = getChildElements(fontface, HH, "font");
            for (Element font : fonts) {
                FaceName fn = new FaceName();
                fn.name = getAttrStr(font, "face", "");
                String fontType = getAttrStr(font, "type", "");
                fn.fontType = parseFontType(fontType);

                // hh:typeInfo 자식 (PANOSE 유사)
                Element typeInfo = getChildElement(font, HH, "typeInfo");
                if (typeInfo != null) {
                    fn.hasTypeInfo = true;
                    fn.typeInfo = new byte[10];
                    // familyType은 HWPX에서 문자열 enum, HWP에서 숫자
                    fn.typeInfo[0] = (byte) parseFamilyType(getAttrStr(typeInfo, "familyType", ""));
                    fn.typeInfo[1] = (byte) getAttrInt(typeInfo, "serifStyle", 0);
                    fn.typeInfo[2] = (byte) getAttrInt(typeInfo, "weight", 0);
                    fn.typeInfo[3] = (byte) getAttrInt(typeInfo, "proportion", 0);
                    fn.typeInfo[4] = (byte) getAttrInt(typeInfo, "contrast", 0);
                    fn.typeInfo[5] = (byte) getAttrInt(typeInfo, "strokeVariation", 0);
                    fn.typeInfo[6] = (byte) getAttrInt(typeInfo, "armStyle", 0);
                    fn.typeInfo[7] = (byte) getAttrInt(typeInfo, "letterform", 0);
                    fn.typeInfo[8] = (byte) getAttrInt(typeInfo, "midline", 0);
                    fn.typeInfo[9] = (byte) getAttrInt(typeInfo, "xHeight", 0);
                }

                // hh:substFont 자식
                Element substFont = getChildElement(font, HH, "substFont");
                if (substFont != null) {
                    fn.hasAltFont = true;
                    fn.altFontName = getAttrStr(substFont, "face", "");
                    fn.altFontType = parseFontType(getAttrStr(substFont, "type", ""));
                }

                // 기본 폰트명(영문 대응명) — 한글이 폰트 매칭에 사용
                // 알려진 매핑이 없으면 폰트명 자체를 기본값으로 사용
                String defName = getDefaultFontName(fn.name);
                fn.hasDefaultFont = true;
                fn.defaultFontName = (defName != null) ? defName : fn.name;

                if (langIdx >= 0 && langIdx < 7) {
                    doc.faceNames.get(langIdx).add(fn);
                }
            }
        }
    }

    /** margin 자식 요소 값 읽기: <hc:name value="123"/> 또는 <hh:name value="123"/> */
    private static int getMarginChildValue(Element margin, String childName, int defaultVal) {
        // 먼저 hc: 네임스페이스(가장 일반적), 그다음 hh:
        Element child = getChildElement(margin, HC, childName);
        if (child == null) child = getChildElement(margin, HH, childName);
        if (child != null) {
            return getAttrInt(child, "value", defaultVal);
        }
        // 대체: 직접 속성으로 시도 (구형 HWPX 포맷)
        return getAttrInt(margin, childName, defaultVal);
    }

    private static int langToIndex(String lang) {
        if (lang == null) return 0;
        switch (lang) {
            case "HANGUL":   return 0;
            case "LATIN":    return 1;
            case "HANJA":    return 2;
            case "JAPANESE": return 3;
            case "OTHER":    return 4;
            case "SYMBOL":   return 5;
            case "USER":     return 6;
            default:         return 0;
        }
    }

    private static int parseFontType(String type) {
        if ("TTF".equals(type)) return 1;
        if ("HFT".equals(type)) return 2;
        return 0;
    }

    /** HWPX familyType string enum → PANOSE family kind byte */
    private static int parseFamilyType(String ft) {
        if (ft == null || ft.isEmpty()) return 0;
        switch (ft) {
            case "FCAT_MYEONGJO": return 2; // serif (PANOSE: Latin Text)
            case "FCAT_GOTHIC":   return 2; // sans-serif (PANOSE: Latin Text)
            case "FCAT_SCRIPT":   return 3; // script (PANOSE: Latin Hand Written)
            case "FCAT_DECORATIVE": return 4; // decorative
            case "FCAT_FREEFORM": return 5;  // symbol
            default:
                // 숫자로 시도
                try { return Integer.parseInt(ft); } catch (NumberFormatException e) { return 2; }
        }
    }

    /** Korean font name → English default font name mapping (from reference HWP analysis) */
    private static String getDefaultFontName(String korName) {
        if (korName == null) return null;
        switch (korName) {
            case "\uAD74\uB9BC": return "Gulim";                  // 굴림
            case "\uAD74\uB9BC\uCCB4": return "GulimChe";         // 굴림체
            case "\uB3CB\uC6C0": return "Dotum";                  // 돋움
            case "\uB3CB\uC6C0\uCCB4": return "DotumChe";         // 돋움체
            case "\uB9D1\uC740 \uACE0\uB515": return "Malgun Gothic"; // 맑은 고딕
            case "\uBC14\uD0D5": return "Batang";                  // 바탕
            case "\uBC14\uD0D5\uCCB4": return "BatangChe";        // 바탕체
            case "\uD55C\uCEF4\uBC14\uD0D5": return "Haansoft Batang"; // 한컴바탕
            case "\uD568\uCD08\uB86C\uB3CB\uC6C0": return "HCR Dotum";  // 함초롬돋움
            case "\uD568\uCD08\uB86C\uBC14\uD0D5": return "HCR Batang"; // 함초롬바탕
            case "\uD734\uBA3C\uBA85\uC870": return "\uD734\uBA3C\uBA85\uC870"; // 휴먼명조
            case "HY\uACAC\uACE0\uB515": return "HYGothic-Extra";  // HY견고딕
            case "HY\uD5E4\uB4DC\uB77C\uC778M": return "HYHeadLine-Medium"; // HY헤드라인M
            case "\uC2E0\uBA85 \uD0DC\uACE0\uB515": return "Sinmyeong Taegothic"; // 신명 태고딕
            case "\uD55C\uC591\uACAC\uBA85\uC870": return "HY Gyeonmyeongjo"; // 한양견명조
            case "\uD55C\uC591\uC2E0\uBA85\uC870": return "HY Sinmyeongjo"; // 한양신명조
            case "\uC2E0\uBA85 \uC2E0\uBA85\uC870": return "Sinmyeong Sinmyeongjo"; // 신명 신명조
            case "\uC2E0\uBA85 \uC911\uBA85\uC870": return "Sinmyeong Jungmyeongjo"; // 신명 중명조
            case "Arial": return "Arial";
            case "Times New Roman": return "Times New Roman";
            case "Courier New": return "Courier New";
            case "Symbol": return "Symbol";
            case "Wingdings": return "Wingdings";
            default: return null; // 기본 폰트명 없음
        }
    }

    /** slash/backSlash 타입 파싱: NONE=0, LEFT=1, CENTER=2, RIGHT=3, CROOKED=4 등. */
    private static int parseSlashBackSlashType(String type) {
        if (type == null) return 0;
        switch (type.toUpperCase()) {
            case "NONE":            return 0;
            case "LEFT":            return 1;
            case "CENTER":          return 2;
            case "RIGHT":           return 3;
            case "COUNTER_CENTER":  return 3;
            case "CROOKED":         return 4;
            default:                return 0;
        }
    }

    // ---- Border fill ----

    private static void parseBorderFills(Element refList, HwpDocument doc) {
        Element borderFills = getChildElement(refList, HH, "borderFills");
        if (borderFills == null) return;

        List<Element> bfList = getChildElements(borderFills, HH, "borderFill");
        for (Element bfEl : bfList) {
            BorderFill bf = new BorderFill();

            // 속성과 자식 요소에서 property 비트필드
            int threeD   = getAttrBool(bfEl, "threeD", false) ? 1 : 0;
            int shadow   = getAttrBool(bfEl, "shadow", false) ? 1 : 0;
            // slash/backSlash는 자식 요소: <hh:slash type="CENTER" .../>
            Element slashEl2 = getChildElement(bfEl, HH, "slash");
            int slash = 0;
            if (slashEl2 != null) {
                slash = parseSlashBackSlashType(getAttrStr(slashEl2, "type", "NONE"));
            }
            Element backSlashEl2 = getChildElement(bfEl, HH, "backSlash");
            int backSlash = 0;
            if (backSlashEl2 != null) {
                backSlash = parseSlashBackSlashType(getAttrStr(backSlashEl2, "type", "NONE"));
            }
            bf.property = (threeD) | (shadow << 1) | (slash << 2) | (backSlash << 5);

            // Border 면: left=0, right=1, top=2, bottom=3
            String[] borderNames = {"leftBorder", "rightBorder", "topBorder", "bottomBorder"};
            for (int i = 0; i < 4; i++) {
                Element border = getChildElement(bfEl, HH, borderNames[i]);
                if (border != null) {
                    bf.borderTypes[i]  = parseLineType(getAttrStr(border, "type", "NONE"));
                    bf.borderWidths[i] = parseLineWidth(getAttrStr(border, "width", "0.12 mm"));
                    bf.borderColors[i] = parseColor(getAttrStr(border, "color", "#000000"));
                }
            }

            // Diagonal — reference binary analysis (00.hwp):
            // HWP의 diagonal 필드는 line style 템플릿을 저장.
            // <hh:diagonal>이 있을 때(BF 1-4): type과 width 모두 요소에서
            // <hh:diagonal>이 없을 때(BF 5-8): type=0, width는 leftBorder의 width
            // 핵심: diagonal 요소가 없을 때 diagonal 필드는 leftBorder의 width를 그대로 반영.
            Element diag = getChildElement(bfEl, HH, "diagonal");
            if (diag != null) {
                bf.diagType = parseLineType(getAttrStr(diag, "type", "SOLID"));
                bf.diagWidth = parseLineWidth(getAttrStr(diag, "width", "0.1 mm"));
                bf.diagColor = parseColor(getAttrStr(diag, "color", "#000000"));
            } else {
                // diagonal 요소 없음: border[0]의 width를 diagonal width로, type=0
                bf.diagType = 0;
                bf.diagWidth = bf.borderWidths[0]; // 좌측 테두리 폭을 그대로 사용
                bf.diagColor = 0;
            }

            // 채우기 브러시
            // fillBrush는 hh: (head)가 아닌 hc: (core) 네임스페이스 사용
            Element fillBrush = getChildElement(bfEl, HC, "fillBrush");
            if (fillBrush == null) fillBrush = getChildElement(bfEl, HH, "fillBrush"); // fallback
            if (fillBrush != null) {
                parseFillBrush(fillBrush, bf);
            }

            doc.borderFills.add(bf);
        }
    }

    private static void parseFillBrush(Element fillBrush, BorderFill bf) {
        // Window brush (solid/pattern fill)
        Element winBrush = getChildElement(fillBrush, HC, "winBrush");
        if (winBrush == null) winBrush = getChildElement(fillBrush, HH, "winBrush");
        if (winBrush != null) {
            bf.fillType = 1; // solid/pattern
            bf.fillBgColor  = parseColor(getAttrStr(winBrush, "faceColor", "none"));
            bf.fillPatColor = parseColor(getAttrStr(winBrush, "hatchColor", "none"));
            // hatchStyle: -1 또는 없음은 패턴 없음을 의미, HWP에서는 -1(0xFFFFFFFF)로 저장
            int hatchStyle = getAttrInt(winBrush, "hatchStyle", -1);
            bf.fillPatType = hatchStyle; // -1 = no pattern, 0+ = pattern index
        }

        // 그라데이션 fill
        Element gradation = getChildElement(fillBrush, HC, "gradation");
        if (gradation == null) gradation = getChildElement(fillBrush, HH, "gradation");
        if (gradation != null) {
            bf.fillType |= 4; // gradient bit
            String gradType = getAttrStr(gradation, "type", "LINEAR");
            switch (gradType) {
                case "LINEAR":  bf.gradType = 1; break;
                case "RADIAL":  bf.gradType = 2; break;
                case "CONICAL": bf.gradType = 3; break;
                case "SQUARE":  bf.gradType = 4; break;
                default:        bf.gradType = 1; break;
            }
            bf.gradAngle      = getAttrInt(gradation, "angle", 0);
            bf.gradCenterX    = getAttrInt(gradation, "centerX", 0);
            bf.gradCenterY    = getAttrInt(gradation, "centerY", 0);
            bf.gradStep       = getAttrInt(gradation, "step", 50);
            bf.gradStepCenter = getAttrInt(gradation, "stepCenter", 50);

            List<Element> colors = getChildElements(gradation, HC, "color");
            if (colors.isEmpty()) colors = getChildElements(gradation, HH, "color");
            // gradation color stop 은 HWP 규격·한글 UI 상 소수 개(보통 2~3, 최대 수십)이므로
            // 악의적 HWPX 가 수백만 <hc:color> 를 주입해 OOM 을 유발하지 않도록 상한 적용.
            int colorCount = Math.min(colors.size(), 256);
            bf.gradColorNum = colorCount;
            bf.gradColors = new long[colorCount];
            for (int i = 0; i < colorCount; i++) {
                bf.gradColors[i] = parseColor(getAttrStr(colors.get(i), "value", "#000000"));
            }
        }

        // 이미지 브러시
        Element imgBrush = getChildElement(fillBrush, HC, "imgBrush");
        if (imgBrush == null) imgBrush = getChildElement(fillBrush, HH, "imgBrush");
        if (imgBrush != null) {
            bf.fillType |= 2; // image bit
            String mode = getAttrStr(imgBrush, "mode", "TILE");
            switch (mode) {
                case "TILE":     bf.imgType = 0; break;
                case "TILE_HORZ_TOP":    bf.imgType = 1; break;
                case "TILE_HORZ_BOTTOM": bf.imgType = 2; break;
                case "TILE_VERT_LEFT":   bf.imgType = 3; break;
                case "TILE_VERT_RIGHT":  bf.imgType = 4; break;
                case "TOTAL":            bf.imgType = 5; break;
                case "CENTER":           bf.imgType = 6; break;
                case "CENTER_TOP":       bf.imgType = 7; break;
                case "CENTER_BOTTOM":    bf.imgType = 8; break;
                case "LEFT_CENTER":      bf.imgType = 9; break;
                case "LEFT_TOP":         bf.imgType = 10; break;
                case "LEFT_BOTTOM":      bf.imgType = 11; break;
                case "RIGHT_CENTER":     bf.imgType = 12; break;
                case "RIGHT_TOP":        bf.imgType = 13; break;
                case "RIGHT_BOTTOM":     bf.imgType = 14; break;
                case "ABSCALE":          bf.imgType = 15; break;
                default:                 bf.imgType = 0; break;
            }

            // hc:img 자식
            Element img = getChildElement(imgBrush, HwpxNs.CORE, "img");
            if (img != null) {
                bf.imgBright   = getAttrInt(img, "bright", 0);
                bf.imgContrast = getAttrInt(img, "contrast", 0);
                String effect = getAttrStr(img, "effect", "REAL_PIC");
                switch (effect) {
                    case "REAL_PIC":    bf.imgEffect = 0; break;
                    case "GRAY_SCALE":  bf.imgEffect = 1; break;
                    case "BLACK_WHITE": bf.imgEffect = 2; break;
                    case "PATTERN8x8":  bf.imgEffect = 3; break;
                    default:            bf.imgEffect = 0; break;
                }
                // binaryItemIDRef -> 숫자 부분 추출
                String binRef = getAttrStr(img, "binaryItemIDRef", "0");
                bf.imgBinItemId = extractBinId(binRef);
            }
        }
    }

    /**
     * 바이너리 항목 참조 문자열에서 숫자 ID 추출.
     * "BIN0001" 또는 단순 "1" 같은 형식 처리.
     */
    private static int extractBinId(String ref) {
        if (ref == null || ref.isEmpty()) return 0;
        // 뒤따르는 숫자 추출 시도
        StringBuilder digits = new StringBuilder();
        for (int i = ref.length() - 1; i >= 0; i--) {
            char c = ref.charAt(i);
            if (Character.isDigit(c)) {
                digits.insert(0, c);
            } else {
                break;
            }
        }
        if (digits.length() == 0) return 0;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- Character shape ----

    private static void parseCharProperties(Element refList, HwpDocument doc) {
        Element charProps = getChildElement(refList, HH, "charProperties");
        if (charProps == null) return;

        List<Element> cpList = getChildElements(charProps, HH, "charPr");
        for (Element cpEl : cpList) {
            CharShape cs = new CharShape();
            cs.baseSize      = getAttrInt(cpEl, "height", 1000);
            cs.textColor     = parseColor(getAttrStr(cpEl, "textColor", "#000000"));
            cs.shadeColor    = parseColor(getAttrStr(cpEl, "shadeColor", "none"));
            cs.borderFillId  = getAttrInt(cpEl, "borderFillIDRef", 0);

            // hh:fontRef
            Element fontRef = getChildElement(cpEl, HH, "fontRef");
            if (fontRef != null) {
                cs.fontId[0] = getAttrInt(fontRef, "hangul", 0);
                cs.fontId[1] = getAttrInt(fontRef, "latin", 0);
                cs.fontId[2] = getAttrInt(fontRef, "hanja", 0);
                cs.fontId[3] = getAttrInt(fontRef, "japanese", 0);
                cs.fontId[4] = getAttrInt(fontRef, "other", 0);
                cs.fontId[5] = getAttrInt(fontRef, "symbol", 0);
                cs.fontId[6] = getAttrInt(fontRef, "user", 0);
            }

            // hh:ratio
            Element ratio = getChildElement(cpEl, HH, "ratio");
            if (ratio != null) {
                cs.ratio[0] = getAttrInt(ratio, "hangul", 100);
                cs.ratio[1] = getAttrInt(ratio, "latin", 100);
                cs.ratio[2] = getAttrInt(ratio, "hanja", 100);
                cs.ratio[3] = getAttrInt(ratio, "japanese", 100);
                cs.ratio[4] = getAttrInt(ratio, "other", 100);
                cs.ratio[5] = getAttrInt(ratio, "symbol", 100);
                cs.ratio[6] = getAttrInt(ratio, "user", 100);
            }

            // hh:spacing
            Element spacing = getChildElement(cpEl, HH, "spacing");
            if (spacing != null) {
                cs.spacing[0] = getAttrInt(spacing, "hangul", 0);
                cs.spacing[1] = getAttrInt(spacing, "latin", 0);
                cs.spacing[2] = getAttrInt(spacing, "hanja", 0);
                cs.spacing[3] = getAttrInt(spacing, "japanese", 0);
                cs.spacing[4] = getAttrInt(spacing, "other", 0);
                cs.spacing[5] = getAttrInt(spacing, "symbol", 0);
                cs.spacing[6] = getAttrInt(spacing, "user", 0);
            }

            // hh:relSz
            Element relSz = getChildElement(cpEl, HH, "relSz");
            if (relSz != null) {
                cs.relSize[0] = getAttrInt(relSz, "hangul", 100);
                cs.relSize[1] = getAttrInt(relSz, "latin", 100);
                cs.relSize[2] = getAttrInt(relSz, "hanja", 100);
                cs.relSize[3] = getAttrInt(relSz, "japanese", 100);
                cs.relSize[4] = getAttrInt(relSz, "other", 100);
                cs.relSize[5] = getAttrInt(relSz, "symbol", 100);
                cs.relSize[6] = getAttrInt(relSz, "user", 100);
            }

            // hh:offset
            Element offset = getChildElement(cpEl, HH, "offset");
            if (offset != null) {
                cs.charOffset[0] = getAttrInt(offset, "hangul", 0);
                cs.charOffset[1] = getAttrInt(offset, "latin", 0);
                cs.charOffset[2] = getAttrInt(offset, "hanja", 0);
                cs.charOffset[3] = getAttrInt(offset, "japanese", 0);
                cs.charOffset[4] = getAttrInt(offset, "other", 0);
                cs.charOffset[5] = getAttrInt(offset, "symbol", 0);
                cs.charOffset[6] = getAttrInt(offset, "user", 0);
            }

            // 자식 요소에서 property 비트필드 구성
            cs.property = buildCharProperty(cpEl, cs);

            doc.charShapes.add(cs);
        }
    }

    /**
     * charShape의 property 비트필드를 자식 요소들의 존재와 속성으로부터 구성
     * (bold, italic, underline, strikeout, outline, shadow 등).
     */
    private static long buildCharProperty(Element cpEl, CharShape cs) {
        long prop = 0;

        // Bit 0: italic
        Element italic = getChildElement(cpEl, HH, "italic");
        if (italic != null) {
            prop |= (1L);
        }

        // Bit 1: bold
        Element bold = getChildElement(cpEl, HH, "bold");
        if (bold != null) {
            prop |= (1L << 1);
        }

        // Bit 2-3: underline type (2 bits)
        // Bit 4-7: underline shape (4 bits)
        Element underline = getChildElement(cpEl, HH, "underline");
        if (underline != null) {
            int ulType  = parseUnderlineType(getAttrStr(underline, "type", ""));
            int ulShape = parseUnderlineShape(getAttrStr(underline, "shape", ""));
            prop |= ((long) (ulType & 0x3) << 2);
            prop |= ((long) (ulShape & 0xF) << 4);
            cs.underlineColor = parseColor(getAttrStr(underline, "color", "#000000"));
        }

        // Bit 8-10: outline type (3 bits)
        Element outline = getChildElement(cpEl, HH, "outline");
        if (outline != null) {
            int olType = parseOutlineType(getAttrStr(outline, "type", ""));
            prop |= ((long) (olType & 0x7) << 8);
        }

        // Bit 11-12: shadow type (2 bits)
        Element shadow = getChildElement(cpEl, HH, "shadow");
        if (shadow != null) {
            int shType = parseShadowType(getAttrStr(shadow, "type", ""));
            prop |= ((long) (shType & 0x3) << 11);
            cs.shadowColor   = parseColor(getAttrStr(shadow, "color", "#B2B2B2"));
            cs.shadowOffsetX = getAttrInt(shadow, "offsetX", 10);
            cs.shadowOffsetY = getAttrInt(shadow, "offsetY", 10);
        }

        // Bit 13: emboss
        Element emboss = getChildElement(cpEl, HH, "emboss");
        if (emboss != null) {
            prop |= (1L << 13);
        }

        // Bit 14: engrave
        Element engrave = getChildElement(cpEl, HH, "engrave");
        if (engrave != null) {
            prop |= (1L << 14);
        }

        // Bit 15: superscript (HWPX 태그는 "superscript"가 아닌 "supscript")
        Element superscript = getChildElement(cpEl, HH, "supscript");
        if (superscript != null) {
            prop |= (1L << 15);
        }

        // Bit 16: subscript
        Element subscript = getChildElement(cpEl, HH, "subscript");
        if (subscript != null) {
            prop |= (1L << 16);
        }

        // Bit 18-20: strikeout type (3 bits)
        Element strikeout = getChildElement(cpEl, HH, "strikeout");
        if (strikeout != null) {
            int stType = parseStrikeoutType(getAttrStr(strikeout, "type", ""));
            prop |= ((long) (stType & 0x7) << 18);
            cs.strikeColor = parseColor(getAttrStr(strikeout, "color", "#000000"));
        }

        // 속성에서 useFontSpace, useKerning, symMark
        boolean useFontSpace = getAttrBool(cpEl, "useFontSpace", false);
        boolean useKerning   = getAttrBool(cpEl, "useKerning", false);
        int symMark          = getAttrInt(cpEl, "symMark", 0);

        if (useFontSpace) prop |= (1L << 21);
        if (useKerning)   prop |= (1L << 22);
        prop |= ((long) (symMark & 0x3) << 23);

        return prop;
    }

    // ---- Tab 정의 ----

    private static void parseTabProperties(Element refList, HwpDocument doc) {
        Element tabProps = getChildElement(refList, HH, "tabProperties");
        if (tabProps == null) return;

        List<Element> tabList = getChildElements(tabProps, HH, "tabPr");
        for (Element tabEl : tabList) {
            TabDef td = new TabDef();

            boolean autoLeft  = getAttrBool(tabEl, "autoTabLeft", false);
            boolean autoRight = getAttrBool(tabEl, "autoTabRight", false);
            td.property = (autoLeft ? 1L : 0L) | (autoRight ? 2L : 0L);

            // tabItem은 직접 자식이거나 여러 hp:switch 블록 내부에 있을 수 있음
            List<Element> items = getChildElements(tabEl, HH, "tabItem");
            if (items.isEmpty()) {
                // HWPX는 각 tabItem을 자체 hp:switch/hp:case/hp:default로 감쌈
                // 모든 hp:switch 자식을 순회하며 각각에서 tabItem 추출
                String HP = "http://www.hancom.co.kr/hwpml/2011/paragraph";
                List<Element> switches = getChildElements(tabEl, HP, "switch");
                items = new ArrayList<>();
                for (Element switchEl : switches) {
                    // hp:case 우선 (보통 더 정확한 단위 정보 보유)
                    Element caseEl = getChildElement(switchEl, HP, "case");
                    if (caseEl != null) {
                        List<Element> caseItems = getChildElements(caseEl, HH, "tabItem");
                        items.addAll(caseItems);
                    } else {
                        // hp:default로 대체
                        Element defaultEl = getChildElement(switchEl, HP, "default");
                        if (defaultEl != null) {
                            List<Element> defItems = getChildElements(defaultEl, HH, "tabItem");
                            items.addAll(defItems);
                        }
                    }
                }
            }
            for (Element itemEl : items) {
                TabDef.TabItem ti = new TabDef.TabItem();
                ti.position = getAttrInt(itemEl, "pos", 0);
                ti.type     = parseTabType(getAttrStr(itemEl, "type", "LEFT"));
                ti.fillType = parseTabLeader(getAttrStr(itemEl, "leader", "NONE"));
                td.items.add(ti);
            }

            doc.tabDefs.add(td);
        }
    }

    // ---- 번호 매기기 속성 ----

    private static void parseNumberingProperties(Element refList, HwpDocument doc) {
        Element numProps = getChildElement(refList, HH, "numberings");
        if (numProps == null) numProps = getChildElement(refList, HH, "numberingProperties");
        if (numProps == null) return;

        List<Element> numList = getChildElements(numProps, HH, "numbering");
        for (Element numEl : numList) {
            Numbering num = new Numbering();
            num.startNumber = getAttrInt(numEl, "start", 1);

            List<Element> heads = getChildElements(numEl, HH, "paraHead");
            for (int i = 0; i < heads.size() && i < 10; i++) {
                Element phEl = heads.get(i);
                Numbering.ParaHead ph = new Numbering.ParaHead();

                int start      = getAttrInt(phEl, "start", 1);
                int level      = getAttrInt(phEl, "level", 1);
                int align      = parseAlignType(getAttrStr(phEl, "align", "LEFT"));
                boolean useIW  = getAttrBool(phEl, "useInstWidth", false);
                boolean autoIn = getAttrBool(phEl, "autoIndent", false);
                int widthAdj   = getAttrInt(phEl, "widthAdjust", 0);

                String toType  = getAttrStr(phEl, "textOffsetType", "percent");
                int textOffset = getAttrInt(phEl, "textOffset", 50);
                int numFormat  = parseNumberType(getAttrStr(phEl, "numFormat", "DIGIT"));

                // property 구성
                ph.property = (align & 0x3L)
                        | ((useIW ? 1L : 0L) << 2)
                        | ((autoIn ? 1L : 0L) << 3)
                        | (((long)(numFormat & 0xFF)) << 4);
                ph.widthAdjust = widthAdj;
                ph.textOffset  = textOffset;
                ph.charShapeId = getAttrLong(phEl, "charPrIDRef", 0);
                ph.formatString = getTextContent(phEl);

                if (i < 7) {
                    num.paraHeads.add(ph);
                    num.levelStartNumbers[i] = start;
                } else {
                    // 확장 레벨 8-10 (인덱스 7-9)
                    num.extParaHeads.add(ph);
                    num.extLevelStartNumbers[i - 7] = start;
                }
            }

            doc.numberings.add(num);
        }
    }

    // ---- 글머리표 속성 ----

    private static void parseBulletProperties(Element refList, HwpDocument doc) {
        Element bulProps = getChildElement(refList, HH, "bullets");
        if (bulProps == null) bulProps = getChildElement(refList, HH, "bulletProperties");
        if (bulProps == null) return;

        List<Element> bulList = getChildElements(bulProps, HH, "bullet");
        for (Element bulEl : bulList) {
            Bullet bul = new Bullet();
            String charStr = getAttrStr(bulEl, "char", "");
            bul.bulletChar = charStr.isEmpty() ? '\u25CF' : charStr.charAt(0);
            String checkedStr = getAttrStr(bulEl, "checkedChar", "");
            bul.checkBulletChar = checkedStr.isEmpty() ? '\0' : checkedStr.charAt(0);

            Element phEl = getChildElement(bulEl, HH, "paraHead");
            if (phEl != null) {
                Numbering.ParaHead ph = new Numbering.ParaHead();
                int align = parseAlignType(getAttrStr(phEl, "align", "LEFT"));
                boolean useIW = getAttrBool(phEl, "useInstWidth", false);
                boolean autoIn = getAttrBool(phEl, "autoIndent", false);
                ph.property = (align & 0x3L)
                        | ((useIW ? 1L : 0L) << 2)
                        | ((autoIn ? 1L : 0L) << 3);
                ph.widthAdjust = getAttrInt(phEl, "widthAdjust", 0);
                ph.textOffset  = getAttrInt(phEl, "textOffset", 50);
                ph.charShapeId = getAttrLong(phEl, "charPrIDRef", 0);
                ph.formatString = getTextContent(phEl);
                bul.paraHead = ph;
            }

            doc.bullets.add(bul);
        }
    }

    // ---- 문단 모양 ----

    private static void parseParaProperties(Element refList, HwpDocument doc) {
        Element paraProps = getChildElement(refList, HH, "paraProperties");
        if (paraProps == null) return;

        String HP = "http://www.hancom.co.kr/hwpml/2011/paragraph";

        List<Element> ppList = getChildElements(paraProps, HH, "paraPr");
        for (Element ppEl : ppList) {
            ParaShape ps = new ParaShape();

            // 자식 요소와 직접 속성에서 property1 구성
            ps.property1 = buildParaProperty1(ppEl);

            ps.tabDefId = getAttrInt(ppEl, "tabPrIDRef", 0);

            // hh:heading@idRef 자식에서 numberingId 추출 (paraPr의 headingIdRef 아님)
            Element headingEl = getChildElement(ppEl, HH, "heading");
            ps.numberingId = headingEl != null ? getAttrInt(headingEl, "idRef", 0) : 0;

            // hh:margin 자식 - hp:switch/hp:default 내부에 있을 수 있음
            // hp:default 값(표준 HWPUNIT) 사용, hp:case(HwpUnitChar = 절반 값) 아님
            Element margin = getChildElement(ppEl, HH, "margin");
            if (margin == null) {
                List<Element> switches = getChildElements(ppEl, HP, "switch");
                // hp:default 우선 (참조 HWP와 일치하는 표준 HWPUNIT 값)
                for (Element switchEl : switches) {
                    Element defaultEl = getChildElement(switchEl, HP, "default");
                    if (defaultEl != null) {
                        Element defMargin = getChildElement(defaultEl, HH, "margin");
                        if (defMargin != null) {
                            margin = defMargin;
                            break;
                        }
                    }
                }
                // hp:default가 없으면 hp:case로 대체
                if (margin == null) {
                    for (Element switchEl : switches) {
                        Element caseEl = getChildElement(switchEl, HP, "case");
                        if (caseEl != null) {
                            Element caseMargin = getChildElement(caseEl, HH, "margin");
                            if (caseMargin != null) {
                                margin = caseMargin;
                                break;
                            }
                        }
                    }
                }
            }
            if (margin != null) {
                // Margin 값은 자식 요소에 위치: hc:intent, hc:left, hc:right, hc:prev, hc:next
                ps.indent      = getMarginChildValue(margin, "intent", 0);
                ps.leftMargin  = getMarginChildValue(margin, "left", 0);
                ps.rightMargin = getMarginChildValue(margin, "right", 0);
                ps.spaceBefore = getMarginChildValue(margin, "prev", 0);
                ps.spaceAfter  = getMarginChildValue(margin, "next", 0);

                // lineSpacing은 margin 내부가 아닌 형제 요소
                // 동일 부모(switch/case 또는 paraPr)의 형제로 hh:lineSpacing 탐색
                Element lsParent = (Element) margin.getParentNode();
                Element lineSpEl = getChildElement(lsParent, HH, "lineSpacing");
                String lsType = lineSpEl != null ? getAttrStr(lineSpEl, "type", "PERCENT") : "PERCENT";
                int lsValue   = lineSpEl != null ? getAttrInt(lineSpEl, "value", 160) : 160;
                ps.lineSpacing = lsValue;
                ps.lineSpacing2 = lsValue;

                // lineSpacingType은 property1 bit 0-1과 property3 bit 0-4에 모두 들어감
                int lstNum = parseLineSpacingType(lsType);
                // property1의 bit 0-1을 초기화하고 설정
                ps.property1 = (ps.property1 & ~0x3L) | (lstNum & 0x3);
                // property3 bit 0-4: line spacing type
                ps.property3 = (ps.property3 & ~0x1FL) | (lstNum & 0x1F);
            }

            // property2: bit 0-1=lineWrap, bit 4=autoSpaceEAsianEng, bit 5=autoSpaceEAsianNum
            // lineWrap은 hh:breakSetting@lineWrap 자식에서 옴
            Element breakSetting = getChildElement(ppEl, HH, "breakSetting");
            String lineWrap = breakSetting != null
                    ? getAttrStr(breakSetting, "lineWrap", "BREAK") : "BREAK";
            int lwVal2 = "BREAK".equals(lineWrap) ? 0 : "SQUEEZE".equals(lineWrap) ? 1 : 2;

            // autoSpacing 값은 hh:autoSpacing 자식에서 옴
            Element autoSpacing = getChildElement(ppEl, HH, "autoSpacing");
            boolean asEng = autoSpacing != null
                    ? getAttrBool(autoSpacing, "eAsianEng", true) : true;
            boolean asNum = autoSpacing != null
                    ? getAttrBool(autoSpacing, "eAsianNum", true) : true;
            ps.property2 = (lwVal2 & 0x3) | (asEng ? (1L << 4) : 0) | (asNum ? (1L << 5) : 0);

            // hh:border 자식
            Element border = getChildElement(ppEl, HH, "border");
            if (border != null) {
                ps.borderFillId   = getAttrInt(border, "borderFillIDRef", 1);
                ps.borderPadLeft  = getAttrInt(border, "offsetLeft", 0);
                ps.borderPadRight = getAttrInt(border, "offsetRight", 0);
                ps.borderPadTop   = getAttrInt(border, "offsetTop", 0);
                ps.borderPadBottom= getAttrInt(border, "offsetBottom", 0);

                boolean connect   = getAttrBool(border, "connect", false);
                boolean ignoreMgn = getAttrBool(border, "ignoreMargin", false);
                // property1 bit 28과 29에 들어감
                if (connect) ps.property1 |= (1L << 28);
                if (ignoreMgn) ps.property1 |= (1L << 29);
            }

            doc.paraShapes.add(ps);
        }
    }

    /**
     * paraPr 속성과 자식 요소에서 paraShape property1 비트필드 구성.
     * property는 자식 요소(hh:align, hh:breakSetting, hh:heading)에서 가져오며
     * condense, fontLineHeight, snapToGrid는 paraPr의 직접 속성.
     */
    private static long buildParaProperty1(Element ppEl) {
        long prop = 0;

        // Bit 0-1: lineSpacingType (margin 자식에서 추후 설정)

        // Bit 2-4: align (3 bits) - hh:align@horizontal 자식에서
        Element alignEl = getChildElement(ppEl, HH, "align");
        int align = parseAlignType(alignEl != null
                ? getAttrStr(alignEl, "horizontal", "JUSTIFY") : "JUSTIFY");
        prop |= ((long)(align & 0x7) << 2);

        // hh:breakSetting 자식은 breakLatinWord, breakNonLatinWord,
        // widowOrphan, keepWithNext, keepLines, pageBreakBefore를 보유
        Element breakSetting = getChildElement(ppEl, HH, "breakSetting");

        // Bit 5-6: breakLatinWord (2 bits)
        String blw = breakSetting != null
                ? getAttrStr(breakSetting, "breakLatinWord", "KEEP_WORD") : "KEEP_WORD";
        int blwVal;
        switch (blw) {
            case "KEEP_WORD":   blwVal = 0; break;
            case "HYPHENATION": blwVal = 1; break;
            case "BREAK_WORD":  blwVal = 2; break;
            default:            blwVal = 0; break;
        }
        prop |= ((long)(blwVal & 0x3) << 5);

        // Bit 7: breakNonLatinWord (HWP bit 7: 1=KEEP_WORD, 0=BREAK_WORD)
        String bnlwStr = breakSetting != null
                ? getAttrStr(breakSetting, "breakNonLatinWord", "KEEP_WORD") : "KEEP_WORD";
        int bnlw = "KEEP_WORD".equals(bnlwStr) ? 1 : 0;
        prop |= ((long)(bnlw & 0x1) << 7);

        // Bit 8: snapToGrid - paraPr 직접 속성
        boolean snap = getAttrBool(ppEl, "snapToGrid", true);
        if (snap) prop |= (1L << 8);

        // Bit 9-15: condense (7 bits) - paraPr 직접 속성
        int condense = getAttrInt(ppEl, "condense", 0);
        prop |= ((long)(condense & 0x7F) << 9);

        // Bit 16: widowOrphan - hh:breakSetting 자식에서
        boolean wo = breakSetting != null && getAttrBool(breakSetting, "widowOrphan", false);
        if (wo) prop |= (1L << 16);

        // Bit 17: keepWithNext - hh:breakSetting 자식에서
        boolean kwn = breakSetting != null && getAttrBool(breakSetting, "keepWithNext", false);
        if (kwn) prop |= (1L << 17);

        // Bit 18: keepLines - hh:breakSetting 자식에서
        boolean kl = breakSetting != null && getAttrBool(breakSetting, "keepLines", false);
        if (kl) prop |= (1L << 18);

        // Bit 19: pageBreakBefore - hh:breakSetting 자식에서
        boolean pbb = breakSetting != null && getAttrBool(breakSetting, "pageBreakBefore", false);
        if (pbb) prop |= (1L << 19);

        // Bit 20-21: verAlign (2 bits) - hh:align@vertical 자식에서
        int va = parseVertAlign(alignEl != null
                ? getAttrStr(alignEl, "vertical", "BASELINE") : "BASELINE");
        prop |= ((long)(va & 0x3) << 20);

        // Bit 22: fontLineHeight - paraPr 직접 속성
        boolean flh = getAttrBool(ppEl, "fontLineHeight", false);
        if (flh) prop |= (1L << 22);

        // hh:heading 자식은 headingType과 level을 보유
        Element headingEl = getChildElement(ppEl, HH, "heading");

        // Bit 23-24: headingType (2 bits) - hh:heading@type에서
        String ht = headingEl != null
                ? getAttrStr(headingEl, "type", "NONE") : "NONE";
        int htVal;
        switch (ht) {
            case "NONE":    htVal = 0; break;
            case "OUTLINE": htVal = 1; break;
            case "NUMBER":  htVal = 2; break;
            case "BULLET":  htVal = 3; break;
            default:        htVal = 0; break;
        }
        prop |= ((long)(htVal & 0x3) << 23);

        // Bit 25-27: level (3 bits) - hh:heading@level에서
        int level = headingEl != null ? getAttrInt(headingEl, "level", 0) : 0;
        prop |= ((long)(level & 0x7) << 25);

        // Bit 28: connect - hh:border 자식에서 (parseParaProperties에서 추후 설정)
        // Bit 29: ignoreMargin - hh:border 자식에서 (parseParaProperties에서 추후 설정)

        return prop;
    }

    // ---- 스타일 ----

    private static void parseStyles(Element refList, HwpDocument doc) {
        Element styles = getChildElement(refList, HH, "styles");
        if (styles == null) return;

        List<Element> stList = getChildElements(styles, HH, "style");
        for (Element stEl : stList) {
            Style st = new Style();
            st.localName      = getAttrStr(stEl, "name", "");
            st.englishName    = getAttrStr(stEl, "engName", "");
            st.nextStyleId    = getAttrInt(stEl, "nextStyleIDRef", 0);
            // v15.9: HWPX 의 실제 속성명은 "langID" (Ref 접미사 없음).
            //   잘못된 이름으로 읽어 모든 스타일의 langId 가 0 으로 fallback
            //   되어 한/글 참조본의 0x0412 (Korean) 와 byte mismatch.
            st.langId         = getAttrInt(stEl, "langID", 0x0412);
            st.paraShapeId    = getAttrInt(stEl, "paraPrIDRef", 0);
            st.charShapeId    = getAttrInt(stEl, "charPrIDRef", 0);

            String typeStr = getAttrStr(stEl, "type", "PARA");
            switch (typeStr) {
                case "PARA": st.type = 0; break;
                case "CHAR": st.type = 1; break;
                default:     st.type = 0; break;
            }

            doc.styles.add(st);
        }
    }

    // ---- IdMappings 계산 ----

    private static void computeIdMappings(HwpDocument doc) {
        // counts[0] = 전체 binData 개수 (header가 아닌 ZIP에서 설정)
        // counts[1] = HANGUL 그룹 faceNames 개수
        // counts[2] = LATIN 그룹 faceNames 개수
        // counts[3] = HANJA 그룹 faceNames 개수
        // counts[4] = JAPANESE 그룹 faceNames 개수
        // counts[5] = OTHER 그룹 faceNames 개수
        // counts[6] = SYMBOL 그룹 faceNames 개수
        // counts[7] = USER 그룹 faceNames 개수
        // counts[8] = borderFills
        // counts[9] = charShapes
        // counts[10]= tabDefs
        // counts[11]= numberings
        // counts[12]= bullets
        // counts[13]= paraShapes
        // counts[14]= styles
        // counts[15]= memoShapes (여기서 파싱하지 않음)

        int[] c = doc.idMappings.counts;
        for (int i = 0; i < 7; i++) {
            if (i < doc.faceNames.size()) {
                c[1 + i] = doc.faceNames.get(i).size();
            }
        }
        c[8]  = doc.borderFills.size();
        c[9]  = doc.charShapes.size();
        c[10] = doc.tabDefs.size();
        c[11] = doc.numberings.size();
        c[12] = doc.bullets.size();
        c[13] = doc.paraShapes.size();
        c[14] = doc.styles.size();
    }
}
