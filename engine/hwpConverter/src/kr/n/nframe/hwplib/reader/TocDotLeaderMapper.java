package kr.n.nframe.hwplib.reader;

import java.util.HashMap;
import java.util.Map;

import kr.n.nframe.hwplib.model.Control;
import kr.n.nframe.hwplib.model.CtrlSectionDef;
import kr.n.nframe.hwplib.model.HwpDocument;
import kr.n.nframe.hwplib.model.PageDef;
import kr.n.nframe.hwplib.model.Paragraph;
import kr.n.nframe.hwplib.model.ParaShape;
import kr.n.nframe.hwplib.model.Section;
import kr.n.nframe.hwplib.model.TabDef;

/**
 * HWPX → HWP 전용 후처리기 (목차 점선 leader 매핑).
 *
 * <p>배경: 목차 문단의 인라인 hp:tab 컨트롤에는 (leader=DOT, type=RIGHT) 신호가
 * 실려 있으나, 한/글은 점선 leader 를 문단 paraShape 가 참조하는 header TabDef 의
 * fillType 을 기준으로 렌더한다. 입력 HWPX header TabDef 에 DOT fill 정지가 없으면
 * 인라인 leader 만으로는 점선이 그려지지 않는다.
 *
 * <p>본 후처리기는 인라인 (leader=DOT, type=RIGHT) 탭을 가진 문단을 감지하여,
 * 그 문단이 참조하던 TabDef 를 복제해 우측 정렬(DOT-fill) 탭 정지를 추가하고,
 * paraShape 를 복제해 새 TabDef 를 가리키도록 재연결한다. 원본 TabDef/paraShape 는
 * 그대로 두므로 비-DOT 탭·비목차 문단은 무변경이다.
 *
 * <p>ODT → HWP/HWPX 경로 및 hwp2hwpx 역변환은 이 클래스를 호출하지 않으므로 무관.
 */
public final class TocDotLeaderMapper {

    private TocDotLeaderMapper() {
    }

    /** 인라인 탭 컨트롤 leader 바이트값: DOT. */
    private static final int INLINE_LEADER_DOT = 3;
    /** 인라인 탭 컨트롤 type 바이트값: RIGHT. */
    private static final int INLINE_TYPE_RIGHT = 2;
    /** 확장 탭 컨트롤 문자 코드 (0x0009). */
    private static final int CHAR_TAB = 0x0009;

    /** HWP TabDef 정지 type: RIGHT (0=left,1=right,2=center,3=decimal). */
    private static final int HWP_TAB_RIGHT = 1;
    /** HWP TabDef 정지 fillType: DOT (NONE=0,SOLID=1,DASH=2,DOT=3,...). */
    private static final int HWP_FILL_DOT = 3;

    /** 표준 여백 A4 세로 기준 contentWidth 안전 기본값. */
    private static final int DEFAULT_CONTENT_WIDTH = 48190;

    /**
     * 문서 전체를 순회하며 목차 점선 탭 문단에 DOT-fill TabDef 를 연결한다.
     *
     * @param doc HWPX 로부터 읽어들인 HWP 문서 모델 (HWPX→HWP 경로 전용)
     */
    public static void apply(HwpDocument doc) {
        if (doc == null || doc.sections == null || doc.sections.isEmpty()) {
            return;
        }

        int contentWidth = computeContentWidth(doc);

        // 원본 paraShapeId -> DOT-fill 로 재연결된 새 paraShapeId
        Map<Integer, Integer> remap = new HashMap<>();
        int patched = 0;

        for (Section sec : doc.sections) {
            for (Paragraph para : sec.paragraphs) {
                if (para.paraText == null || para.paraText.rawBytes == null) {
                    continue;
                }
                if (!hasDotRightTab(para.paraText.rawBytes)) {
                    continue;
                }

                int origPsId = para.paraShapeId;
                Integer mapped = remap.get(origPsId);
                if (mapped == null) {
                    mapped = buildDotParaShape(doc, origPsId, contentWidth);
                    remap.put(origPsId, mapped);
                }
                para.paraShapeId = mapped;
                patched++;
            }
        }

        if (patched > 0) {
            // append 후 ID_MAPPINGS 카운트 재계산 (counts[10]=tabDefs, counts[13]=paraShapes)
            doc.idMappings.counts[10] = doc.tabDefs.size();
            doc.idMappings.counts[13] = doc.paraShapes.size();
            System.out.println("[TocDotLeaderMapper] Patched TOC dot-leader paragraphs: " + patched
                    + " (new tabDefs/paraShapes=" + remap.size() + ")");
        }
    }

    /**
     * paraText rawBytes 안에서 16-byte 확장 탭 컨트롤 중
     * (byte6 == DOT leader) && (byte7 == RIGHT type) 를 가진 것이 있는지 검사.
     *
     * <p>확장 탭 컨트롤은 code(0x0009) 로 시작하고 14 byte 뒤에 code 복사본이 다시
     * 등장한다. 두 code 워드가 모두 0x0009 이고 leader/type 이 일치할 때만 채택하여
     * 오탐을 배제한다.
     */
    private static boolean hasDotRightTab(byte[] b) {
        int i = 0;
        while (i + 16 <= b.length) {
            int codeHead = (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
            if (codeHead == CHAR_TAB) {
                int codeTail = (b[i + 14] & 0xFF) | ((b[i + 15] & 0xFF) << 8);
                if (codeTail == CHAR_TAB) {
                    int leader = b[i + 6] & 0xFF;
                    int type = b[i + 7] & 0xFF;
                    if (leader == INLINE_LEADER_DOT && type == INLINE_TYPE_RIGHT) {
                        return true;
                    }
                    i += 16; // 유효한 확장 컨트롤: 8 WCHAR 건너뜀
                    continue;
                }
            }
            i += 2; // WCHAR 단위 이동
        }
        return false;
    }

    /**
     * 원본 paraShape 를 복제하고, 그 참조 TabDef 를 복제해 DOT-fill 우측정렬 정지를
     * 추가한 뒤, 복제 paraShape 의 tabDefId 를 새 TabDef 로 연결한다.
     *
     * @return 새로 추가된 paraShape 의 id (= 리스트 인덱스)
     */
    private static int buildDotParaShape(HwpDocument doc, int origPsId, int contentWidth) {
        ParaShape src = (origPsId >= 0 && origPsId < doc.paraShapes.size())
                ? doc.paraShapes.get(origPsId) : new ParaShape();

        // 1) 참조 TabDef 복제 + DOT 정지 추가
        int srcTabDefId = src.tabDefId;
        TabDef srcTd = (srcTabDefId >= 0 && srcTabDefId < doc.tabDefs.size())
                ? doc.tabDefs.get(srcTabDefId) : null;
        TabDef newTd = cloneTabDef(srcTd);

        boolean exists = false;
        for (TabDef.TabItem it : newTd.items) {
            if (it.fillType == HWP_FILL_DOT && it.type == HWP_TAB_RIGHT && it.position == contentWidth) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            TabDef.TabItem stop = new TabDef.TabItem();
            stop.position = contentWidth;
            stop.type = HWP_TAB_RIGHT;
            stop.fillType = HWP_FILL_DOT;
            stop.padding = 0;
            newTd.items.add(stop);
        }
        int newTabDefId = doc.tabDefs.size();
        doc.tabDefs.add(newTd);

        // 2) paraShape 복제 + tabDefId 재지정
        ParaShape newPs = cloneParaShape(src);
        newPs.tabDefId = newTabDefId;
        int newPsId = doc.paraShapes.size();
        doc.paraShapes.add(newPs);
        return newPsId;
    }

    private static TabDef cloneTabDef(TabDef src) {
        TabDef td = new TabDef();
        if (src != null) {
            td.property = src.property;
            for (TabDef.TabItem it : src.items) {
                TabDef.TabItem c = new TabDef.TabItem();
                c.position = it.position;
                c.type = it.type;
                c.fillType = it.fillType;
                c.padding = it.padding;
                td.items.add(c);
            }
        }
        return td;
    }

    private static ParaShape cloneParaShape(ParaShape s) {
        ParaShape p = new ParaShape();
        p.property1 = s.property1;
        p.leftMargin = s.leftMargin;
        p.rightMargin = s.rightMargin;
        p.indent = s.indent;
        p.spaceBefore = s.spaceBefore;
        p.spaceAfter = s.spaceAfter;
        p.lineSpacing = s.lineSpacing;
        p.tabDefId = s.tabDefId;
        p.numberingId = s.numberingId;
        p.borderFillId = s.borderFillId;
        p.borderPadLeft = s.borderPadLeft;
        p.borderPadRight = s.borderPadRight;
        p.borderPadTop = s.borderPadTop;
        p.borderPadBottom = s.borderPadBottom;
        p.property2 = s.property2;
        p.property3 = s.property3;
        p.lineSpacing2 = s.lineSpacing2;
        return p;
    }

    /** 첫 CtrlSectionDef 의 PageDef 로부터 contentWidth 를 계산. */
    private static int computeContentWidth(HwpDocument doc) {
        for (Section sec : doc.sections) {
            for (Paragraph para : sec.paragraphs) {
                if (para.controls == null) {
                    continue;
                }
                for (Control c : para.controls) {
                    if (c instanceof CtrlSectionDef) {
                        PageDef pd = ((CtrlSectionDef) c).pageDef;
                        int w = pd.paperWidth - pd.leftMargin - pd.rightMargin - pd.gutterMargin;
                        if (w > 0) {
                            return w;
                        }
                    }
                }
            }
        }
        return DEFAULT_CONTENT_WIDTH;
    }
}
