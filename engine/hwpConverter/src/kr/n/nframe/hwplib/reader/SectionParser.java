package kr.n.nframe.hwplib.reader;

import kr.n.nframe.hwplib.constants.CtrlId;
import kr.n.nframe.hwplib.constants.HwpxNs;
import kr.n.nframe.hwplib.model.*;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static kr.n.nframe.hwplib.reader.XmlHelper.*;

/**
 * 단일 섹션 XML(hs:sec) 요소를 Section 객체로 파싱
 * (텍스트, 컨트롤, char shape 참조, 라인 세그먼트를 포함하는 문단들).
 */
public class SectionParser {

    private static final String HP = HwpxNs.PARAGRAPH;
    private static final String HS = HwpxNs.SECTION;
    private static final String HC = HwpxNs.CORE;
    private static final String HH = HwpxNs.HEAD;

    /**
     * 단일 문단 텍스트 버퍼 최대 크기 (16 MB).
     * 16 MB 는 WCHAR 기준 ~8M 문자로 정상 문서에서 도달할 수 없는 한도.
     */
    private static final int MAX_PARA_TEXT_BYTES = 16 * 1024 * 1024;

    /** 표·드로잉 중첩 최대 깊이 (StackOverflowError 방어). */
    private static final int MAX_NESTED_DEPTH = 64;

    /**
     * 현재 스레드의 중첩 파싱 깊이. 표 셀 → 문단 → 표, 드로잉 → 문단 → 드로잉 재귀가
     * 악의적 HWPX 의 수천 단계 중첩으로 {@link StackOverflowError} 를 유발하는 것을 막는다.
     */
    private static final ThreadLocal<int[]> NEST_DEPTH = ThreadLocal.withInitial(() -> new int[]{0});

    private static void enterNested() {
        int[] d = NEST_DEPTH.get();
        if (++d[0] > MAX_NESTED_DEPTH) {
            d[0]--;
            throw new IllegalStateException("HWPX nesting depth exceeds limit (" + MAX_NESTED_DEPTH + ")");
        }
    }

    private static void exitNested() {
        int[] d = NEST_DEPTH.get();
        if (d[0] > 0) d[0]--;
    }

    // HWP 문단 텍스트에 사용되는 유니코드 컨트롤 문자 (HWP 5.0 spec §5.4, 표 6)
    private static final char CHAR_SECTION_COLUMN_DEF = 0x0002; // secd/cold (2) extended
    private static final char CHAR_FIELD_BEGIN  = 0x0003; // 필드 시작 (3) 확장형
    private static final char CHAR_INLINE_BEGIN = 0x0004; // 필드 끝 (4) inline
    private static final char CHAR_INLINE_END   = 0x0005; // 예약 inline
    private static final char CHAR_TAB          = 0x0009; // 탭 (9) inline
    private static final char CHAR_FORCED_NEWLINE = 0x000A;
    private static final char CHAR_TABLE        = 0x000B; // 그리기/표 (11) 확장형
    private static final char CHAR_PARA_END     = 0x000D; // 문단 끝 (13) char
    private static final char CHAR_HIDDEN_CMT   = 0x000F; // 숨은 설명 (15) 확장형
    private static final char CHAR_HEADER_FOOTER = 0x0010; // 머리말/꼬리말 (16) 확장형
    private static final char CHAR_FOOTNOTE     = 0x0011; // 각주/미주 (17) 확장형
    private static final char CHAR_AUTO_NUMBER  = 0x0012; // 자동 번호 (18) 확장형
    private static final char CHAR_PAGE_NUM_POS = 0x0015; // 페이지 컨트롤 (21) 확장형
    private static final char CHAR_BOOKMARK     = 0x0016; // 책갈피/찾아보기표 (22) 확장형
    private static final char CHAR_RUBY_TEXT    = 0x0017; // 루비/겹침 (23) 확장형
    private static final char CHAR_NBSPACE      = 0x0018;
    private static final char CHAR_FWSPACE      = 0x0019;
    private static final char CHAR_HYPHEN       = 0x001E;

    /** 고유한 문단 instance ID 생성을 위한 카운터 */
    private static int instanceIdCounter = 1;

    /**
     * 루트 hs:sec 요소를 파싱해 채워진 Section을 반환한다.
     */
    public static Section parse(Element secElement, HwpDocument doc) {
        Section section = new Section();
        if (secElement == null) return section;

        // 섹션 파싱마다 instance ID 카운터 초기화
        instanceIdCounter = 1;

        List<Element> pElements = getChildElements(secElement, HP, "p");
        for (Element pEl : pElements) {
            // v15.8: hp:rect + hp:drawText 의 fallback 처리.
            //   회귀 분석 결과 SHAPE_COMPONENT/SHAPE_COMPONENT_RECTANGLE 의 byte
            //   layout 을 한/글 참조본과 byte-identical 로 맞춰도 (v15.7) 한/글이
            //   계속 "파일이 손상되었습니다" 로 거부 — 알 수 없는 추가 strict
            //   validation 이 존재. 입찰공고문 의 rect 는 본문 텍스트를 담는
            //   "테두리 + 표 형태의 글상자" 라 시각적 frame 손실은 허용되지만
            //   텍스트 내용은 반드시 보존되어야 함. 회귀 안정성을 위해 다음
            //   휴리스틱을 적용:
            //     - 문단의 모든 run 이 hp:rect 만 포함 (다른 텍스트/컨트롤 없음)
            //     - 해당 rect 가 drawText/subList/p 텍스트 본문을 가짐
            //   → 외곽 carrier 문단을 버리고 drawText 내부 문단들을 section-level
            //     paragraph 로 emit. 결과적으로 rect frame 없는 일반 텍스트로
            //     변환되며, rect-specific byte mismatch 가 사라져 한/글 정상 오픈.
            List<Element> drawTextPs = extractRectDrawTextParagraphsIfCarrierOnly(pEl);
            if (drawTextPs != null) {
                for (Element dEl : drawTextPs) {
                    Paragraph dp = parseParagraph(dEl, doc);
                    section.paragraphs.add(dp);
                }
                continue;
            }
            Paragraph para = parseParagraph(pEl, doc);
            section.paragraphs.add(para);
        }

        return section;
    }

    /**
     * hp:p 가 "오직 hp:rect 컨테이너 1 개" 만 포함하고 그 rect 에 drawText 텍스트가
     * 들어 있는 경우, drawText/subList 의 hp:p 리스트를 반환한다. 그 외에는 null.
     * 정상 본문이 있는 문단은 영향받지 않는다.
     */
    private static List<Element> extractRectDrawTextParagraphsIfCarrierOnly(Element pEl) {
        List<Element> runs = getChildElements(pEl, HP, "run");
        if (runs.size() != 1) return null;
        Element run = runs.get(0);
        // run 의 children = 정확히 hp:rect 1개 (옵션: 뒤에 빈 hp:t 가 따라올 수 있음).
        List<Element> runChildren = getAllChildElements(run);
        Element rectEl = null;
        for (Element c : runChildren) {
            String ln = c.getLocalName();
            if (ln == null) ln = stripNsPrefix(c.getTagName());
            if ("rect".equals(ln)) {
                if (rectEl != null) return null; // 두 개 이상 rect — 비표준 carrier 케이스
                rectEl = c;
            } else if ("t".equals(ln)) {
                // 빈 hp:t 는 허용 (rect 뒤의 텍스트 segment 종료자). 비어 있는지 확인.
                String text = c.getTextContent();
                if (text != null && !text.isEmpty()) return null;
            } else {
                return null; // 다른 컨트롤이나 컨텐츠가 있으면 carrier 가 아님
            }
        }
        if (rectEl == null) return null;
        Element only = rectEl;
        Element drawText = getChildElement(only, HP, "drawText");
        if (drawText == null) return null;
        Element subList = getChildElement(drawText, HP, "subList");
        if (subList == null) return null;
        List<Element> ps = getChildElements(subList, HP, "p");
        if (ps.isEmpty()) return null;
        return ps;
    }

    /**
     * hp:p 요소를 Paragraph로 파싱한다.
     */
    static Paragraph parseParagraph(Element pEl, HwpDocument doc) {
        Paragraph para = new Paragraph();
        para.paraShapeId = getAttrInt(pEl, "paraPrIDRef", 0);
        para.styleId     = getAttrInt(pEl, "styleIDRef", 0);

        // v15.6: hp:p 의 'id' 속성을 instanceId 로 사용한다 (HWPML→HWP 직매핑).
        //   한/글이 만든 입찰공고문.hwp 와 byte 비교 결과, 우리 v15.5 출력은
        //   모든 문단의 instanceId 를 0x80000000 으로 하드코드해서 원본의 0,
        //   0x80000000, 그리고 그 외 값들과 충돌. byte-level mismatch 의
        //   가장 큰 비중을 차지. HWPX 의 hp:p id 는 paragraph instance id
        //   그대로이므로 그 값을 사용한다 (id 없거나 음수면 0x80000000 fallback).
        long parsedId = getAttrLong(pEl, "id", -1L);
        para.instanceId = (parsedId < 0) ? 0x80000000L : parsedId;

        boolean pageBreak   = getAttrBool(pEl, "pageBreak", false);
        boolean columnBreak = getAttrBool(pEl, "columnBreak", false);

        // run에서 텍스트와 컨트롤 수집
        List<RunInfo> runs = new ArrayList<>();
        List<Element> runElements = getChildElements(pEl, HP, "run");
        for (Element runEl : runElements) {
            RunInfo ri = parseRun(runEl, doc);
            runs.add(ri);
        }

        // ParaText(원시 UTF-16LE byte)와 CharShapeRef 리스트 구성
        buildParaTextAndCharRefs(para, runs);

        // columnBreakType: 문단에 존재하는 컨트롤로부터 자동 계산
        // 규격 4.3.1 표 59: 0x01=구역, 0x02=다단, 0x04=쪽, 0x08=단
        int breakType = 0;
        for (Control ctrl : para.controls) {
            if (ctrl instanceof CtrlSectionDef) breakType |= 0x01; // 구역 나누기
            if (ctrl instanceof CtrlColumnDef) breakType |= 0x02;  // 다단 나누기
        }
        if (pageBreak) breakType |= 0x04;   // 쪽 나누기
        if (columnBreak) breakType |= 0x08;  // 단 나누기
        para.columnBreakType = breakType;

        // linesegarray 파싱
        Element lineSegArray = getChildElement(pEl, HP, "linesegarray");
        if (lineSegArray != null) {
            List<Element> lsElements = getChildElements(lineSegArray, HP, "lineseg");
            for (Element lsEl : lsElements) {
                LineSeg ls = new LineSeg();
                ls.textStartPos      = getAttrLong(lsEl, "textpos", 0);
                ls.lineVertPos       = getAttrInt(lsEl, "vertpos", 0);
                ls.lineHeight        = getAttrInt(lsEl, "vertsize", 0);
                ls.textHeight        = getAttrInt(lsEl, "textheight", 0);
                ls.baselineDistance   = getAttrInt(lsEl, "baseline", 0);
                ls.lineSpacing       = getAttrInt(lsEl, "spacing", 0);
                ls.columnStartPos    = getAttrInt(lsEl, "horzpos", 0);
                ls.segWidth          = getAttrInt(lsEl, "horzsize", 0);
                ls.tag               = getAttrLong(lsEl, "flags", 0);
                para.lineSegs.add(ls);
            }
        }

        return para;
    }

    // ---- 내부 run 표현 ----

    /**
     * 단일 hp:run과 그 내용에 대한 중간 표현.
     */
    private static class RunInfo {
        int charPrIDRef;
        List<RunItem> items = new ArrayList<>();
    }

    /**
     * run 내부의 단일 항목: 텍스트 또는 컨트롤.
     */
    private static class RunItem {
        enum Type { TEXT, SECTION_DEF, COLUMN_DEF, TABLE, PICTURE, FIELD, FIELD_END, PAGE_NUM_POS, HEADER_FOOTER, TAB }
        Type type;
        String text;          // TEXT 타입용
        Control control;      // 컨트롤 타입용
        char charCode;        // 출력할 컨트롤 문자 코드
        int ctrlId;           // 확장 컨트롤용 4-byte ctrl ID
        int tabWidth;         // TAB 타입용: HWPUNIT 단위 탭 너비
        int tabLeader;        // TAB 타입용: leader 타입
        int tabType;          // TAB 타입용: 탭 타입 (0=left,1=right,2=center,3=decimal)
    }

    /**
     * hp:run 요소를 파싱해 텍스트와 컨트롤을 추출한다.
     */
    private static RunInfo parseRun(Element runEl, HwpDocument doc) {
        RunInfo ri = new RunInfo();
        ri.charPrIDRef = getAttrInt(runEl, "charPrIDRef", 0);

        List<Element> children = getAllChildElements(runEl);
        for (Element child : children) {
            String ln = child.getLocalName();
            if (ln == null) ln = stripNsPrefix(child.getTagName());

            switch (ln) {
                case "t": {
                    // 혼합 내용 파싱: 텍스트 노드와 hp:tab 요소
                    parseTextElement(child, ri);
                    break;
                }
                case "secPr": {
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.SECTION_DEF;
                    item.charCode = CHAR_SECTION_COLUMN_DEF;
                    item.ctrlId = CtrlId.SECTION_DEF;
                    item.control = parseSectionDef(child);
                    ri.items.add(item);
                    break;
                }
                case "ctrl": {
                    parseCtrlElement(child, ri, doc);
                    break;
                }
                case "tbl": {
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.TABLE;
                    item.charCode = CHAR_TABLE;
                    item.ctrlId = CtrlId.TABLE;
                    item.control = parseTable(child, doc);
                    ri.items.add(item);
                    break;
                }
                case "pic": {
                    // 그림은 표와 동일한 코드 0x000B의 'gso ' ctrlId를 사용.
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.PICTURE;
                    item.charCode = CHAR_TABLE; // 그림은 표와 동일한 0x000B 사용
                    item.ctrlId = CtrlId.GSO;
                    item.control = parsePicture(child);
                    ri.items.add(item);
                    break;
                }
                case "equation": {
                    // 수식 개체 — ctrlId 'eqed', gso 확장 사용
                    // 표/그림과 동일한 char code 0x000B. 스크립트, 폰트,
                    // 버전과 baseline 정보는
                    // CtrlPicture 범용 gso 컨테이너 안에 담기고,
                    // SectionWriter가 HWPTAG_EQEDIT로 직렬화한다.
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.PICTURE;
                    item.charCode = CHAR_TABLE; // 0x000B
                    item.ctrlId = CtrlId.EQUATION;
                    item.control = parseEquation(child);
                    ri.items.add(item);
                    break;
                }
                case "rect":
                case "line":
                case "ellipse":
                case "arc":
                case "polygon":
                case "curve":
                case "connectLine": {
                    // 그리기 개체는 코드 0x000B의 'gso ' ctrlId 사용
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.PICTURE; // 모든 gso 개체에 PICTURE 타입 재사용
                    item.charCode = CHAR_TABLE; // 0x000B
                    item.ctrlId = CtrlId.GSO;
                    item.control = parseDrawingObject(child, ln, doc);
                    ri.items.add(item);
                    break;
                }
                case "fieldEnd": {
                    // Field end는 inline 컨트롤 (코드 0x0004, 8 WCHAR = 16 byte)
                    // Control 객체 없음 - 텍스트 스트림에만 삽입
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.FIELD_END;
                    item.charCode = CHAR_INLINE_BEGIN; // 0x0004
                    item.ctrlId = 0; // field end에는 ctrl ID 없음
                    ri.items.add(item);
                    break;
                }
                default:
                    break;
            }
        }

        return ri;
    }

    /**
     * hp:t 혼합 내용 파싱: 텍스트 노드와 hp:tab 자식 요소.
     * tab 요소는 8-WCHAR 확장 컨트롤 문자(코드 0x0009)를 생성.
     */
    private static void parseTextElement(Element tEl, RunInfo ri) {
        NodeList nodes = tEl.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                String text = node.getNodeValue();
                if (text != null && !text.isEmpty()) {
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.TEXT;
                    item.text = text;
                    ri.items.add(item);
                }
            } else if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) node;
                String ln = el.getLocalName();
                if (ln == null) ln = stripNsPrefix(el.getTagName());
                if ("tab".equals(ln)) {
                    // 탭은 8-WCHAR 확장 컨트롤 (코드 0x0009)
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.TAB;
                    item.charCode = CHAR_TAB;
                    item.ctrlId = 0;
                    item.tabWidth  = getAttrInt(el, "width", 0);
                    item.tabLeader = getAttrInt(el, "leader", 0);
                    item.tabType   = getAttrInt(el, "type", 0);
                    ri.items.add(item);
                } else if ("lineBreak".equals(ln)) {
                    // 줄바꿈은 char 타입 컨트롤 (코드 0x000A, 1 WCHAR)
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.TEXT;
                    item.text = String.valueOf(CHAR_FORCED_NEWLINE);
                    ri.items.add(item);
                } else if ("nbSpace".equals(ln)) {
                    // 줄바꿈 없는 공백 (코드 0x0018, 1 WCHAR)
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.TEXT;
                    item.text = String.valueOf(CHAR_NBSPACE);
                    ri.items.add(item);
                } else if ("fwSpace".equals(ln)) {
                    // 전각 공백 (코드 0x0019, 1 WCHAR)
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.TEXT;
                    item.text = String.valueOf(CHAR_FWSPACE);
                    ri.items.add(item);
                }
            }
        }
    }

    /**
     * 여러 하위 컨트롤을 가질 수 있는 hp:ctrl 요소를 파싱한다.
     */
    private static void parseCtrlElement(Element ctrlEl, RunInfo ri, HwpDocument doc) {
        List<Element> children = getAllChildElements(ctrlEl);
        for (Element child : children) {
            String ln = child.getLocalName();
            if (ln == null) ln = stripNsPrefix(child.getTagName());

            switch (ln) {
                case "colPr": {
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.COLUMN_DEF;
                    item.charCode = CHAR_SECTION_COLUMN_DEF;
                    item.ctrlId = CtrlId.COLUMN_DEF;
                    item.control = parseColumnDef(child);
                    ri.items.add(item);
                    break;
                }
                case "pageNum": {
                    // 페이지 번호 위치 컨트롤 (pgnp)
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.PAGE_NUM_POS;
                    item.charCode = CHAR_PAGE_NUM_POS; // 0x0015
                    item.ctrlId = CtrlId.PAGE_NUM_POS;
                    item.control = parsePageNumPos(child);
                    ri.items.add(item);
                    break;
                }
                case "header": {
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.HEADER_FOOTER;
                    item.charCode = CHAR_HEADER_FOOTER; // HWP 5.0 spec 표 6에 따라 0x0010
                    item.ctrlId = CtrlId.HEADER;
                    item.control = parseHeaderFooter(child, CtrlId.HEADER, doc);
                    ri.items.add(item);
                    break;
                }
                case "footer": {
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.HEADER_FOOTER;
                    item.charCode = CHAR_HEADER_FOOTER; // HWP 5.0 spec 표 6에 따라 0x0010
                    item.ctrlId = CtrlId.FOOTER;
                    item.control = parseHeaderFooter(child, CtrlId.FOOTER, doc);
                    ri.items.add(item);
                    break;
                }
                case "autoNum": {
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.PAGE_NUM_POS; // 확장 컨트롤용 타입 재사용
                    item.charCode = CHAR_AUTO_NUMBER; // HWP 5.0 spec 표 6에 따라 0x0012
                    item.ctrlId = CtrlId.AUTO_NUMBER;
                    item.control = new Control(CtrlId.AUTO_NUMBER);
                    ri.items.add(item);
                    break;
                }
                case "newNum": {
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.PAGE_NUM_POS;
                    item.charCode = CHAR_PAGE_NUM_POS; // 0x0015: 새 번호 지정은 페이지 컨트롤 (spec 표 6)
                    item.ctrlId = CtrlId.NEW_NUMBER;
                    item.control = new Control(CtrlId.NEW_NUMBER);
                    ri.items.add(item);
                    break;
                }
                case "pageHide":
                case "pageHiding": {
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.PAGE_NUM_POS;
                    item.charCode = CHAR_PAGE_NUM_POS; // 페이지 컨트롤용 0x0015
                    item.ctrlId = CtrlId.PAGE_HIDE;
                    item.control = new Control(CtrlId.PAGE_HIDE);
                    // hideXxx 속성에서 rawData 구성
                    // bit 0: hideHeader, bit 1: hideFooter, bit 2: hideMasterPage
                    // bit 3: hideBorder, bit 4: hideFill, bit 5: hidePageNum
                    int hideFlags = 0;
                    if (getAttrBool(child, "hideHeader", false)) hideFlags |= 0x01;
                    if (getAttrBool(child, "hideFooter", false)) hideFlags |= 0x02;
                    if (getAttrBool(child, "hideMasterPage", false)) hideFlags |= 0x04;
                    if (getAttrBool(child, "hideBorder", false)) hideFlags |= 0x08;
                    if (getAttrBool(child, "hideFill", false)) hideFlags |= 0x10;
                    if (getAttrBool(child, "hidePageNum", false)) hideFlags |= 0x20;
                    kr.n.nframe.hwplib.binary.HwpBinaryWriter hw =
                        new kr.n.nframe.hwplib.binary.HwpBinaryWriter(4);
                    hw.writeUInt32(hideFlags);
                    item.control.rawData = hw.toByteArray();
                    ri.items.add(item);
                    break;
                }
                case "pageOddEven": {
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.PAGE_NUM_POS;
                    item.charCode = CHAR_PAGE_NUM_POS; // 페이지 컨트롤용 0x0015
                    item.ctrlId = CtrlId.PAGE_ODD_EVEN;
                    item.control = new Control(CtrlId.PAGE_ODD_EVEN);
                    ri.items.add(item);
                    break;
                }
                case "fieldEnd": {
                    // Field end: inline 8-WCHAR 컨트롤 (0x0004), Control 객체 없음
                    RunItem item = new RunItem();
                    item.type = RunItem.Type.FIELD_END;
                    item.charCode = CHAR_INLINE_BEGIN; // 0x0004
                    item.ctrlId = 0;
                    ri.items.add(item);
                    break;
                }
                default:
                    // field 타입 하위 요소 확인
                    int fieldCtrlId = getFieldCtrlId(ln);
                    if (fieldCtrlId != 0) {
                        // fieldBegin의 경우 'type' 속성에서 실제 타입 결정
                        if (fieldCtrlId == CtrlId.FIELD_UNKNOWN && "fieldBegin".equals(ln)) {
                            String fieldType = getAttrStr(child, "type", "");
                            fieldCtrlId = resolveFieldType(fieldType);
                        }
                        RunItem item = new RunItem();
                        item.type = RunItem.Type.FIELD;
                        item.charCode = CHAR_FIELD_BEGIN;
                        item.ctrlId = fieldCtrlId;
                        item.control = parseField(child, fieldCtrlId);
                        ri.items.add(item);
                    }
                    break;
            }
        }
    }

    /**
     * field 타입 요소 이름에 대한 CtrlId를 반환, field가 아니면 0 반환.
     */
    private static int getFieldCtrlId(String localName) {
        if (localName == null) return 0;
        switch (localName) {
            case "fieldBegin":   return CtrlId.FIELD_UNKNOWN;
            case "hyperlink":    return CtrlId.FIELD_HYPERLINK;
            case "bookmark":     return CtrlId.FIELD_BOOKMARK;
            case "clickHere":    return CtrlId.FIELD_CLICKHERE;
            case "formula":      return CtrlId.FIELD_FORMULA;
            case "date":         return CtrlId.FIELD_DATE;
            case "crossRef":     return CtrlId.FIELD_CROSSREF;
            case "privateInfo":  return CtrlId.FIELD_PRIVATE_INFO;
            case "toc":          return CtrlId.FIELD_TOC;
            case "memo":         return CtrlId.FIELD_MEMO;
            default:             return 0;
        }
    }

    /**
     * fieldBegin의 'type' 속성을 실제 CtrlId로 해석.
     */
    private static int resolveFieldType(String type) {
        if (type == null) return CtrlId.FIELD_UNKNOWN;
        switch (type) {
            case "HYPERLINK":    return CtrlId.FIELD_HYPERLINK;
            case "BOOKMARK":     return CtrlId.FIELD_BOOKMARK;
            case "CLICKHERE":    return CtrlId.FIELD_CLICKHERE;
            case "FORMULA":      return CtrlId.FIELD_FORMULA;
            case "DATE":         return CtrlId.FIELD_DATE;
            case "DOCDATE":      return CtrlId.make('%', 'd', 'd', 't');
            case "PATH":         return CtrlId.make('%', 'p', 'a', 't');
            case "CROSSREF":     return CtrlId.FIELD_CROSSREF;
            case "MAILMERGE":    return CtrlId.make('%', 'm', 'm', 'g');
            case "SUMMARY":      return CtrlId.make('%', 's', 'm', 'r');
            case "USERINFO":     return CtrlId.make('%', 'u', 's', 'r');
            case "MEMO":         return CtrlId.FIELD_MEMO;
            case "PRIVATE_INFO": return CtrlId.FIELD_PRIVATE_INFO;
            case "TOC":          return CtrlId.FIELD_TOC;
            default:             return CtrlId.FIELD_UNKNOWN;
        }
    }

    private static String stripNsPrefix(String tagName) {
        int idx = tagName.indexOf(':');
        return idx >= 0 ? tagName.substring(idx + 1) : tagName;
    }

    // ---- Text와 CharShapeRef 구성 ----

    /**
     * 컨트롤 문자 코드가 확장 타입(8-WCHAR = 16 byte)인지 확인.
     */
    private static boolean isExtendedChar(char c) {
        // HWP 5.0 spec 표 6: 확장형/inline 컨트롤 코드 (둘 다 8-WCHAR = 16 byte).
        return c == 0x0002 || c == 0x0003 || c == 0x0004 || c == 0x0005 ||
               c == 0x0009 || c == 0x000B || c == 0x000F ||
               c == 0x0010 || c == 0x0011 || c == 0x0012 ||
               c == 0x0015 || c == 0x0016 || c == 0x0017;
    }

    /**
     * 파싱된 run에서 ParaText 원시 byte와 CharShapeRef 리스트를 구성한다.
     *
     * 컨트롤 문자는 적절한 위치에 삽입된다:
     * - 확장형 (8-WCHAR = 16 byte): 0x0002 (섹션/단 정의), 0x0003 (필드),
     *   0x000B (표/그리기), 0x0015 (페이지 번호 위치)
     * - char 타입 (1 WCHAR = 2 byte): 0x000D (문단 끝)
     *
     * Bug #1 수정: 확장 컨트롤 형식:
     *   [ctrl_code:2][ctrlId_LE_bytes:4][padding:8][ctrl_code_copy:2] = 총 16 byte
     *
     * Bug #2 수정: nChars > 0인 경우에만 ParaText 출력 (bit 31 마스킹 후).
     *
     * Bug #3 수정: controlMask는 각 컨트롤 문자에 대해 (1 << ctrlCode)의 OR 연산.
     */
    private static void buildParaTextAndCharRefs(Paragraph para, List<RunInfo> runs) {
        // 초기 4 KB 로 시작해 필요 시 2배로 확장하며 {@link #MAX_PARA_TEXT_BYTES} 초과 시 예외.
        // 이전 버전의 고정 65536 byte 는 긴 문단 1 개만으로도 BufferOverflowException 을
        // 던지는 DoS 표면이었다.
        GrowingLeBuffer buf = new GrowingLeBuffer(4096);
        int charPos = 0; // WCHAR 단위 위치
        long controlMask = 0;
        int lastCharPrIDRef = -1; // 중복 제거를 위해 이전 run의 charPrIDRef 추적
        int lastFieldCtrlId = 0;  // fieldEnd와 짝을 맞추기 위한 마지막으로 열린 FIELD ctrlId

        for (RunInfo ri : runs) {
            // Fix #2: charPrIDRef가 실제로 변경된 경우에만 새 charShapeRef 항목 추가
            if (ri.charPrIDRef != lastCharPrIDRef) {
                CharShapeRef csr = new CharShapeRef();
                csr.position = charPos;
                csr.charShapeId = ri.charPrIDRef;
                para.charShapeRefs.add(csr);
                lastCharPrIDRef = ri.charPrIDRef;
            }

            for (RunItem item : ri.items) {
                switch (item.type) {
                    case SECTION_DEF:
                    case COLUMN_DEF:
                    case TABLE:
                    case PICTURE:
                    case FIELD:
                    case PAGE_NUM_POS:
                    case HEADER_FOOTER: {
                        // 모두 확장형 8-WCHAR 컨트롤 (16 byte)
                        char cc = item.charCode;
                        int cid = item.ctrlId;
                        writeExtendedControl(buf, cc, cid);
                        controlMask |= (1L << (cc & 0xFFFF));
                        para.controls.add(item.control);
                        charPos += 8; // 8 WCHAR
                        // 짝이 되는 FIELD_END가 pair marker로 사용할 수 있도록
                        // 마지막으로 열린 FIELD ctrlId를 기억 (spec은 '%' high byte를 생략).
                        if (item.type == RunItem.Type.FIELD) {
                            lastFieldCtrlId = cid;
                        }
                        break;
                    }
                    case FIELD_END: {
                        // Field end: inline 8-WCHAR 컨트롤 (코드 0x0004).
                        // Spec 표 6: inline 컨트롤은 최대 12 byte 정보를 전달.
                        // 한컴은 짝이 되는 FIELD_BEGIN의 ctrlId를 high byte('%')를
                        // 0x00으로 대체하여 여기에 기록 — fieldEnd와 fieldBegin을
                        // 연결하는 pair marker 역할. 이 marker가 없으면 한글이
                        // 클릭 가능한 텍스트 범위를 하이퍼링크 FIELD 레코드와
                        // 연결하지 못해 링크가 동작하지 않음.
                        int endMarker = lastFieldCtrlId & 0x00FFFFFF;
                        writeExtendedControl(buf, item.charCode, endMarker);
                        controlMask |= (1L << (item.charCode & 0xFFFF));
                        charPos += 8;
                        lastFieldCtrlId = 0;
                        break;
                    }
                    case TAB: {
                        // 탭은 8-WCHAR 확장 inline 컨트롤 (코드 0x0009)
                        // 형식: code(2) + width(4) + leader(1) + type(1) + pad(6) + code(2) = 16 byte
                        writeTabControl(buf, item.tabWidth, item.tabLeader, item.tabType);
                        controlMask |= (1L << (CHAR_TAB & 0xFFFF));
                        charPos += 8; // 8 WCHAR
                        break;
                    }
                    case TEXT: {
                        if (item.text != null) {
                            for (int i = 0; i < item.text.length(); i++) {
                                char c = item.text.charAt(i);
                                // surrogate pair 처리
                                if (Character.isHighSurrogate(c) && i + 1 < item.text.length()) {
                                    buf.putShort((short) c);
                                    charPos++;
                                    i++;
                                    buf.putShort((short) item.text.charAt(i));
                                    charPos++;
                                } else {
                                    buf.putShort((short) c);
                                    charPos++;
                                    // char 타입 컨트롤 문자도 controlMask 비트를 설정
                                    if (c < 0x0020 && c != CHAR_PARA_END) {
                                        controlMask |= (1L << (c & 0xFFFF));
                                    }
                                }
                            }
                        }
                        break;
                    }
                }
            }
        }

        // 문단 끝 마커 덧붙이기 (1 WCHAR = 2 byte)
        // Fix #1: 0x000D는 char 타입 컨트롤(1 WCHAR)이며, 확장 컨트롤이 아님.
        // controlMask에 포함하면 안 됨. 확장 컨트롤만 controlMask 비트를 설정.
        buf.putShort((short) CHAR_PARA_END);
        charPos++;

        // Bug #3: controlMask 설정
        para.controlMask = controlMask;

        // nChars 설정
        para.nChars = charPos;

        // v15.5: 하이퍼링크용 PARA_RANGE_TAG 자동 추가를 제거함.
        // 입찰공고문.hwp (한/글 원본) 와 byte 비교 결과, 한/글은 하이퍼링크가
        // 있는 문단에 PARA_RANGE_TAG 를 emit 하지 *않음*. 이전 동작 (v15.4
        // 까지) 은 paragraph 마다 4N 개의 spurious tag(48 byte 레코드 × N)
        // 를 추가했고, 한/글 의 엄격한 reader 는 이를 stream 정합성 위반으로
        // 보고 "파일이 손상되었습니다." 로 거부했다. 입찰공고문 손상의
        // 핵심 원인 중 하나.

        // Fix #1: 문단이 문단 끝 marker만 가질 경우(nChars=1, 0x000D 하나뿐),
        // PARA_TEXT를 출력하지 않음. 리더는 nChars=1에서 암묵적 문단 분리를 추론.
        // 실제로 내용이 없는 경우에도 PARA_TEXT를 건너뜀.
        int size = buf.size();
        if (charPos > 1 && size > 2) {
            // 문단 끝 마커 외에 실제 내용이 존재
            para.paraText = new ParaText(buf.toByteArray());
        } else {
            // 빈 문단: nChars=1, PARA_TEXT 없음, controlMask=0
            para.paraText = null;
            para.controlMask = 0;
        }
    }

    /**
     * 2배 증가 전략으로 자동 확장되는 little-endian 바이트 버퍼.
     * {@link #MAX_PARA_TEXT_BYTES} 초과 시 {@link IllegalStateException} 을 던져
     * 악의적으로 부풀린 문단에 의한 OOM 을 방지한다.
     */
    private static final class GrowingLeBuffer {
        private ByteBuffer bb;

        GrowingLeBuffer(int initialCap) {
            bb = ByteBuffer.allocate(Math.max(initialCap, 64));
            bb.order(ByteOrder.LITTLE_ENDIAN);
        }

        private void ensure(int more) {
            if (bb.remaining() >= more) return;
            long needed = (long) bb.position() + more;
            long newCap = Math.max((long) bb.capacity() * 2, needed);
            if (newCap > MAX_PARA_TEXT_BYTES) {
                throw new IllegalStateException("Paragraph text exceeds limit ("
                        + MAX_PARA_TEXT_BYTES + " bytes) — rejecting suspected malicious HWPX");
            }
            ByteBuffer n = ByteBuffer.allocate((int) newCap);
            n.order(ByteOrder.LITTLE_ENDIAN);
            bb.flip();
            n.put(bb);
            bb = n;
        }

        void putShort(short v) { ensure(2); bb.putShort(v); }
        void putInt(int v)     { ensure(4); bb.putInt(v); }
        void put(byte v)       { ensure(1); bb.put(v); }
        int size()             { return bb.position(); }

        byte[] toByteArray() {
            byte[] out = new byte[bb.position()];
            ByteBuffer dup = bb.duplicate();
            dup.order(ByteOrder.LITTLE_ENDIAN);
            dup.flip();
            dup.get(out);
            return out;
        }
    }

    /**
     * 8-WCHAR 확장 컨트롤 시퀀스를 버퍼에 기록.
     *
     * Bug #1 수정: 참조 HWP 바이너리와 일치하는 올바른 형식:
     *   Byte 0-1:   컨트롤 문자 코드 (LE UINT16)
     *   Byte 2-5:   ctrlId (LE UINT32, ctrl 코드 직후 원시 4 byte)
     *   Byte 6-13:  패딩 0 (4 WCHAR = 8 byte)
     *   Byte 14-15: 컨트롤 문자 코드 복사본 (LE UINT16)
     *   총: 16 byte = 8 WCHAR
     *
     * 섹션 정의 예 (ctrlId = 0x73656364 = 'secd'):
     *   0x02 0x00 | 0x64 0x63 0x65 0x73 | 0x00 x8 | 0x02 0x00
     */
    private static void writeExtendedControl(GrowingLeBuffer buf, char charCode, int ctrlId) {
        buf.putShort((short) charCode);    // Byte 0-1: 컨트롤 문자
        buf.putInt(ctrlId);                // Byte 2-5: ctrlId (LE UINT32)
        buf.putInt(0);                     // Byte 6-9: 패딩
        buf.putInt(0);                     // Byte 10-13: 패딩
        buf.putShort((short) charCode);    // Byte 14-15: 컨트롤 문자 복사본
    }

    /**
     * 탭 확장 컨트롤을 기록 (8-WCHAR = 16 byte).
     * 형식: code(2) + width(4) + leader(1) + type(1) + padding(6) + code(2) = 16 byte
     * 패딩 byte는 0x0020 (UTF-16LE의 공백 문자).
     */
    private static void writeTabControl(GrowingLeBuffer buf, int width, int leader, int type) {
        buf.putShort((short) CHAR_TAB);    // Byte 0-1: 0x0009
        buf.putInt(width);                 // Byte 2-5: 탭 너비 (UINT32 LE)
        buf.put((byte) leader);            // Byte 6: leader 타입
        buf.put((byte) type);              // Byte 7: 탭 타입
        buf.putShort((short) 0x0020);      // Byte 8-9: 패딩 (공백)
        buf.putShort((short) 0x0020);      // Byte 10-11: 패딩 (공백)
        buf.putShort((short) 0x0020);      // Byte 12-13: 패딩 (공백)
        buf.putShort((short) CHAR_TAB);    // Byte 14-15: 코드 복사본
    }

    // ---- Control 파서 ----

    /**
     * hp:secPr를 CtrlSectionDef로 파싱.
     */
    private static CtrlSectionDef parseSectionDef(Element secPr) {
        CtrlSectionDef sd = new CtrlSectionDef();

        String textDir = getAttrStr(secPr, "textDirection", "HORIZONTAL");
        sd.property = "VERTICAL".equals(textDir) ? 1 : 0;
        sd.columnGap      = getAttrInt(secPr, "spaceColumns", 1134);
        sd.defaultTabStop  = getAttrInt(secPr, "tabStop", 8000);

        // outlineShapeIDRef -> numberingParaShapeId (직접 값 사용, 오프셋 없음)
        sd.numberingParaShapeId = getAttrInt(secPr, "outlineShapeIDRef", 1);

        // hp:grid
        Element grid = getChildElement(secPr, HP, "grid");
        if (grid != null) {
            sd.vertGrid  = getAttrInt(grid, "lineGrid", 0);
            sd.horizGrid = getAttrInt(grid, "charGrid", 0);
        }

        // hp:startNum
        Element startNum = getChildElement(secPr, HP, "startNum");
        if (startNum != null) {
            sd.pageNumber     = getAttrInt(startNum, "page", 0);
            sd.figNumber      = getAttrInt(startNum, "pic", 0);
            sd.tableNumber    = getAttrInt(startNum, "tbl", 0);
            sd.equationNumber = getAttrInt(startNum, "equation", 0);
        }

        // hp:pagePr -> PageDef
        Element pagePr = getChildElement(secPr, HP, "pagePr");
        if (pagePr != null) {
            PageDef pd = sd.pageDef;
            pd.paperWidth  = getAttrInt(pagePr, "width", 59528);
            pd.paperHeight = getAttrInt(pagePr, "height", 84188);

            // Fix #4: "landscape" 속성 대신 실제 크기로부터 landscape 여부를 유도.
            // HWP 바이너리: 0=좁게(세로, 짧은 쪽이 width), 1=넓게(가로, 긴 쪽이 width).
            // paperWidth >= paperHeight이면 가로(1), 아니면 세로(0).
            int landscape = (pd.paperWidth >= pd.paperHeight) ? 1 : 0;

            String gutterType = getAttrStr(pagePr, "gutterType", "LEFT_ONLY");
            int gutterVal;
            switch (gutterType) {
                case "LEFT_ONLY":   gutterVal = 0; break;
                case "LEFT_RIGHT":  gutterVal = 1; break;
                case "TOP_BOTTOM":  gutterVal = 2; break;
                default:            gutterVal = 0; break;
            }
            pd.property = (landscape & 0x1) | ((gutterVal & 0x3) << 1);

            Element margin = getChildElement(pagePr, HP, "margin");
            if (margin != null) {
                pd.headerMargin = getAttrInt(margin, "header", 4252);
                pd.footerMargin = getAttrInt(margin, "footer", 4252);
                pd.gutterMargin = getAttrInt(margin, "gutter", 0);
                pd.leftMargin   = getAttrInt(margin, "left", 5669);
                pd.rightMargin  = getAttrInt(margin, "right", 5669);
                pd.topMargin    = getAttrInt(margin, "top", 4252);
                pd.bottomMargin = getAttrInt(margin, "bottom", 4252);
            }
        }

        // hp:footNotePr -> FootnoteShape
        Element footNotePr = getChildElement(secPr, HP, "footNotePr");
        if (footNotePr != null) {
            sd.footnoteShape = parseFootnoteShape(footNotePr);
        }

        // hp:endNotePr -> FootnoteShape (미주용)
        Element endNotePr = getChildElement(secPr, HP, "endNotePr");
        if (endNotePr != null) {
            sd.endnoteShape = parseFootnoteShape(endNotePr);
        }

        // hp:pageBorderFill (여러 번 나타날 수 있음)
        List<Element> pbfElements = getChildElements(secPr, HP, "pageBorderFill");
        for (Element pbfEl : pbfElements) {
            PageBorderFill pbf = new PageBorderFill();

            // Fix #3: 타입(BOTH/EVEN/ODD)은 property 필드에 저장되지 않음.
            // 어떤 PG_BF 레코드인지만 결정(모두 동일한 property 비트 공유).
            // Property 비트:
            //   bit 0: 위치 기준 (0=본문, 1=용지) — textBorder 속성에서
            //   bit 1: 머리말 포함
            //   bit 2: 꼬리말 포함
            //   bits 3-4: 채움 영역
            String textBorder = getAttrStr(pbfEl, "textBorder", "PAPER");
            int tbVal = "BODY".equals(textBorder) ? 0 : 1; // PAPER=1

            boolean headerInside  = getAttrBool(pbfEl, "headerInside", false);
            boolean footerInside  = getAttrBool(pbfEl, "footerInside", false);
            String fillAreaStr    = getAttrStr(pbfEl, "fillArea", "PAPER");
            int fillAreaVal = "PAGE".equals(fillAreaStr) ? 1 : "BORDER".equals(fillAreaStr) ? 2 : 0;

            pbf.property = (tbVal & 0x1)
                    | ((headerInside ? 1 : 0) << 1)
                    | ((footerInside ? 1 : 0) << 2)
                    | ((fillAreaVal & 0x3) << 3);

            pbf.borderFillId = getAttrInt(pbfEl, "borderFillIDRef", 1);

            Element offsetEl = getChildElement(pbfEl, HP, "offset");
            if (offsetEl != null) {
                pbf.padLeft   = getAttrInt(offsetEl, "left", 1417);
                pbf.padRight  = getAttrInt(offsetEl, "right", 1417);
                pbf.padTop    = getAttrInt(offsetEl, "top", 1417);
                pbf.padBottom = getAttrInt(offsetEl, "bottom", 1417);
            }

            sd.pageBorderFills.add(pbf);
        }

        return sd;
    }

    /**
     * 각주/미주 속성 요소를 FootnoteShape로 파싱.
     */
    private static FootnoteShape parseFootnoteShape(Element noteEl) {
        FootnoteShape fs = new FootnoteShape();

        // hp:autoNumFormat
        Element autoNum = getChildElement(noteEl, HP, "autoNumFormat");
        if (autoNum != null) {
            int numType = parseNumberType(getAttrStr(autoNum, "type", "DIGIT"));
            int supscr  = getAttrBool(autoNum, "superscript", false) ? 1 : 0;
            fs.property = (numType & 0xFF) | ((supscr & 0x1) << 8);

            String prefix = getAttrStr(autoNum, "prefix", "");
            String suffix = getAttrStr(autoNum, "suffix", ")");
            fs.prefixChar = prefix.isEmpty() ? 0 : prefix.charAt(0);
            fs.suffixChar = suffix.isEmpty() ? 0 : suffix.charAt(0);
        }

        // hp:noteLine
        Element noteLine = getChildElement(noteEl, HP, "noteLine");
        if (noteLine != null) {
            fs.dividerLength    = getAttrInt(noteLine, "length", -1);
            // Fix #5: 각주 구분선 타입은 shifted 매핑 사용:
            // 0=none, 1=solid, 2=dash 등 (solid=0인 테두리 선 타입과 다름).
            fs.dividerLineType  = parseFootnoteDividerLineType(getAttrStr(noteLine, "type", "SOLID"));
            fs.dividerLineWidth = parseLineWidth(getAttrStr(noteLine, "width", "0.12 mm"));
            fs.dividerLineColor = parseColor(getAttrStr(noteLine, "color", "#000000"));
        }

        // hp:noteSpacing
        Element noteSpacing = getChildElement(noteEl, HP, "noteSpacing");
        if (noteSpacing != null) {
            fs.noteBetweenMargin  = getAttrInt(noteSpacing, "betweenNotes", 283);
            fs.dividerBelowMargin = getAttrInt(noteSpacing, "belowLine", 567);
            fs.dividerAboveMargin = getAttrInt(noteSpacing, "aboveLine", 850);
        }

        // hp:numbering
        Element numbering = getChildElement(noteEl, HP, "numbering");
        if (numbering != null) {
            String numType = getAttrStr(numbering, "type", "CONTINUOUS");
            int ntVal;
            switch (numType) {
                case "CONTINUOUS":    ntVal = 0; break;
                case "ON_SECTION":    ntVal = 1; break;
                case "ON_PAGE":       ntVal = 2; break;
                default:              ntVal = 0; break;
            }
            fs.property = (fs.property & 0xFF) | ((long)(ntVal & 0x3) << 8);
            fs.startNumber = getAttrInt(numbering, "newNum", 1);
        }

        return fs;
    }

    /**
     * hp:colPr를 CtrlColumnDef로 파싱.
     */
    private static CtrlColumnDef parseColumnDef(Element colPr) {
        CtrlColumnDef cd = new CtrlColumnDef();

        int colType   = parseColumnType(getAttrStr(colPr, "type", "NEWSPAPER"));
        int colCnt    = getAttrInt(colPr, "cnt", 1);
        int layout    = parseColumnLayout(getAttrStr(colPr, "layout", "LEFT"));
        boolean sameSz  = getAttrBool(colPr, "sameSz", false);
        boolean sameGap = getAttrBool(colPr, "sameGap", false);

        // propertyLow 구성: colType(bit 0-1), colCnt(bit 2-9), layout(bit 10-11), sameSz(bit 12)
        cd.propertyLow = (colType & 0x3)
                | ((colCnt & 0xFF) << 2)
                | ((layout & 0x3) << 10)
                | ((sameSz ? 1 : 0) << 12);

        cd.columnGap = getAttrInt(colPr, "gap", 0);

        // 자식 요소에서 컬럼 너비
        // HWP 단 개수는 property 의 8-bit 필드로 최대 255. 그 이상은 무의미하고
        // 악의적 주입으로 간주하여 클램프.
        List<Element> colSzElements = getChildElements(colPr, HP, "colSz");
        if (!colSzElements.isEmpty()) {
            int cnt = Math.min(colSzElements.size(), 255);
            cd.columnWidths = new int[cnt];
            for (int i = 0; i < cnt; i++) {
                cd.columnWidths[i] = getAttrInt(colSzElements.get(i), "width", 0);
            }
        }

        // 구분선
        Element divider = getChildElement(colPr, HP, "colLine");
        if (divider != null) {
            cd.dividerType  = parseLineType(getAttrStr(divider, "type", "NONE"));
            cd.dividerWidth = parseLineWidth(getAttrStr(divider, "width", "0.12 mm"));
            cd.dividerColor = parseColor(getAttrStr(divider, "color", "#000000"));
        }

        return cd;
    }

    /**
     * hp:tbl을 CtrlTable로 파싱(재귀적 셀 파싱 포함).
     *
     * <p>중첩 표/드로잉이 {@link #MAX_NESTED_DEPTH} 단계를 넘으면 예외를 던져
     * StackOverflowError 로 인한 DoS 를 방지한다.
     */
    private static CtrlTable parseTable(Element tblEl, HwpDocument doc) {
        enterNested();
        try {
            return parseTableImpl(tblEl, doc);
        } finally {
            exitNested();
        }
    }

    private static CtrlTable parseTableImpl(Element tblEl, HwpDocument doc) {
        CtrlTable tbl = new CtrlTable();

        // v15.6: hp:tbl 의 id 속성을 instanceId 로 매핑. 이전엔 0 으로 방치되어
        //   한/글 원본의 CTRL_HEADER (size 46) body offset 36-39 와 mismatch.
        tbl.instanceId = getAttrLong(tblEl, "id", 0L);

        tbl.rowCount     = getAttrInt(tblEl, "rowCnt", 0);
        tbl.colCount     = getAttrInt(tblEl, "colCnt", 0);
        tbl.cellSpacing  = getAttrInt(tblEl, "cellSpacing", 0);
        tbl.borderFillId = getAttrInt(tblEl, "borderFillIDRef", 1);
        tbl.zOrder       = getAttrInt(tblEl, "zOrder", 0);

        // 표 속성 비트 파싱
        String pageBreak = getAttrStr(tblEl, "pageBreak", "TABLE");
        boolean repeatHeader = getAttrBool(tblEl, "repeatHeader", false);
        boolean noAdjust = getAttrBool(tblEl, "noAdjust", false);
        int pbVal = parsePageBreakType(pageBreak);
        tbl.property = (pbVal & 0x3) | ((repeatHeader ? 1 : 0) << 2) | ((noAdjust ? 1 : 0) << 3);

        // hp:sz
        Element sz = getChildElement(tblEl, HP, "sz");
        if (sz != null) {
            tbl.width  = getAttrInt(sz, "width", 0);
            tbl.height = getAttrInt(sz, "height", 0);
        }

        // hp:pos -- Fix #4: 위치 속성으로부터 완전한 objProperty 계산
        Element pos = getChildElement(tblEl, HP, "pos");
        if (pos != null) {
            tbl.vertOffset = getAttrInt(pos, "vertOffset", 0);
            tbl.horzOffset = getAttrInt(pos, "horzOffset", 0);

            boolean treatAsChar = getAttrBool(pos, "treatAsChar", true);
            String vertRelTo  = getAttrStr(pos, "vertRelTo", "PARA");
            String horzRelTo  = getAttrStr(pos, "horzRelTo", "COLUMN");
            String vertAlign  = getAttrStr(pos, "vertAlign", "TOP");
            String horzAlign  = getAttrStr(pos, "horzAlign", "LEFT");
            boolean flowText  = getAttrBool(pos, "flowWithText", false);
            boolean overlap   = getAttrBool(pos, "allowOverlap", false);

            tbl.objProperty = buildObjProperty(treatAsChar, vertRelTo, vertAlign,
                    horzRelTo, horzAlign, flowText, overlap);
        }

        // hp:sz와 hp:tbl 속성에서 확장 obj property 필드
        Element szEl = getChildElement(tblEl, HP, "sz");
        String textWrap = getAttrStr(tblEl, "textWrap", "TOP_AND_BOTTOM");
        String widthRelTo = szEl != null ? getAttrStr(szEl, "widthRelTo", "ABSOLUTE") : "ABSOLUTE";
        String heightRelTo = szEl != null ? getAttrStr(szEl, "heightRelTo", "ABSOLUTE") : "ABSOLUTE";
        boolean sizeProtect = szEl != null && getAttrBool(szEl, "protect", false);
        int widthBasis = parseRelTo(widthRelTo);   // PAPER=0, PAGE=1, COLUMN=2, PARA=3, ABSOLUTE=4
        int heightBasis = parseHeightRelTo(heightRelTo); // PAPER=0, PAGE=1, ABSOLUTE=2
        tbl.objProperty = buildExtendedObjProperty(tbl.objProperty, textWrap,
                widthBasis, heightBasis, sizeProtect, 2);

        // hp:outMargin
        Element outMargin = getChildElement(tblEl, HP, "outMargin");
        if (outMargin != null) {
            tbl.margins[0] = getAttrInt(outMargin, "left", 0);
            tbl.margins[1] = getAttrInt(outMargin, "right", 0);
            tbl.margins[2] = getAttrInt(outMargin, "top", 0);
            tbl.margins[3] = getAttrInt(outMargin, "bottom", 0);
        }

        // hp:inMargin (기본 셀 패딩)
        Element inMargin = getChildElement(tblEl, HP, "inMargin");
        int defPadL = 0, defPadR = 0, defPadT = 0, defPadB = 0;
        if (inMargin != null) {
            defPadL = getAttrInt(inMargin, "left", 141);
            defPadR = getAttrInt(inMargin, "right", 141);
            defPadT = getAttrInt(inMargin, "top", 141);
            defPadB = getAttrInt(inMargin, "bottom", 141);
            tbl.padLeft   = defPadL;
            tbl.padRight  = defPadR;
            tbl.padTop    = defPadT;
            tbl.padBottom = defPadB;
        }

        // 행과 셀 파싱
        List<Integer> cellsPerRow = new ArrayList<>();
        List<Element> trElements = getChildElements(tblEl, HP, "tr");
        for (Element trEl : trElements) {
            List<Element> tcElements = getChildElements(trEl, HP, "tc");
            for (Element tcEl : tcElements) {
                TableCell cell = parseTableCell(tcEl, doc, defPadL, defPadR, defPadT, defPadB);
                tbl.cells.add(cell);
            }
            cellsPerRow.add(tcElements.size());
        }

        // Row heights: 값 = 해당 행의 셀 수 (참조 바이너리에서 확인됨).
        // HWP rowCnt 는 UINT16 (0..65535) 이므로 그 이상은 거부하여 OOM 방어.
        int rowCnt = Math.min(cellsPerRow.size(), 65535);
        tbl.rowHeights = new int[rowCnt];
        for (int i = 0; i < rowCnt; i++) {
            tbl.rowHeights[i] = cellsPerRow.get(i);
        }

        // zoneCount
        Element zoneList = getChildElement(tblEl, HP, "cellzoneList");
        if (zoneList != null) {
            tbl.zoneCount = getAttrInt(zoneList, "count", 0);
        }

        return tbl;
    }

    /**
     * hp:tc를 TableCell로 파싱(하위 문단 포함).
     */
    private static TableCell parseTableCell(Element tcEl, HwpDocument doc,
                                            int defPadL, int defPadR, int defPadT, int defPadB) {
        TableCell cell = new TableCell();
        cell.borderFillId = getAttrInt(tcEl, "borderFillIDRef", 1);

        // cellAddr: colAddr/rowAddr를 갖는 자식 요소
        Element cellAddr = getChildElement(tcEl, HP, "cellAddr");
        if (cellAddr != null) {
            cell.colAddr = getAttrInt(cellAddr, "colAddr", 0);
            cell.rowAddr = getAttrInt(cellAddr, "rowAddr", 0);
        }
        // cellSpan: colSpan/rowSpan을 갖는 자식 요소
        Element cellSpan = getChildElement(tcEl, HP, "cellSpan");
        if (cellSpan != null) {
            cell.colSpan = getAttrInt(cellSpan, "colSpan", 1);
            cell.rowSpan = getAttrInt(cellSpan, "rowSpan", 1);
        }
        // cellSz: width/height를 갖는 자식 요소
        Element cellSz = getChildElement(tcEl, HP, "cellSz");
        if (cellSz != null) {
            cell.width  = getAttrInt(cellSz, "width", 0);
            cell.height = getAttrInt(cellSz, "height", 0);
        }

        // hp:cellMargin
        Element cellMargin = getChildElement(tcEl, HP, "cellMargin");
        if (cellMargin != null) {
            cell.margins[0] = getAttrInt(cellMargin, "left", defPadL);
            cell.margins[1] = getAttrInt(cellMargin, "right", defPadR);
            cell.margins[2] = getAttrInt(cellMargin, "top", defPadT);
            cell.margins[3] = getAttrInt(cellMargin, "bottom", defPadB);
        } else {
            cell.margins[0] = defPadL;
            cell.margins[1] = defPadR;
            cell.margins[2] = defPadT;
            cell.margins[3] = defPadB;
        }

        // hp:subList는 셀 내부 문단을 포함
        Element subList = getChildElement(tcEl, HP, "subList");
        if (subList != null) {
            // LIST_HEADER용 subList 속성 파싱
            String textDir  = getAttrStr(subList, "textDirection", "HORIZONTAL");
            String lineWrap = getAttrStr(subList, "lineWrap", "BREAK");
            String vertAlign = getAttrStr(subList, "vertAlign", "TOP");

            // listHeaderProperty 구성: textDirection(bit 0-2), lineWrap(bit 3-4), vertAlign(bit 5-6)
            // 주의: paraCount가 INT32이면 property가 올바른 offset에 있고 원래 bit 레이아웃 사용
            int tdVal = "VERTICAL".equals(textDir) ? 1 : 0;
            int lwVal;
            switch (lineWrap) {
                case "BREAK":    lwVal = 0; break;
                case "SQUEEZE":  lwVal = 1; break;
                case "KEEP":     lwVal = 2; break;
                default:         lwVal = 0; break;
            }
            int vaVal;
            switch (vertAlign) {
                case "TOP":      vaVal = 0; break;
                case "CENTER":   vaVal = 1; break;
                case "BOTTOM":   vaVal = 2; break;
                default:         vaVal = 0; break;
            }
            // hp:tc 속성의 hasMargin -> property의 bit 16
            boolean hasMargin = getAttrBool(tcEl, "hasMargin", false);
            // hp:tc 속성의 header -> property의 bit 18 (표 헤더 행 반복)
            boolean isHeader = getAttrBool(tcEl, "header", false);
            cell.listHeaderProperty = (tdVal & 0x7) | ((lwVal & 0x3) << 3) | ((vaVal & 0x3) << 5)
                    | (hasMargin ? (1 << 16) : 0)
                    | (isHeader ? (1 << 18) : 0);

            // textWidth/textHeight: subList 값을 사용, 없으면 cellSz 값으로 대체
            int tw = getAttrInt(subList, "textWidth", 0);
            int th = getAttrInt(subList, "textHeight", 0);
            // HWPX가 0인 경우 셀 width 사용 (참조 동작과 일치)
            cell.textWidth  = (tw > 0) ? tw : cell.width;
            cell.textHeight = (th > 0) ? th : cell.height;

            cell.listHeaderParaCount = 0;
            List<Element> pElements = getChildElements(subList, HP, "p");
            for (Element pEl : pElements) {
                Paragraph para = parseParagraph(pEl, doc);
                cell.paragraphs.add(para);
            }
            cell.listHeaderParaCount = cell.paragraphs.size();
        }

        return cell;
    }

    /**
     * hp:pageNum 컨트롤을 PAGE_NUM_POS ctrlId의 Control로 파싱.
     */
    private static Control parsePageNumPos(Element pageNumEl) {
        Control ctrl = new Control(CtrlId.PAGE_NUM_POS);
        // HWP 바이너리 포맷에 맞춰 원시 property 데이터 구성:
        // UINT32 property (bit 0-7: numberFormat, bit 8-11: 위치)
        // WCHAR userChar / prefixChar / suffixChar / sideChar (각 WCHAR)
        String pos = getAttrStr(pageNumEl, "pos", "NONE");
        String fmt = getAttrStr(pageNumEl, "formatType", "DIGIT");
        String sideCharStr = getAttrStr(pageNumEl, "sideChar", "-");

        int posVal = 0;
        switch (pos) {
            case "NONE": posVal = 0; break;
            case "TOP_LEFT": posVal = 1; break;
            case "TOP_CENTER": posVal = 2; break;
            case "TOP_RIGHT": posVal = 3; break;
            case "BOTTOM_LEFT": posVal = 4; break;
            case "BOTTOM_CENTER": posVal = 5; break;
            case "BOTTOM_RIGHT": posVal = 6; break;
            case "OUTSIDE_TOP": posVal = 7; break;
            case "OUTSIDE_BOTTOM": posVal = 8; break;
            case "INSIDE_TOP": posVal = 9; break;
            case "INSIDE_BOTTOM": posVal = 10; break;
        }
        int numFmt = XmlHelper.parseNumberType(fmt);
        long property = (numFmt & 0xFF) | ((posVal & 0xF) << 8);

        kr.n.nframe.hwplib.binary.HwpBinaryWriter w = new kr.n.nframe.hwplib.binary.HwpBinaryWriter(12);
        w.writeUInt32(property);
        w.writeWChar('\0');  // userChar
        w.writeWChar('\0');  // prefixChar
        w.writeWChar('\0');  // suffixChar
        char sideChar = sideCharStr.isEmpty() ? '-' : sideCharStr.charAt(0);
        w.writeWChar(sideChar);
        ctrl.rawData = w.toByteArray();
        return ctrl;
    }

    /**
     * hp:header 또는 hp:footer를 CtrlHeaderFooter로 파싱.
     */
    private static CtrlHeaderFooter parseHeaderFooter(Element el, int ctrlId, HwpDocument doc) {
        CtrlHeaderFooter hf = new CtrlHeaderFooter(ctrlId);

        // v15.6: header/footer 의 applyPageType 을 property bit 0-1 로 인코딩.
        //   0=BOTH, 1=EVEN_ONLY, 2=ODD_ONLY (HWP 5.0 spec)
        String apt = getAttrStr(el, "applyPageType", "BOTH");
        if ("EVEN_ONLY".equals(apt)) hf.property = 1L;
        else if ("ODD_ONLY".equals(apt)) hf.property = 2L;
        else hf.property = 0L;

        // 머리말/꼬리말 내부 하위 문단 + textWidth/textHeight 파싱
        Element subList = getChildElement(el, HP, "subList");
        if (subList != null) {
            // v15.6: subList 의 textWidth/textHeight 를 추출 — 이전엔 0 으로 방치되어
            // 한/글 원본과 byte mismatch 발생. (HWP LIST_HEADER 의 textWidth/Height
            // 필드를 정확히 채워야 한/글이 본문 영역 크기를 올바르게 인식.)
            hf.textWidth  = getAttrInt(subList, "textWidth", 0);
            hf.textHeight = getAttrInt(subList, "textHeight", 0);
            hf.textRefFlag = getAttrBool(subList, "hasTextRef", false) ? 1 : 0;
            hf.numRefFlag  = getAttrBool(subList, "hasNumRef", false) ? 1 : 0;

            List<Element> pElements = getChildElements(subList, HP, "p");
            for (Element pEl : pElements) {
                Paragraph para = parseParagraph(pEl, doc);
                hf.paragraphs.add(para);
            }
        }

        return hf;
    }

    /**
     * field 타입 컨트롤 요소를 CtrlField로 파싱.
     */
    private static CtrlField parseField(Element fieldEl, int ctrlId) {
        CtrlField field = new CtrlField(ctrlId);

        // 'id' 속성에서 fieldId
        field.fieldId = getAttrLong(fieldEl, "id", 0);

        // 'name' 속성 (BOOKMARK 필드용 - HWPTAG_CTRL_DATA 출력 유발)
        field.name = getAttrStr(fieldEl, "name", "");

        // UINT32 속성 (HWP5 spec 표 153): bit 0 = editable, bit 15 = dirty (modified)
        boolean dirty = getAttrBool(fieldEl, "dirty", false);
        boolean editable = getAttrBool(fieldEl, "editable", false);
        field.fieldProperty = (editable ? 1L : 0L) | (dirty ? 0x8000L : 0L);

        // hp:parameters에서 Command 문자열과 Prop 값 파싱.
        // 주의: HWPML "Property" (hp:integerParam name="Prop")는 HWP5 필드 레코드의
        //       BYTE 기타 속성에 매핑됨 (UINT32 속성이 아님). UINT32 속성은 오로지
        //       위의 'editable' / 'dirty' 속성으로부터 유도됨.
        //       (한컴 생성 참조 HWP로 확인: HYPERLINK Prop=0/dirty=1이면
        //        fieldProperty=0x8000 extra=0; BOOKMARK Prop=2/dirty=0이면
        //        fieldProperty=0 extra=2.)
        Element params = getChildElement(fieldEl, HP, "parameters");
        if (params != null) {
            List<Element> children = getAllChildElements(params);
            for (Element param : children) {
                String paramName = getAttrStr(param, "name", "");
                String localTag = param.getLocalName();
                if (localTag == null) localTag = param.getTagName();

                if ("Command".equals(paramName)) {
                    field.command = getTextContent(param);
                } else if ("Prop".equals(paramName)) {
                    try {
                        String val = getTextContent(param).trim();
                        if (!val.isEmpty()) {
                            field.extraProperty = Integer.parseInt(val) & 0xFF;
                        }
                    } catch (NumberFormatException e) { /* 무시 */ }
                }
            }
        }

        // 대체: 'command' 속성을 직접 시도
        if ((field.command == null || field.command.isEmpty())) {
            field.command = getAttrStr(fieldEl, "command", "");
        }

        return field;
    }

    /**
     * hp:pic을 CtrlPicture로 파싱.
     */
    /**
     * 그리기 개체(rect, line, ellipse 등)를 CtrlPicture로 파싱.
     * 범용 gso 컨테이너로 CtrlPicture 사용 - shapeType 필드가
     * 서로 다른 그리기 개체 종류를 구분.
     */
    /**
     * {@code <hp:equation>} 요소를 {@code shapeType="equation"} 및
     * 수식 전용 필드가 채워진 CtrlPicture로 파싱한다.
     * SectionWriter가 shape 타입을 인식하여 공유 gso CTRL_HEADER 뒤에
     * HWPTAG_EQEDIT 레코드를 출력.
     */
    private static CtrlPicture parseEquation(Element eqEl) {
        CtrlPicture obj = new CtrlPicture();
        obj.shapeType = "equation";
        // 기본 GSO ctrlId를 재정의 — 수식은 CTRL_HEADER에서 'eqed' ctrlId를
        // 사용하여 문단의 0x000B 확장 컨트롤(ctrlId도 반드시 'eqed')과의
        // 짝이 한글에서 인식되도록 함. 여기에 대신 GSO를 쓰면 파일이
        // "파일이 손상되었습니다"로 열림.
        obj.ctrlId = CtrlId.EQUATION;

        // 요소 자체로부터의 수식 속성
        obj.eqVersion = getAttrStr(eqEl, "version", "Equation Version 60");
        obj.eqFont = getAttrStr(eqEl, "font", "HancomEQN");
        obj.eqBaseUnit = getAttrInt(eqEl, "baseUnit", 1000);
        obj.eqBaseLine = getAttrInt(eqEl, "baseLine", 86);

        // 공유 개체 기하 정보 — 그림/그리기 개체와 동일
        Element sz = getChildElement(eqEl, HP, "sz");
        if (sz != null) {
            obj.width = getAttrInt(sz, "width", 0);
            obj.height = getAttrInt(sz, "height", 0);
        }
        Element pos = getChildElement(eqEl, HP, "pos");
        if (pos != null) {
            boolean treatAsChar = getAttrBool(pos, "treatAsChar", false);
            String vertRelTo = getAttrStr(pos, "vertRelTo", "PARA");
            String vertAlign = getAttrStr(pos, "vertAlign", "TOP");
            String horzRelTo = getAttrStr(pos, "horzRelTo", "PARA");
            String horzAlign = getAttrStr(pos, "horzAlign", "LEFT");
            boolean flowText = getAttrBool(pos, "flowWithText", false);
            boolean overlap  = getAttrBool(pos, "allowOverlap", false);
            obj.objProperty = buildObjProperty(treatAsChar, vertRelTo, vertAlign,
                    horzRelTo, horzAlign, flowText, overlap);
            obj.vertOffset = getAttrInt(pos, "vertOffset", 0);
            obj.horzOffset = getAttrInt(pos, "horzOffset", 0);
        }
        Element outMargin = getChildElement(eqEl, HP, "outMargin");
        if (outMargin != null) {
            obj.margins[0] = getAttrInt(outMargin, "left", 0);
            obj.margins[1] = getAttrInt(outMargin, "right", 0);
            obj.margins[2] = getAttrInt(outMargin, "top", 0);
            obj.margins[3] = getAttrInt(outMargin, "bottom", 0);
        }
        Element shapeComment = getChildElement(eqEl, HP, "shapeComment");
        if (shapeComment != null) obj.description = getTextContent(shapeComment);
        Element script = getChildElement(eqEl, HP, "script");
        if (script != null) obj.eqScript = getTextContent(script);

        obj.zOrder = getAttrInt(eqEl, "zOrder", 0);
        obj.pageBreakPrev = 0;
        obj.instanceId = getAttrLong(eqEl, "id", 0L);

        return obj;
    }

    private static CtrlPicture parseDrawingObject(Element drawEl, String shapeType, HwpDocument doc) {
        enterNested();
        try {
            return parseDrawingObjectImpl(drawEl, shapeType, doc);
        } finally {
            exitNested();
        }
    }

    private static CtrlPicture parseDrawingObjectImpl(Element drawEl, String shapeType, HwpDocument doc) {
        CtrlPicture obj = new CtrlPicture();
        obj.shapeType = shapeType; // "rect", "line", "ellipse" 등

        // v15.6: 그리기 도형(rect/line/...) 의 id 도 instanceId 로 매핑.
        // 아래쪽에서 'instid' 속성도 별도로 처리되지만, HWPML 의 hp:rect/hp:line
        // 최상위 id 가 표준. 둘 다 있으면 instid 가 덮어쓴다(아래 line 1438).
        obj.instanceId = getAttrLong(drawEl, "id", 0L);

        // 그림과 동일한 공통 개체 속성 파싱
        Element sz = getChildElement(drawEl, HP, "sz");
        if (sz != null) {
            obj.width  = getAttrInt(sz, "width", 0);
            obj.height = getAttrInt(sz, "height", 0);
        }

        // v15.7: hp:orgSz (스케일링 전 원본 크기) — SHAPE_COMPONENT initialWidth/Height
        //   및 SHAPE_COMPONENT_RECTANGLE 의 4 꼭짓점 좌표에 사용된다.
        //   누락 시 한/글이 "파일이 손상되었습니다" 로 거부 — 입찰공고문 회귀의 진짜 원인.
        Element orgSzEl = getChildElement(drawEl, HP, "orgSz");
        if (orgSzEl != null) {
            obj.originalWidth  = getAttrInt(orgSzEl, "width", obj.width);
            obj.originalHeight = getAttrInt(orgSzEl, "height", obj.height);
        }

        // v15.7: hp:renderingInfo → transMatrix/scaMatrix/rotMatrix
        //   SHAPE_COMPONENT 의 groupXOffset/YOffset 및 affine 행렬에 사용.
        //   누락 시 객체 위치가 한/글에서 0,0 으로 그려져 손상으로 인식될 수 있음.
        Element renderInfoEl = getChildElement(drawEl, HP, "renderingInfo");
        if (renderInfoEl != null) {
            Element trans = getChildElement(renderInfoEl, HC, "transMatrix");
            if (trans != null) obj.transMatrix = parseMatrix6(trans);
            Element sca = getChildElement(renderInfoEl, HC, "scaMatrix");
            if (sca != null) obj.scaMatrix = parseMatrix6(sca);
            Element rot = getChildElement(renderInfoEl, HC, "rotMatrix");
            if (rot != null) obj.rotMatrix = parseMatrix6(rot);
        }

        Element pos = getChildElement(drawEl, HP, "pos");
        if (pos != null) {
            boolean treatAsChar = getAttrBool(pos, "treatAsChar", false);
            String vertRelTo  = getAttrStr(pos, "vertRelTo", "PARA");
            String vertAlign  = getAttrStr(pos, "vertAlign", "TOP");
            String horzRelTo  = getAttrStr(pos, "horzRelTo", "COLUMN");
            String horzAlign  = getAttrStr(pos, "horzAlign", "LEFT");
            boolean flowText  = getAttrBool(pos, "flowWithText", false);
            boolean overlap   = getAttrBool(pos, "allowOverlap", false);
            obj.objProperty = buildObjProperty(treatAsChar, vertRelTo, vertAlign,
                    horzRelTo, horzAlign, flowText, overlap);
            obj.vertOffset = getAttrInt(pos, "vertOffset", 0);
            obj.horzOffset = getAttrInt(pos, "horzOffset", 0);
        }

        // 확장 obj 속성
        String textWrap = getAttrStr(drawEl, "textWrap", "TOP_AND_BOTTOM");
        String widthRelTo = sz != null ? getAttrStr(sz, "widthRelTo", "ABSOLUTE") : "ABSOLUTE";
        String heightRelTo = sz != null ? getAttrStr(sz, "heightRelTo", "ABSOLUTE") : "ABSOLUTE";
        boolean sizeProtect = sz != null && getAttrBool(sz, "protect", false);
        obj.objProperty = buildExtendedObjProperty(obj.objProperty, textWrap,
                parseRelTo(widthRelTo), parseHeightRelTo(heightRelTo), sizeProtect, 0);

        Element outMargin = getChildElement(drawEl, HP, "outMargin");
        if (outMargin != null) {
            obj.margins[0] = getAttrInt(outMargin, "left", 0);
            obj.margins[1] = getAttrInt(outMargin, "right", 0);
            obj.margins[2] = getAttrInt(outMargin, "top", 0);
            obj.margins[3] = getAttrInt(outMargin, "bottom", 0);
        }

        // zOrder
        obj.zOrder = getAttrInt(drawEl, "zOrder", 0);

        // shapeComment / description
        Element shapeComment = getChildElement(drawEl, HP, "shapeComment");
        if (shapeComment != null) {
            String desc = getTextContent(shapeComment);
            if (desc != null) obj.description = desc.replace("\n", "\r\n");
        }

        // instid
        long instid = getAttrLong(drawEl, "instid", 0);
        obj.instanceId = instid;

        // hp:drawText - 그리기 개체 내부 텍스트박스
        Element drawText = getChildElement(drawEl, HP, "drawText");
        if (drawText != null) {
            obj.textboxLastWidth = getAttrInt(drawText, "lastWidth", 0);

            // hp:textMargin
            Element textMargin = getChildElement(drawText, HP, "textMargin");
            if (textMargin != null) {
                obj.textboxMarginLeft   = getAttrInt(textMargin, "left", 283);
                obj.textboxMarginRight  = getAttrInt(textMargin, "right", 283);
                obj.textboxMarginTop    = getAttrInt(textMargin, "top", 283);
                obj.textboxMarginBottom = getAttrInt(textMargin, "bottom", 283);
            }

            // hp:subList는 문단을 포함
            Element subList = getChildElement(drawText, HP, "subList");
            if (subList != null) {
                // LIST_HEADER용 subList 속성 파싱
                String textDir  = getAttrStr(subList, "textDirection", "HORIZONTAL");
                String lineWrap = getAttrStr(subList, "lineWrap", "BREAK");
                String vertAlign = getAttrStr(subList, "vertAlign", "TOP");

                int tdVal = "VERTICAL".equals(textDir) ? 1 : 0;
                int lwVal;
                switch (lineWrap) {
                    case "BREAK":    lwVal = 0; break;
                    case "SQUEEZE":  lwVal = 1; break;
                    case "KEEP":     lwVal = 2; break;
                    default:         lwVal = 0; break;
                }
                int vaVal;
                switch (vertAlign) {
                    case "TOP":      vaVal = 0; break;
                    case "CENTER":   vaVal = 1; break;
                    case "BOTTOM":   vaVal = 2; break;
                    default:         vaVal = 0; break;
                }
                // v15.8: vertAlign 비트 위치 수정. 셀 LIST_HEADER 와 동일하게 bit 5~6 사용.
                //   이전 v15.7 까지는 bit 21 로 잘못 emit 되어 한/글이 vertAlign=TOP 으로
                //   해석. byte-level diff 1 byte (LIST_HEADER body byte 4) 발생.
                obj.textboxListProperty = (tdVal & 0x7) | ((lwVal & 0x3) << 3) | ((long)(vaVal & 0x3) << 5);

                List<Element> pElements = getChildElements(subList, HP, "p");
                for (Element pEl : pElements) {
                    Paragraph para = parseParagraph(pEl, doc);
                    obj.textboxParagraphs.add(para);
                }
            }
        }

        return obj;
    }

    private static CtrlPicture parsePicture(Element picEl) {
        CtrlPicture pic = new CtrlPicture();

        // v15.6: hp:pic 의 id 를 instanceId 로 매핑 (CTRL_HEADER instid 필드용)
        pic.instanceId = getAttrLong(picEl, "id", 0L);

        // hp:pic 요소에서 zOrder
        pic.zOrder = getAttrInt(picEl, "zOrder", 0);

        // hp:sz
        Element sz = getChildElement(picEl, HP, "sz");
        if (sz != null) {
            pic.width  = getAttrInt(sz, "width", 0);
            pic.height = getAttrInt(sz, "height", 0);
        }

        // hp:pos -- Fix #4: 그림도 완전한 objProperty 계산
        Element pos = getChildElement(picEl, HP, "pos");
        if (pos != null) {
            pic.vertOffset = getAttrInt(pos, "vertOffset", 0);
            pic.horzOffset = getAttrInt(pos, "horzOffset", 0);

            boolean treatAsChar = getAttrBool(pos, "treatAsChar", true);
            String vertRelTo  = getAttrStr(pos, "vertRelTo", "PARA");
            String horzRelTo  = getAttrStr(pos, "horzRelTo", "COLUMN");
            String vertAlign  = getAttrStr(pos, "vertAlign", "TOP");
            String horzAlign  = getAttrStr(pos, "horzAlign", "LEFT");
            boolean flowText  = getAttrBool(pos, "flowWithText", false);
            boolean overlap   = getAttrBool(pos, "allowOverlap", false);

            pic.objProperty = buildObjProperty(treatAsChar, vertRelTo, vertAlign,
                    horzRelTo, horzAlign, flowText, overlap);
        }

        // 그림용 확장 obj property 필드
        Element picSzEl = getChildElement(picEl, HP, "sz");
        String picTextWrap = getAttrStr(picEl, "textWrap", "TOP_AND_BOTTOM");
        String picWidthRelTo = picSzEl != null ? getAttrStr(picSzEl, "widthRelTo", "ABSOLUTE") : "ABSOLUTE";
        String picHeightRelTo = picSzEl != null ? getAttrStr(picSzEl, "heightRelTo", "ABSOLUTE") : "ABSOLUTE";
        boolean picSizeProtect = picSzEl != null && getAttrBool(picSzEl, "protect", false);
        pic.objProperty = buildExtendedObjProperty(pic.objProperty, picTextWrap,
                parseRelTo(picWidthRelTo), parseHeightRelTo(picHeightRelTo), picSizeProtect, 1);

        // hp:outMargin
        Element outMargin = getChildElement(picEl, HP, "outMargin");
        if (outMargin != null) {
            pic.margins[0] = getAttrInt(outMargin, "left", 0);
            pic.margins[1] = getAttrInt(outMargin, "right", 0);
            pic.margins[2] = getAttrInt(outMargin, "top", 0);
            pic.margins[3] = getAttrInt(outMargin, "bottom", 0);
        }

        // hc:img (그림 데이터 참조) - HC (core) 네임스페이스
        Element imgEl = getChildElement(picEl, HC, "img");
        if (imgEl == null) {
            // HP 네임스페이스로 대체
            imgEl = getChildElement(picEl, HP, "img");
        }
        if (imgEl != null) {
            // binaryItemIDRef는 숫자 또는 "image7" 같은 문자열일 수 있음
            String binRef = getAttrStr(imgEl, "binaryItemIDRef", "0");
            // "image7" 같은 문자열에서 숫자 접미사 추출
            String numPart = binRef.replaceAll("[^0-9]", "");
            pic.binItemId = numPart.isEmpty() ? 0 : Integer.parseInt(numPart);

            // hc:img에서 brightness, contrast, effect, alpha도 읽음
            pic.brightness  = getAttrInt(imgEl, "bright", 0);
            pic.contrast    = getAttrInt(imgEl, "contrast", 0);
            pic.transparency = getAttrInt(imgEl, "alpha", 0);
        }

        // hp:imgRect - 모서리 점은 hc:pt0, hc:pt1, hc:pt2, hc:pt3
        // HWPX는 표준 사각형으로 저장: pt0=(0,0), pt1=(W,0), pt2=(W,H), pt3=(0,H)
        // HWP 바이너리는 X=[0,0,W,0], Y=[W,H,0,H]로 저장 (다른 레이아웃)
        Element imgRect = getChildElement(picEl, HP, "imgRect");
        if (imgRect != null) {
            // HWPX 사각형에서 pt1.x를 width, pt2.y를 height로 읽음
            Element pt1 = getChildElement(imgRect, HC, "pt1");
            Element pt2 = getChildElement(imgRect, HC, "pt2");
            int rectW = 0, rectH = 0;
            if (pt1 != null) rectW = getAttrInt(pt1, "x", 0);
            if (pt2 != null) rectH = getAttrInt(pt2, "y", 0);
            // HWP 바이너리 포맷으로 저장: X=[0,0,W,0], Y=[W,H,0,H]
            pic.imageRectX[0] = 0;     pic.imageRectY[0] = rectW;
            pic.imageRectX[1] = 0;     pic.imageRectY[1] = rectH;
            pic.imageRectX[2] = rectW; pic.imageRectY[2] = 0;
            pic.imageRectX[3] = 0;     pic.imageRectY[3] = rectH;
        }

        // hp:imgClip (자르기)
        Element imgClip = getChildElement(picEl, HP, "imgClip");
        if (imgClip != null) {
            pic.cropLeft   = getAttrInt(imgClip, "left", 0);
            pic.cropTop    = getAttrInt(imgClip, "top", 0);
            pic.cropRight  = getAttrInt(imgClip, "right", 0);
            pic.cropBottom = getAttrInt(imgClip, "bottom", 0);
        }

        // hp:effects
        Element effects = getChildElement(picEl, HP, "effects");
        if (effects != null) {
            pic.brightness  = getAttrInt(effects, "brightness", 0);
            pic.contrast    = getAttrInt(effects, "contrast", 0);
        }

        // hp:shapeComment (gso CTRL_HEADER용 description 문자열)
        Element shapeComment = getChildElement(picEl, HP, "shapeComment");
        if (shapeComment != null) {
            String desc = shapeComment.getTextContent();
            if (desc != null && !desc.isEmpty()) {
                // HWP 바이너리는 description 문자열에서 CRLF 줄바꿈 사용
                desc = desc.replace("\r\n", "\n").replace("\n", "\r\n");
                pic.description = desc;
            }
        }

        // hp:orgSz (스케일링 전 원본 개체 크기 — SHAPE_COMPONENT initWidth/Height로 사용)
        Element orgSz = getChildElement(picEl, HP, "orgSz");
        if (orgSz != null) {
            pic.originalWidth  = getAttrInt(orgSz, "width", pic.width);
            pic.originalHeight = getAttrInt(orgSz, "height", pic.height);
        }

        // hp:imgDim (HWPUNIT 단위의 원시 이미지 픽셀 크기)
        // SHAPE_COMPONENT_PICTURE의 originalWidth/Height에 사용
        Element imgDim = getChildElement(picEl, HP, "imgDim");
        if (imgDim != null) {
            pic.imgDimWidth  = getAttrInt(imgDim, "dimwidth", 0);
            pic.imgDimHeight = getAttrInt(imgDim, "dimheight", 0);
        }
        // orgSz가 없으면 SHAPE_COMPONENT initW/H를 imgDim으로 대체
        if (pic.originalWidth == 0 && pic.imgDimWidth > 0) {
            pic.originalWidth  = pic.imgDimWidth;
            pic.originalHeight = pic.imgDimHeight;
        }

        // hp:renderingInfo → transMatrix, scaMatrix, rotMatrix
        Element renderInfo = getChildElement(picEl, HP, "renderingInfo");
        if (renderInfo != null) {
            // transMatrix/scaMatrix/rotMatrix는 hc: (core) 네임스페이스 소속
            Element trans = getChildElement(renderInfo, HC, "transMatrix");
            if (trans != null) {
                pic.transMatrix = parseMatrix6(trans);
            }
            Element sca = getChildElement(renderInfo, HC, "scaMatrix");
            if (sca != null) {
                pic.scaMatrix = parseMatrix6(sca);
            }
            Element rot = getChildElement(renderInfo, HC, "rotMatrix");
            if (rot != null) {
                pic.rotMatrix = parseMatrix6(rot);
            }
        }

        return pic;
    }

    /** 요소 속성 e1..e6에서 6원소 affine 행렬 파싱 */
    private static double[] parseMatrix6(Element el) {
        double[] m = new double[6];
        m[0] = Double.parseDouble(getAttrStr(el, "e1", "1"));
        m[1] = Double.parseDouble(getAttrStr(el, "e2", "0"));
        m[2] = Double.parseDouble(getAttrStr(el, "e3", "0"));
        m[3] = Double.parseDouble(getAttrStr(el, "e4", "0"));
        m[4] = Double.parseDouble(getAttrStr(el, "e5", "1"));
        m[5] = Double.parseDouble(getAttrStr(el, "e6", "0"));
        return m;
    }

    // ---- 개체 속성 헬퍼 ----

    /**
     * Fix #4: 위치 속성으로부터 완전한 objProperty UINT32를 구성.
     *
     * 비트 배치 (HWP 5.0 spec 표 70 참고):
     *   bit 0:      treatAsChar
     *   bit 1:      예약
     *   bit 2:      줄 간격에 영향
     *   bit 3-4:    vertRelTo (0=paper, 1=page, 2=para)
     *   bit 5-7:    vertAlign (0=top/left, 1=center, 2=bottom/right, 3=inside, 4=outside)
     *   bit 8-9:    horzRelTo (0=page, 1=page, 2=column, 3=para)
     *   bit 10-12:  horzAlign (vertAlign과 동일한 매핑)
     *   bit 13:     vertRelTo=para일 때 세로 위치를 본문 영역으로 제한
     *   bit 14:     다른 개체와 겹침 허용
     *   bit 15-17:  width basis (0=paper, 1=page, 2=column, 3=para, 4=absolute)
     *   bit 18-19:  height basis (0=paper, 1=page, 2=absolute)
     *   bit 20:     size protection (vertRelTo=para일 때)
     *   bit 21-23:  textWrap (0=square, 1=tight, 2=through, 3=topAndBottom, 4=behindText, 5=inFrontOfText)
     *   bit 24-25:  텍스트 흐름 방향 (0=bothSides, 1=leftOnly, 2=rightOnly, 3=largestOnly)
     *   bit 26-28:  numbering 카테고리 (0=none, 1=figure, 2=table, 3=equation)
     */
    private static long buildObjProperty(boolean treatAsChar, String vertRelTo, String vertAlign,
            String horzRelTo, String horzAlign, boolean flowText, boolean overlap) {
        long prop = 0;
        prop |= (treatAsChar ? 1L : 0L);

        int vrVal;
        switch (vertRelTo) {
            case "PAPER":  vrVal = 0; break;
            case "PAGE":   vrVal = 1; break;
            case "PARA":   vrVal = 2; break;
            default:       vrVal = 2; break;
        }
        prop |= ((long)(vrVal & 0x3) << 3);

        int vaVal;
        switch (vertAlign) {
            case "TOP":     vaVal = 0; break;
            case "CENTER":  vaVal = 1; break;
            case "BOTTOM":  vaVal = 2; break;
            case "INSIDE":  vaVal = 3; break;
            case "OUTSIDE": vaVal = 4; break;
            default:        vaVal = 0; break;
        }
        prop |= ((long)(vaVal & 0x7) << 5);

        int hrVal;
        switch (horzRelTo) {
            case "PAPER":  hrVal = 0; break;
            case "PAGE":   hrVal = 1; break;
            case "COLUMN": hrVal = 2; break;
            case "PARA":   hrVal = 3; break;
            default:       hrVal = 2; break;
        }
        prop |= ((long)(hrVal & 0x3) << 8);

        int haVal;
        switch (horzAlign) {
            case "LEFT":    haVal = 0; break;
            case "CENTER":  haVal = 1; break;
            case "RIGHT":   haVal = 2; break;
            case "INSIDE":  haVal = 3; break;
            case "OUTSIDE": haVal = 4; break;
            default:        haVal = 0; break;
        }
        prop |= ((long)(haVal & 0x7) << 10);

        // bit 13: vertRelTo=para일 때 본문 영역으로 제한
        if (vrVal == 2) prop |= (1L << 13); // para 기준일 때 기본 활성화

        // bit 14: 겹침 허용
        if (overlap) prop |= (1L << 14);

        return prop;
    }

    /**
     * Fix #4: hp:pos 범위를 넘어서는 확장 objProperty 필드 구성.
     * 설정 항목: widthBasis, heightBasis, sizeProtection, textWrap, textFlowSide, numberCategory.
     */
    private static long buildExtendedObjProperty(long prop, String textWrap,
            int widthBasis, int heightBasis, boolean sizeProtect, int numberCategory) {
        // bit 15-17: width basis
        prop |= ((long)(widthBasis & 0x7) << 15);
        // bit 18-19: height basis
        prop |= ((long)(heightBasis & 0x3) << 18);
        // bit 20: size protection
        if (sizeProtect) prop |= (1L << 20);
        // bit 21-23: textWrap
        int twVal = parseTextWrapType(textWrap);
        prop |= ((long)(twVal & 0x7) << 21);
        // bit 26-28: numbering category
        prop |= ((long)(numberCategory & 0x7) << 26);
        return prop;
    }

    /** widthRelTo 파싱: PAPER=0, PAGE=1, COLUMN=2, PARA=3, ABSOLUTE=4 */
    private static int parseRelTo(String relTo) {
        if (relTo == null) return 4;
        switch (relTo) {
            case "PAPER": return 0;
            case "PAGE": return 1;
            case "COLUMN": return 2;
            case "PARA": return 3;
            case "ABSOLUTE": return 4;
            default: return 4;
        }
    }

    /** heightRelTo 파싱: PAPER=0, PAGE=1, ABSOLUTE=2 */
    private static int parseHeightRelTo(String relTo) {
        if (relTo == null) return 2;
        switch (relTo) {
            case "PAPER": return 0;
            case "PAGE": return 1;
            case "ABSOLUTE": return 2;
            default: return 2;
        }
    }

    /**
     * Fix #5: 각주/미주 구분선 타입은 테두리 선 타입과 비교해
     * shifted 매핑 사용. 0=none, 1=solid, 2=dash 등.
     */
    private static int parseFootnoteDividerLineType(String type) {
        if (type == null || type.isEmpty()) return 0;
        switch (type) {
            case "NONE":          return 0;
            case "SOLID":         return 1;
            case "DASH":          return 2;
            case "DOT":           return 3;
            case "DASH_DOT":      return 4;
            case "DASH_DOT_DOT":  return 5;
            case "LONG_DASH":     return 6;
            case "CIRCLE":        return 7;
            case "DOUBLE_SLIM":   return 8;
            case "SLIM_THICK":    return 9;
            case "THICK_SLIM":    return 10;
            case "SLIM_THICK_SLIM": return 11;
            default:              return 0;
        }
    }

    /**
     * Fix #4: objProperty 비트 21-23용 숫자 값으로 textWrap 속성 파싱.
     */
    private static int parseTextWrapType(String wrap) {
        if (wrap == null) return 0;
        // 참조 00.hwp 바이너리에 대해 매핑 확인됨 (objProperty의 bit 21-23)
        switch (wrap) {
            case "SQUARE":           return 0;
            case "TOP_AND_BOTTOM":   return 1; // 참조값은 3이 아니라 1
            case "BEHIND_TEXT":      return 2;
            case "IN_FRONT_OF_TEXT": return 3;
            case "TIGHT":            return 4;
            case "THROUGH":          return 5;
            default:                 return 0;
        }
    }
}
