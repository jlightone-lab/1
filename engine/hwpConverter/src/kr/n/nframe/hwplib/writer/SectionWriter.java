package kr.n.nframe.hwplib.writer;

import kr.n.nframe.hwplib.binary.HwpBinaryWriter;
import kr.n.nframe.hwplib.binary.RecordWriter;
import kr.n.nframe.hwplib.constants.CtrlId;
import kr.n.nframe.hwplib.constants.HwpTagId;
import kr.n.nframe.hwplib.model.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * HWP 5.0 파일을 위한 BodyText/SectionN 스트림 레코드를 생성한다.
 * 원시(비압축) byte를 반환한다. 압축은 HwpWriter가 처리한다.
 *
 * 모든 레코드 포맷은 한글 워드프로세서가 생성한 참조 HWP 파일의 byte 분석과 정확히 일치한다.
 * 한글 워드프로세서가 생성한 결과.
 */
public class SectionWriter {

    private SectionWriter() {}

    /**
     * 단일 섹션의 모든 레코드를 생성한다.
     *
     * @param section 섹션 모델
     * @param doc     부모 문서 (필요시 컨텍스트 용도)
     * @return 원시 레코드 byte (비압축)
     */
    public static byte[] build(Section section, HwpDocument doc) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            for (int i = 0; i < section.paragraphs.size(); i++) {
                boolean lastInList = (i == section.paragraphs.size() - 1);
                writeParagraph(out, section.paragraphs.get(i), 0, false, lastInList);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build Section", e);
        }
    }

    // -----------------------------------------------------------------------
    // 문단 쓰기
    // -----------------------------------------------------------------------

    /**
     * 모든 하위 레코드를 포함한 완전한 문단을 작성한다.
     *
     * 레코드 순서:
     *   PARA_HEADER (level에 위치)
     *   PARA_TEXT (level+1에 위치) -- nChars > 1 또는 rawBytes > 2인 경우에만
     *   PARA_CHAR_SHAPE (level+1에 위치)
     *   PARA_LINE_SEG (level+1에 위치)
     *   컨트롤 (level+1에 위치)
     */
    private static void writeParagraph(ByteArrayOutputStream out, Paragraph para, int level,
                                       boolean inCell, boolean lastInList) throws IOException {
        // PARA_CHAR_SHAPE / PARA_LINE_SEG 정합성 보정 — PARA_HEADER 작성 전에 수행해야
        // PARA_HEADER 의 charShapeCount / lineSegCount 가 실제 후속 record 와 일치한다.
        // hwp2hwpx ForPara.runs(...) NPE 와 한/글 "파일 손상" 거부의 직접 원인이었다.
        if (para.charShapeRefs.isEmpty()) {
            CharShapeRef defaultRef = new CharShapeRef();
            defaultRef.position = 0;
            defaultRef.charShapeId = 0;
            para.charShapeRefs.add(defaultRef);
        }
        if (para.lineSegs.isEmpty()) {
            LineSeg defaultSeg = new LineSeg();
            defaultSeg.textStartPos = 0;
            defaultSeg.lineVertPos = 0;
            defaultSeg.lineHeight = 1000;
            defaultSeg.textHeight = 1000;
            defaultSeg.baselineDistance = 600;
            defaultSeg.lineSpacing = 0;
            defaultSeg.columnStartPos = 0;
            defaultSeg.segWidth = 0;
            defaultSeg.tag = 0;
            para.lineSegs.add(defaultSeg);
        }
        // 안전 가드: 퇴화 seg(segWidth/lineHeight<=0)는 한컴 layout 재계산 시 위험.
        for (LineSeg seg : para.lineSegs) {
            if (seg.segWidth <= 0) seg.segWidth = 42000;
            if (seg.lineHeight <= 0) seg.lineHeight = 1000;
        }

        // PARA_HEADER
        out.write(buildParaHeader(para, level, inCell, lastInList));

        // PARA_TEXT: 문단 끝 마커 이외의 실제 내용이 있는 경우에만 작성.
        // nChars=1이고 내용이 0x000D뿐이면 PARA_TEXT 자체를 생략한다.
        boolean hasText = para.paraText != null
                && para.paraText.rawBytes != null
                && para.paraText.rawBytes.length > 2; // 0x000D (2 byte) 이상일 경우

        if (hasText) {
            out.write(RecordWriter.buildRecord(HwpTagId.PARA_TEXT, level + 1, para.paraText.rawBytes));
        }

        // PARA_CHAR_SHAPE — 보정은 메서드 상단에서 이미 수행됨 (PARA_HEADER 정합성 위해).
        out.write(buildParaCharShape(para.charShapeRefs, level + 1));

        // PARA_LINE_SEG — 보정은 메서드 상단에서 이미 수행됨.
        out.write(buildParaLineSeg(para.lineSegs, level + 1));

        // PARA_RANGE_TAG: PARA_LINE_SEG 뒤에 기록 (한컴 참조 순서와 동일:
        //   HEADER, TEXT, CHAR_SHAPE, LINE_SEG, RANGE_TAG, 컨트롤...).
        // 하이퍼링크 텍스트가 한글에서 클릭 가능 / 스타일 적용으로 인식되기 위해 필요함.
        if (!para.rangeTags.isEmpty()) {
            out.write(buildParaRangeTag(para.rangeTags, level + 1));
        }

        // 컨트롤
        for (Control ctrl : para.controls) {
            writeControl(out, ctrl, level + 1);
        }
    }

    /**
     * PARA_HEADER: 22 byte.
     *
     * UINT32 nChars(4) + UINT32 controlMask(4) + UINT16 paraShapeId(2) +
     * UINT8 styleId(1) + UINT8 columnBreakType(1) + UINT16 charShapeCount(2) +
     * UINT16 rangeTagCount(2) + UINT16 lineSegCount(2) + UINT32 instanceId(4)
     *
     * nChars는 문단 텍스트의 총 WCHAR 개수:
     *   - 확장 컨트롤은 각각 8 WCHAR로 계산
     *   - 문자형 컨트롤(tab, line break, para end)은 각각 1 WCHAR로 계산
     *   - 빈 문단의 경우(PARA_TEXT 레코드 없음): nChars=1
     *
     * controlMask = 각 확장 컨트롤에 대한 (1 << charCode)의 OR 연산.
     *   - 문단 끝(0x0D)은 포함되지 않음
     *   - Tab(0x09)은 존재할 경우 포함됨
     *   - pghd (code 21) = bit 21 = 0x200000
     *   - gso (code 11) = bit 11 = 0x800
     *
     * instanceId: 대부분의 문단에 대해 0x80000000 (bit 31 설정).
     */
    private static byte[] buildParaHeader(Paragraph para, int level, boolean inCell, boolean lastInList) {
        HwpBinaryWriter w = new HwpBinaryWriter(28);

        boolean hasText = para.paraText != null
                && para.paraText.rawBytes != null
                && para.paraText.rawBytes.length > 2;

        long nChars = para.nChars;
        long controlMask = para.controlMask;
        long instanceId = para.instanceId & 0xFFFFFFFFL;

        if (!hasText) {
            // 빈 문단: nChars=1 (암시적 문단 개행), controlMask=0
            // v15.6: instanceId override 제거. parseParagraph 가 HWPML id 속성
            // 으로부터 정확한 값을 이미 채워뒀으므로 그대로 신뢰한다.
            //   이전 (~v15.5) 코드는 빈 문단 instanceId 를 0x80000000 으로 강제해
            //   한/글 원본 (대부분 0) 과 30+ byte 위치에서 mismatch 발생.
            nChars = 1;
            controlMask = 0;
        }

        // nChars bit 31 = "lastInList" 플래그.
        // hwplib는 셀/리스트에서 문단 읽기를 멈출 시점을 결정하기 위해 이 값을 사용한다.
        // 각 문단 리스트(섹션, 셀 등)의 마지막 문단에 반드시 설정되어야 한다.
        if (lastInList) {
            nChars |= 0x80000000L;
        }

        w.writeUInt32(nChars);                          // 4 byte
        w.writeUInt32(controlMask);                     // 4 byte
        w.writeUInt16(para.paraShapeId);                // 2 byte
        w.writeUInt8(para.styleId);                     // 1 byte
        w.writeUInt8(para.columnBreakType);             // 1 byte
        w.writeUInt16(para.charShapeRefs.size());       // 2 byte
        w.writeUInt16(para.rangeTags.size());           // 2 byte: rangeTagCount
        w.writeUInt16(para.lineSegs.size());            // 2 byte: lineSegCount
        w.writeUInt32(instanceId);                      // 4 byte
        w.writeUInt16(0);                               // 2 byte: mergeFlag
        return RecordWriter.buildRecord(HwpTagId.PARA_HEADER, level, w.toByteArray());
    }

    /**
     * PARA_CHAR_SHAPE: (UINT32 position + UINT32 charShapeId) 쌍의 배열.
     * 각 항목은 8 byte.
     */
    private static byte[] buildParaCharShape(List<CharShapeRef> refs, int level) {
        HwpBinaryWriter w = new HwpBinaryWriter(Math.multiplyExact(refs.size(), 8));
        for (CharShapeRef ref : refs) {
            w.writeUInt32(ref.position);
            w.writeUInt32(ref.charShapeId);
        }
        return RecordWriter.buildRecord(HwpTagId.PARA_CHAR_SHAPE, level, w.toByteArray());
    }

    /**
     * PARA_RANGE_TAG: 12 byte 항목의 배열.
     * 각 항목: UINT32 start + UINT32 end + UINT32 tag
     *   tag = (sort << 24) | (data & 0xFFFFFF)
     */
    private static byte[] buildParaRangeTag(List<ParaRangeTag> tags, int level) {
        HwpBinaryWriter w = new HwpBinaryWriter(Math.multiplyExact(tags.size(), 12));
        for (ParaRangeTag t : tags) {
            w.writeUInt32(t.start);
            w.writeUInt32(t.end);
            long tag = ((long)(t.sort & 0xFF) << 24) | (t.data & 0xFFFFFFL);
            w.writeUInt32(tag);
        }
        return RecordWriter.buildRecord(HwpTagId.PARA_RANGE_TAG, level, w.toByteArray());
    }

    /**
     * PARA_LINE_SEG: 36 byte 항목의 배열.
     * 각 항목: textStartPos(4) + lineVertPos(4) + lineHeight(4) + textHeight(4) +
     *       baselineDistance(4) + lineSpacing(4) + columnStartPos(4) + segWidth(4) + tag(4)
     */
    private static byte[] buildParaLineSeg(List<LineSeg> segs, int level) {
        HwpBinaryWriter w = new HwpBinaryWriter(Math.multiplyExact(segs.size(), 36));
        for (LineSeg seg : segs) {
            w.writeUInt32(seg.textStartPos);
            w.writeInt32(seg.lineVertPos);
            w.writeInt32(seg.lineHeight);
            w.writeInt32(seg.textHeight);
            w.writeInt32(seg.baselineDistance);
            w.writeInt32(seg.lineSpacing);
            w.writeInt32(seg.columnStartPos);
            w.writeInt32(seg.segWidth);
            w.writeUInt32(seg.tag);
        }
        return RecordWriter.buildRecord(HwpTagId.PARA_LINE_SEG, level, w.toByteArray());
    }

    // -----------------------------------------------------------------------
    // 컨트롤 쓰기
    // -----------------------------------------------------------------------

    private static void writeControl(ByteArrayOutputStream out, Control ctrl, int level)
            throws IOException {
        if (ctrl instanceof CtrlSectionDef) {
            writeSectionDef(out, (CtrlSectionDef) ctrl, level);
        } else if (ctrl instanceof CtrlColumnDef) {
            writeColumnDef(out, (CtrlColumnDef) ctrl, level);
        } else if (ctrl instanceof CtrlTable) {
            writeTable(out, (CtrlTable) ctrl, level);
        } else if (ctrl instanceof CtrlPicture) {
            writePicture(out, (CtrlPicture) ctrl, level);
        } else if (ctrl instanceof CtrlHeaderFooter) {
            writeHeaderFooter(out, (CtrlHeaderFooter) ctrl, level);
        } else if (ctrl instanceof CtrlField) {
            writeField(out, (CtrlField) ctrl, level);
        } else {
            writeGenericControl(out, ctrl, level);
        }
    }

    /**
     * 일반 컨트롤: ctrlId(4) + 원시 속성 데이터 byte.
     * pgnp (쪽 번호 위치), atno (자동 번호), nwno (새 번호),
     * pghd (쪽 숨기기), pgct (홀/짝), idxm (색인), bokm (책갈피) 등에 사용.
     */
    private static void writeGenericControl(ByteArrayOutputStream out, Control ctrl, int level)
            throws IOException {
        HwpBinaryWriter w = new HwpBinaryWriter(20);
        w.writeUInt32(ctrl.ctrlId);
        if (ctrl.rawData != null) {
            w.writeBytes(ctrl.rawData);
        } else {
            // 일부 컨트롤은 hwplib 리더가 기대하는 스펙으로 고정된 후속 데이터
            // 블록을 가진다. rawData가 없을 때(예: HWPX 소스에 hp:autoNumFormat
            // 세부 정보가 없는 경우)에도 레코드 길이가 스펙과 일치하도록
            // 0으로 채운 데이터를 반드시 기록해야 하며, 그렇지 않으면 한글은
            // 해당 레코드를 잘린 것으로 간주하여 이후 문단 레이아웃이 무너진다.
            int ctrlId = ctrl.ctrlId;
            if (ctrlId == CtrlId.NEW_NUMBER) {
                // HWP 5.0 §4.3.10.6 새 번호 지정: UINT32 property(4) + UINT16 number(2) = 6 byte.
                w.writePad(6);
            } else if (ctrlId == CtrlId.AUTO_NUMBER) {
                // HWP 5.0 §4.3.10.5 자동 번호 / §4.3.10.6 새 번호 지정:
                //   UINT32 property(4) + UINT16 number(2)
                //   + WCHAR userChar(2) + WCHAR prefix(2) + WCHAR suffix(2)
                //   = 12 byte 데이터 → 총 16 byte 레코드.
                w.writePad(12);
            } else if (ctrlId == CtrlId.PAGE_HIDE || ctrlId == CtrlId.PAGE_ODD_EVEN) {
                // §4.3.10.7~8 — UINT32 속성 = 4 byte 데이터.
                w.writePad(4);
            } else if (ctrlId == CtrlId.PAGE_NUM_POS) {
                // §4.3.10.9 — UINT32 속성 + UINT16 number_position = 6 byte.
                w.writePad(6);
            } else if (ctrlId == CtrlId.INDEX_MARK) {
                // §4.3.10.10 — 최소 6 byte (빈 키워드).
                w.writePad(6);
            }
            // BOOKMARK (bokm)는 HWPTAG_CTRL_DATA 형제 레코드를 통해 데이터를
            // 전달하므로 여기에는 후속 byte가 없다.
        }
        out.write(RecordWriter.buildRecord(HwpTagId.CTRL_HEADER, level, w.toByteArray()));
    }

    // --- CtrlSectionDef ---

    /**
     * 섹션 정의용 CTRL_HEADER: 총 38 byte.
     *
     * ctrlId(4) + property(4) + columnGap(2) + vertGrid(2) + horizGrid(2) +
     * defaultTabStop(4) + numberingParaShapeId(2) + pageNumber(2) + figNumber(2) +
     * tableNumber(2) + equationNumber(2) + defaultLang(2) = 30 byte
     * + 버전 5.0.1.2+ 호환성을 위한 8 byte 0 패딩 = 38 byte
     *
     * <p>v16.00 수정 (newfeature deep diff): 이전 47 byte (17 byte 패딩) 출력은
     *   사양 위반으로 한/글이 "파일이 손상되었습니다" 팝업을 띄움. 참조본 v47.hwp 와
     *   hwplib-1.1.10 의 ForControlSectionDefine.ctrlHeader 가 모두 8 byte 패딩 = 38 byte.
     *   기존 17 byte 패딩 출력에 의존하는 다운스트림 reader 는 없음 (한/글, hwplib reader
     *   모두 38 byte 본문을 표준으로 인식).
     *
     * 참조본 byte 18 (ctrlId 시작부터의 offset) = outlineShapeIDRef (UINT16), 값=1.
     * 이는 numberingParaShapeId 위치에 해당한다.
     */
    private static void writeSectionDef(ByteArrayOutputStream out, CtrlSectionDef sd, int level)
            throws IOException {
        HwpBinaryWriter w = new HwpBinaryWriter(40);
        w.writeUInt32(sd.ctrlId);              // 4 byte
        w.writeUInt32(sd.property);            // 4 byte
        w.writeHwpUnit16(sd.columnGap);        // 2 byte
        w.writeHwpUnit16(sd.vertGrid);         // 2 byte
        w.writeHwpUnit16(sd.horizGrid);        // 2 byte
        w.writeHwpUnit(sd.defaultTabStop);     // 4 byte
        w.writeUInt16(sd.numberingParaShapeId); // 2 byte
        w.writeUInt16(sd.pageNumber);          // 2 byte
        w.writeUInt16(sd.figNumber);           // 2 byte
        w.writeUInt16(sd.tableNumber);         // 2 byte
        w.writeUInt16(sd.equationNumber);      // 2 byte
        w.writeUInt16(sd.defaultLang);         // 2 byte
        // --- 여기까지 30 byte ---
        w.writePad(8);                         // 8 byte 0 패딩 (사양상 정확)
        // --- 총 38 byte ---
        out.write(RecordWriter.buildRecord(HwpTagId.CTRL_HEADER, level, w.toByteArray()));

        // CTRL_DATA는 여기에 작성하지 않음 - 일부 참조 파일에만 존재하며
        // 대부분의 파일에서는 없어도 문제가 되지 않는다.

        // PAGE_DEF
        out.write(buildPageDef(sd.pageDef, level + 1));

        // FOOTNOTE_SHAPE (각주)
        out.write(buildFootnoteShape(sd.footnoteShape, level + 1));

        // FOOTNOTE_SHAPE (미주)
        out.write(buildFootnoteShape(sd.endnoteShape, level + 1));

        // PAGE_BORDER_FILL 레코드
        for (PageBorderFill pbf : sd.pageBorderFills) {
            out.write(buildPageBorderFill(pbf, level + 1));
        }
    }

    /**
     * PAGE_DEF: 40 byte.
     * HWPUNIT paperWidth(4) + paperHeight(4) + leftMargin(4) + rightMargin(4) +
     * topMargin(4) + bottomMargin(4) + headerMargin(4) + footerMargin(4) +
     * gutterMargin(4) + UINT32 property(4)
     */
    private static byte[] buildPageDef(PageDef pd, int level) {
        HwpBinaryWriter w = new HwpBinaryWriter(40);
        w.writeHwpUnit(pd.paperWidth);
        w.writeHwpUnit(pd.paperHeight);
        w.writeHwpUnit(pd.leftMargin);
        w.writeHwpUnit(pd.rightMargin);
        w.writeHwpUnit(pd.topMargin);
        w.writeHwpUnit(pd.bottomMargin);
        w.writeHwpUnit(pd.headerMargin);
        w.writeHwpUnit(pd.footerMargin);
        w.writeHwpUnit(pd.gutterMargin);
        w.writeUInt32(pd.property);
        return RecordWriter.buildRecord(HwpTagId.PAGE_DEF, level, w.toByteArray());
    }

    /**
     * FOOTNOTE_SHAPE: 정확히 28 byte.
     *
     * UINT32 property(4) + WCHAR userSymbol(2) + WCHAR prefixChar(2) +
     * WCHAR suffixChar(2) + UINT16 startNumber(2) + INT32 dividerLength(4) +
     * HWPUNIT16 aboveMargin(2) + HWPUNIT16 belowMargin(2) +
     * HWPUNIT16 betweenNote(2) + UINT8 dividerLineType(1) +
     * UINT8 dividerLineWidth(1) + COLORREF dividerColor(4) = 28 byte
     *
     * dividerLength는 INT32 (4 byte)이며 INT16이 아님! 자동의 경우 -1 = 0xFFFFFFFF.
     * dividerLineType: 0=없음, 1=실선 (테두리 선 타입 enum과 다름).
     * dividerLineWidth: 0=0.1mm, 1=0.12mm 등.
     */
    private static byte[] buildFootnoteShape(FootnoteShape fs, int level) {
        HwpBinaryWriter w = new HwpBinaryWriter(32);
        w.writeUInt32(fs.property);              // 4 byte
        w.writeWChar(fs.userSymbol);             // 2 byte
        w.writeWChar(fs.prefixChar);             // 2 byte
        w.writeWChar(fs.suffixChar);             // 2 byte
        w.writeUInt16(fs.startNumber);           // 2 byte
        w.writeInt32(fs.dividerLength);          // 4 byte (INT32!)
        w.writeHwpUnit16(fs.dividerAboveMargin); // 2 byte
        w.writeHwpUnit16(fs.dividerBelowMargin); // 2 byte
        w.writeHwpUnit16(fs.noteBetweenMargin);  // 2 byte
        w.writeUInt8(fs.dividerLineType);        // 1 byte
        w.writeUInt8(fs.dividerLineWidth);       // 1 byte
        w.writeColorRef(fs.dividerLineColor);    // 4 byte
        // --- 총 28 byte ---
        return RecordWriter.buildRecord(HwpTagId.FOOTNOTE_SHAPE, level, w.toByteArray());
    }

    /**
     * PAGE_BORDER_FILL: 14 byte.
     * UINT32 property(4) + HWPUNIT16 padL(2) + padR(2) + padT(2) + padB(2) +
     * UINT16 borderFillId(2)
     *
     * Property 비트:
     *   bit 0:    위치 기준 (0=본문, 1=용지)
     *   bit 1:    머리말 포함
     *   bit 2:    꼬리말 포함
     *   bits 3-4: 채움 영역 (0=용지, 1=페이지, 2=테두리)
     */
    private static byte[] buildPageBorderFill(PageBorderFill pbf, int level) {
        HwpBinaryWriter w = new HwpBinaryWriter(16);
        w.writeUInt32(pbf.property);
        w.writeHwpUnit16(pbf.padLeft);
        w.writeHwpUnit16(pbf.padRight);
        w.writeHwpUnit16(pbf.padTop);
        w.writeHwpUnit16(pbf.padBottom);
        w.writeUInt16(pbf.borderFillId);
        return RecordWriter.buildRecord(HwpTagId.PAGE_BORDER_FILL, level, w.toByteArray());
    }

    // --- CtrlColumnDef ---

    /**
     * 단 정의 컨트롤.
     * ctrlId(4) + propertyLow(2) + columnGap(2) + [columnWidths] +
     * propertyHigh(2) + dividerType(1) + dividerWidth(1) + dividerColor(4)
     */
    private static void writeColumnDef(ByteArrayOutputStream out, CtrlColumnDef cd, int level)
            throws IOException {
        HwpBinaryWriter w = new HwpBinaryWriter(64);
        w.writeUInt32(cd.ctrlId);
        w.writeUInt16(cd.propertyLow);
        w.writeHwpUnit16(cd.columnGap);

        // 같은 크기가 아닌 경우의 컬럼 너비
        if (cd.columnWidths != null && cd.columnWidths.length > 0) {
            for (int cw : cd.columnWidths) {
                w.writeUInt16(cw);
            }
        }

        w.writeUInt16(cd.propertyHigh);
        w.writeUInt8(cd.dividerType);
        w.writeUInt8(cd.dividerWidth);
        w.writeColorRef(cd.dividerColor);
        out.write(RecordWriter.buildRecord(HwpTagId.CTRL_HEADER, level, w.toByteArray()));
    }

    // --- CtrlTable ---

    /**
     * 표 컨트롤 쓰기.
     * 공통 개체 속성을 갖는 CTRL_HEADER 다음에 TABLE 레코드, 그리고 셀 순서.
     *
     * TABLE property 참조 값: 0x00000006 = bits 0-1=2 (셀 단위 분할) + bit 2=1 (머리 반복).
     * "CELL" pageBreak는 bits 0-1에서 값 2에 해당 (1 아님!).
     */
    private static void writeTable(ByteArrayOutputStream out, CtrlTable tbl, int level)
            throws IOException {
        // 공통 개체 속성을 갖는 CTRL_HEADER
        out.write(buildCommonCtrlHeader(tbl.ctrlId, tbl.objProperty, tbl.vertOffset,
                tbl.horzOffset, tbl.width, tbl.height, tbl.zOrder, tbl.margins,
                tbl.instanceId, tbl.pageBreakPrev, tbl.description, level));

        // TABLE 레코드
        out.write(buildTableRecord(tbl, level + 1));

        // 셀
        for (TableCell cell : tbl.cells) {
            writeTableCell(out, cell, level + 1);
        }
    }

    /**
     * TABLE 레코드: rowCount에 따라 가변 크기.
     *
     * UINT32 property(4) + UINT16 rowCount(2) + UINT16 colCount(2) +
     * HWPUNIT16 cellSpacing(2) + HWPUNIT16 padL(2) + padR(2) + padT(2) + padB(2) +
     * WORD[rowCount] rowHeights(2*rowCount) + UINT16 borderFillId(2) +
     * UINT16 zoneCount(2)
     *
     * 참조본: 28 = 4+2+2+2+8+6+2+2 = 28 (3행: rowHeights 6 byte)
     * 참조본의 행 높이 값: 1,1,1 (placeholder 값).
     */
    private static byte[] buildTableRecord(CtrlTable tbl, int level) {
        HwpBinaryWriter w = new HwpBinaryWriter(64);
        w.writeUInt32(tbl.property);            // 4 byte
        w.writeUInt16(tbl.rowCount);            // 2 byte
        w.writeUInt16(tbl.colCount);            // 2 byte
        w.writeHwpUnit16(tbl.cellSpacing);      // 2 byte
        w.writeHwpUnit16(tbl.padLeft);          // 2 byte
        w.writeHwpUnit16(tbl.padRight);         // 2 byte
        w.writeHwpUnit16(tbl.padTop);           // 2 byte
        w.writeHwpUnit16(tbl.padBottom);        // 2 byte

        // 행별 높이 (행당 WORD)
        if (tbl.rowHeights != null) {
            for (int rh : tbl.rowHeights) {
                w.writeUInt16(rh);
            }
        }

        w.writeUInt16(tbl.borderFillId);        // 2 byte
        w.writeUInt16(tbl.zoneCount);           // 2 byte
        // zone 데이터 생략 (zoneCount는 보통 0)

        return RecordWriter.buildRecord(HwpTagId.TABLE, level, w.toByteArray());
    }

    /**
     * 표 셀용 LIST_HEADER: 정확히 47 byte.
     *
     * 리스트 헤더 기본(8 byte):
     *   INT16 paraCount(2) + UINT32 listProperty(4) + UINT16 padding(2)
     *
     * 셀 전용 속성(26 byte):
     *   UINT16 colAddr(2) + UINT16 rowAddr(2) + UINT16 colSpan(2) + UINT16 rowSpan(2) +
     *   HWPUNIT width(4) + HWPUNIT height(4) + HWPUNIT16[4] margins(8) +
     *   UINT16 borderFillId(2)
     *
     * 추가 버전 필드(13 byte):
     *   HWPUNIT width_copy(4) + BYTE[9] padding(9)
     *
     * 총합: 8 + 26 + 13 = 47 byte
     *
     * listProperty: vertAlign이 bit 21에 위치 (bit 5 아님!)
     *
     * 셀 문단은 LIST_HEADER와 동일한 level에 작성됨 (level+1 아님).
     */
    private static void writeTableCell(ByteArrayOutputStream out, TableCell cell, int level)
            throws IOException {
        // v14.4: 한/글 프로그램은 표 셀의 paragraphCount=0 LIST_HEADER 를 거부하고
        // "파일 손상" 으로 처리한다. 셀에 paragraph 가 없으면 안전한 빈 paragraph
        // 1 개를 자동 보정 — paragraph 자체는 writeParagraph 안에서 다시 빈
        // CHAR_SHAPE/LINE_SEG 보정을 받게 된다.
        if (cell.paragraphs.isEmpty()) {
            Paragraph emptyP = new Paragraph();
            emptyP.nChars = 1;       // 빈 paragraph 도 0x0D para end 1자
            emptyP.paraShapeId = 0;
            emptyP.styleId = 0;
            cell.paragraphs.add(emptyP);
        }

        HwpBinaryWriter w = new HwpBinaryWriter(48);

        // hwplib 기준 list header base (8 byte): readSInt4 + readUInt4
        w.writeInt32(cell.paragraphs.size());    // 4 byte: paraCount (INT32!)
        w.writeUInt32(cell.listHeaderProperty);  // 4 byte: vertAlign이 bit 21에 위치

        // 셀 전용 속성: colIndex, rowIndex, colSpan, rowSpan, width, height...
        w.writeUInt16(cell.colAddr);             // 2 byte: colIndex
        w.writeUInt16(cell.rowAddr);             // 2 byte: rowIndex
        w.writeUInt16(cell.colSpan);             // 2 byte
        w.writeUInt16(cell.rowSpan);             // 2 byte
        w.writeHwpUnit(cell.width);              // 4 byte
        w.writeHwpUnit(cell.height);             // 4 byte
        for (int i = 0; i < 4; i++) {
            w.writeHwpUnit16(cell.margins[i]);   // 총 8 byte (L,R,T,B)
        }
        w.writeUInt16(cell.borderFillId);        // 2 byte

        // v15.6: 추가 버전 필드 — 셀 내부 텍스트 영역 폭 (textWidth).
        //   이전 코드는 cell.width 를 다시 쓰는 "복사본" 으로 잘못 처리.
        //   hwplib ForCell.listHeader 분석 + 한/글 원본 byte 비교 결과,
        //   이 4 byte 는 subList textWidth (cell width 와 다른 값) 임을 확인.
        //   parseTableCell 이 이미 cell.textWidth 에 채워 두므로 그대로 사용.
        w.writeHwpUnit(cell.textWidth > 0 ? cell.textWidth : cell.width);
        w.writePad(9);                           // 9 byte: 0 패딩
        // 총합: 8 + 26 + 13 = 47 byte

        out.write(RecordWriter.buildRecord(HwpTagId.LIST_HEADER, level, w.toByteArray()));

        // 셀 문단은 LIST_HEADER와 동일한 level에 위치
        // 마지막 문단은 nChars bit 31이 설정되어야 함 (lastInList 플래그)
        for (int pi = 0; pi < cell.paragraphs.size(); pi++) {
            boolean isLast = (pi == cell.paragraphs.size() - 1);
            writeParagraph(out, cell.paragraphs.get(pi), level, true, isLast);
        }
    }

    // --- CtrlPicture ---

    /**
     * 그림 컨트롤 쓰기.
     *
     * 기록되는 레코드:
     *   CTRL_HEADER (level): ctrlId('gso ') + 공통 개체 속성 (46+2*descLen byte)
     *   SHAPE_COMPONENT (level+1): ctrlId 두 번 기록 + 변환 + 렌더링 행렬
     *   SHAPE_COMPONENT_PICTURE (level+2): 테두리 + imageRect + crop + padding + imageInfo
     *
     * gso CTRL_HEADER 크기 = 46 + 2*descLen. 72자 description의 경우 = 190 byte.
     * description 문자열은 HWPX picture 요소로부터 정확히 전달되어야 한다.
     */
    private static void writePicture(ByteArrayOutputStream out, CtrlPicture pic, int level)
            throws IOException {
        // 공통 개체 속성을 갖는 CTRL_HEADER (ctrlId는 GSO, PICTURE 아님)
        out.write(buildCommonCtrlHeader(pic.ctrlId, pic.objProperty, pic.vertOffset,
                pic.horzOffset, pic.width, pic.height, pic.zOrder, pic.margins,
                pic.instanceId, pic.pageBreakPrev, pic.description, level));

        // 수식 개체(ctrlId "eqed")는 CTRL_HEADER 뒤에 shape-component 레코드
        // 대신 HWPTAG_EQEDIT 페이로드를 담는다. 이것이 없으면 한글에서 수식
        // 영역이 공백으로 표시되며 — 이는 TA-04의 수식 셀에서 관측된 버그이다.
        if ("equation".equals(pic.shapeType)) {
            out.write(buildEquationRecord(pic, level + 1));
            return;
        }

        // shapeType 기반으로 shape별 ctrlId 결정
        int shapeCtrlId = CtrlId.PICTURE; // 'pic'에 대한 기본값
        int shapeTag = HwpTagId.SHAPE_COMPONENT_PICTURE; // shape 레코드의 기본 태그
        if (pic.shapeType != null) {
            switch (pic.shapeType) {
                case "rect": shapeCtrlId = CtrlId.RECTANGLE; shapeTag = HwpTagId.SHAPE_COMPONENT_RECT; break;
                case "line": shapeCtrlId = CtrlId.LINE; shapeTag = HwpTagId.SHAPE_COMPONENT_LINE; break;
                case "ellipse": shapeCtrlId = CtrlId.ELLIPSE; shapeTag = HwpTagId.SHAPE_COMPONENT_ELLIPSE; break;
                case "arc": shapeCtrlId = CtrlId.ARC; shapeTag = HwpTagId.SHAPE_COMPONENT_ARC; break;
                case "polygon": shapeCtrlId = CtrlId.POLYGON; shapeTag = HwpTagId.SHAPE_COMPONENT_POLYGON; break;
                case "curve": shapeCtrlId = CtrlId.CURVE; shapeTag = HwpTagId.SHAPE_COMPONENT_CURVE; break;
                default: shapeCtrlId = CtrlId.PICTURE; break;
            }
        }

        // SHAPE_COMPONENT (tag 0x04C)는 level+1에 위치
        // GenShapeObject의 경우 ctrlId가 두 번 기록된다.
        HwpBinaryWriter sw = new HwpBinaryWriter(256);
        sw.writeUInt32(shapeCtrlId);       // 4: shape 전용 ctrlId (1회차 기록)
        sw.writeUInt32(shapeCtrlId);       // 4: shape 전용 ctrlId (2회차 기록)

        // initialWidth/Height = 원본 이미지 크기 (스케일링 이전)
        // currentWidth/Height = 표시 크기 (스케일링 이후)
        int initW = pic.originalWidth > 0 ? pic.originalWidth : pic.width;
        int initH = pic.originalHeight > 0 ? pic.originalHeight : pic.height;
        int curW = pic.width;
        int curH = pic.height;

        // xOffset/yOffset: HWPX transMatrix e3/e6에서 가져오거나 계산한 값
        int scXOff, scYOff;
        if (pic.transMatrix != null) {
            scXOff = (int) pic.transMatrix[2]; // e3 = tx
            scYOff = (int) pic.transMatrix[5]; // e6 = ty
        } else {
            scXOff = -(initW - curW) / 2;
            scYOff = -(initH - curH) / 2;
        }

        sw.writeInt32(scXOff);             // 4: 그룹 내 xOffset
        sw.writeInt32(scYOff);             // 4: 그룹 내 yOffset
        sw.writeUInt16(0);                 // 2: groupLevel
        sw.writeUInt16(1);                 // 2: localVersion
        sw.writeHwpUnit(initW);            // 4: initialWidth (원본 크기)
        sw.writeHwpUnit(initH);            // 4: initialHeight (원본 크기)
        sw.writeHwpUnit(curW);             // 4: currentWidth (표시 크기)
        sw.writeHwpUnit(curH);             // 4: currentHeight (표시 크기)
        // SHAPE_COMPONENT property — shape type 마다 값이 다름.
        //   $pic (그림): 한/글 참조본 byte 비교 시 0x24080000 — bits 19/26/29.
        //   $rec (사각형): 한/글 참조본 0x01090000 — bits 16/19/24.
        //   bit 19 = RotateWithImage (hwplib BitFlag 검증). 다른 비트들은 한컴
        //   문서에 없으나 한/글이 shape 별 유효 비트를 strict 검증하는 것으로
        //   보임 (v15.7 rect 좌표를 ref 와 동일하게 맞춰도 거부 → property 가
        //   $pic 패턴이라 mismatch). $rec 일 때 0x01090000 으로 분기.
        long scProp = 0;
        boolean scaled = (initW != curW) || (initH != curH);
        if (scaled) {
            if ("rect".equals(pic.shapeType)) {
                scProp = 0x01090000L;
            } else if ("pic".equals(pic.shapeType) || pic.shapeType == null) {
                scProp = 0x24080000L;
            } else {
                // line / ellipse / arc / polygon / curve — 기본적으로 rect 와 동일 패턴
                scProp = 0x01090000L;
            }
        }
        sw.writeUInt32(scProp);            // 4: property (flip/scale 플래그)
        sw.writeUInt16(0);                 // 2: 회전 각도 (도)
        sw.writeInt32(curW / 2);           // 4: 회전 중심 X
        sw.writeInt32(curH / 2);           // 4: 회전 중심 Y

        // 렌더링 정보: WORD matrixPairCount + 행렬들
        // 각 행렬은 3x2 아핀: [e1, e2, e3, e4, e5, e6], 6개 double로 저장 (48 byte)
        sw.writeUInt16(1);                 // 2: 행렬 쌍 개수 = 1

        if (pic.transMatrix != null && pic.scaMatrix != null && pic.rotMatrix != null) {
            // HWPX renderingInfo의 정확한 값 사용
            for (double v : pic.transMatrix) writeDouble(sw, v);
            for (double v : pic.scaMatrix)   writeDouble(sw, v);
            for (double v : pic.rotMatrix)   writeDouble(sw, v);
        } else {
            // 크기로부터 행렬 계산
            double scaleX = (initW > 0) ? (double) curW / initW : 1.0;
            double scaleY = (initH > 0) ? (double) curH / initH : 1.0;

            // 이동: [1, 0, xOff, 0, 1, yOff]
            writeDouble(sw, 1.0); writeDouble(sw, 0.0); writeDouble(sw, (double) scXOff);
            writeDouble(sw, 0.0); writeDouble(sw, 1.0); writeDouble(sw, (double) scYOff);

            // 스케일: [scaleX, 0, -xOff, 0, scaleY, -yOff]
            writeDouble(sw, scaleX); writeDouble(sw, 0.0); writeDouble(sw, (double) -scXOff);
            writeDouble(sw, 0.0); writeDouble(sw, scaleY); writeDouble(sw, (double) -scYOff);

            // 회전: 단위 행렬
            writeDouble(sw, 1.0); writeDouble(sw, 0.0); writeDouble(sw, 0.0);
            writeDouble(sw, 0.0); writeDouble(sw, 1.0); writeDouble(sw, 0.0);
        }

        // v13.40: 그리기 개체 (rect/line/ellipse/arc/polygon/curve) 는 SHAPE_COMPONENT 레코드
        // 안에 LineInfo + FillInfo 를 추가 포함해야 한다. 누락 시 hwplib 파서가 lineInfo=null
        // 로 읽어 hwp2hwpx.ForDrawingObject.lineShape() 에서 NPE 발생.
        // (참조: hwplib ForShapeComponentForNormal.write(): gsoId -> CommonPart -> lineInfo(13)
        //         -> fillInfo(변동, type=0 이면 8) -> shadowInfo(22, null 가능) -> rest)
        //
        // v15.5: ShadowInfo(22 byte) 도 반드시 기록해야 한다. 한/글이 생성한 참조본 HWP 의
        // SHAPE_COMPONENT 와 byte 비교 결과, 우리 출력은 22 byte 짧았고 그 만큼이
        // ShadowInfo 영역이었음. hwplib 는 null shadow 를 허용하지만 한/글은 "파일이
        // 손상되었습니다." 로 거부 — 입찰공고문 손상 회귀의 진짜 원인. (참조본 size=239
        // = LineInfo 13 + FillInfo 8 + ShadowInfo 22 + 나머지 196 byte;
        //  우리 v15.4 size=217 = ShadowInfo 22 byte 누락)
        boolean isDrawingShape = pic.shapeType != null && !"pic".equals(pic.shapeType);
        if (isDrawingShape) {
            // LineInfo (13 byte) — 기본 검은 0.5mm 실선
            sw.writeUInt32(0x00000000L); // color: 검정 RGBA 0
            sw.writeInt32(50);           // thickness: HWPUNIT 50 (약 0.18mm, 기본 얇은 선)
            sw.writeUInt32(0);           // property (선 유형·끝 모양·화살 등 플래그, 기본 0)
            sw.writeUInt8(0);            // outlineStyle: 0 = SOLID
            // FillInfo (8 byte) — type=0 (채움 없음) + 4 zero-pad
            sw.writeUInt32(0);           // type = NULL (채움 없음)
            sw.writeUInt32(0);           // 4 byte zero padding (type==0 branch)
            // ShadowInfo (22 byte) — 그림자 없음 (모든 필드 0)
            // 구조: BYTE shadowType(1) + COLORREF color(4) + INT32 offsetX(4) + INT32 offsetY(4)
            //       + UINT8 alpha(1) + 8 byte 패딩 = 22 byte
            sw.writePad(22);
        }

        out.write(RecordWriter.buildRecord(HwpTagId.SHAPE_COMPONENT, level + 1, sw.toByteArray()));

        // v15.5: 한/글 참조본의 정확한 순서로 복귀.
        //   한/글이 생성한 입찰공고문.hwp 와 record 위치를 비교한 결과 (v15.4 의
        //   추정은 오류였음):
        //     올바른 순서: SHAPE_COMPONENT → LIST_HEADER(textbox) → 단락 → SHAPE_COMPONENT_RECT
        //   즉 shape 전용 레코드는 textbox 뒤에 와야 한다. (TABLE 패턴이 아닌
        //   GenShapeObject 패턴 — hwplib ForRectangle 의 emit 순서와도 일치.)
        if (!pic.textboxParagraphs.isEmpty()) {
            // 텍스트박스 LIST_HEADER 형식 (hwplib ForTextBox 리더와 일치):
            //   INT32 paraCount(4) + UINT32 property(4) +
            //   UINT16 margins[4](8) + UINT32 textWidth(4)
            //   = 기본 20 byte, 13 byte 버전 패딩 추가 = 총 33 byte
            HwpBinaryWriter lw = new HwpBinaryWriter(48);
            lw.writeInt32(pic.textboxParagraphs.size());    // 4 byte: paraCount (INT32, INT16 아님)
            lw.writeUInt32(pic.textboxListProperty);        // 4 byte: 리스트 속성
            lw.writeHwpUnit16(pic.textboxMarginLeft);       // 2 byte
            lw.writeHwpUnit16(pic.textboxMarginRight);      // 2 byte
            lw.writeHwpUnit16(pic.textboxMarginTop);        // 2 byte
            lw.writeHwpUnit16(pic.textboxMarginBottom);     // 2 byte
            lw.writeHwpUnit(pic.textboxLastWidth);          // 4 byte: textWidth
            lw.writePad(13);                                // 13 byte: 버전 패딩
            // 총합: 4+4+8+4+13 = 33 byte
            out.write(RecordWriter.buildRecord(HwpTagId.LIST_HEADER, level + 2, lw.toByteArray()));

            // 문단은 LIST_HEADER와 동일한 level(level+2)에 위치
            for (int pi = 0; pi < pic.textboxParagraphs.size(); pi++) {
                boolean isLast = (pi == pic.textboxParagraphs.size() - 1);
                writeParagraph(out, pic.textboxParagraphs.get(pi), level + 2, false, isLast);
            }
        }

        // Shape 전용 레코드는 level+2에 위치 — textbox 다음에 옴
        if ("pic".equals(pic.shapeType) || pic.shapeType == null) {
            // SHAPE_COMPONENT_PICTURE (tag 0x055)
            HwpBinaryWriter pw = new HwpBinaryWriter(128);
            pw.writeColorRef(pic.borderColor);
            pw.writeInt32(pic.borderThickness);
            pw.writeUInt32(pic.borderProperty);
            for (int i = 0; i < 4; i++) pw.writeInt32(pic.imageRectX[i]);
            for (int i = 0; i < 4; i++) pw.writeInt32(pic.imageRectY[i]);
            pw.writeInt32(pic.cropLeft);
            pw.writeInt32(pic.cropTop);
            pw.writeInt32(pic.cropRight);
            pw.writeInt32(pic.cropBottom);
            pw.writeHwpUnit16(pic.imgPadLeft);
            pw.writeHwpUnit16(pic.imgPadRight);
            pw.writeHwpUnit16(pic.imgPadTop);
            pw.writeHwpUnit16(pic.imgPadBottom);
            pw.writeUInt8(pic.brightness);
            pw.writeUInt8(pic.contrast);
            pw.writeUInt8(pic.effect);
            pw.writeUInt16(pic.binItemId);
            pw.writeUInt8(pic.transparency);
            pw.writeUInt32(pic.pictureInstanceId);
            pw.writeUInt32(0); // 그림 효과 정보
            // SHAPE_COMPONENT_PICTURE는 orgSz가 아닌 imgDim(원본 픽셀 크기)을 사용
            int picOrigW = pic.imgDimWidth > 0 ? pic.imgDimWidth : pic.originalWidth;
            int picOrigH = pic.imgDimHeight > 0 ? pic.imgDimHeight : pic.originalHeight;
            pw.writeHwpUnit(picOrigW);
            pw.writeHwpUnit(picOrigH);
            pw.writeUInt8(0); // 이미지 투명도
            out.write(RecordWriter.buildRecord(HwpTagId.SHAPE_COMPONENT_PICTURE, level + 2, pw.toByteArray()));
        } else if ("rect".equals(pic.shapeType)) {
            // SHAPE_COMPONENT_RECTANGLE: cornerRadius(1) + 4 x POINT (8byte씩) = 33 byte.
            //   v15.7: HWP 5.0 spec 의 POINT[4] = (x,y) 쌍 interleave 가 정답.
            //   이전 v15.6 까지는 X[0..3] 4 개 다음에 Y[0..3] 4 개를 직렬화 → 한/글이
            //   (0, w) 등 비정상 좌표로 해석 → 사각형이 cross 모양 → "파일이 손상" 거부.
            //   또한 좌표는 SHAPE_COMPONENT 의 initialWidth/Height (hp:orgSz) 와 동일해야
            //   한/글이 받아들임. originalWidth/Height 가 0 이면 width/height 로 fallback.
            int rectW = pic.originalWidth > 0 ? pic.originalWidth : pic.width;
            int rectH = pic.originalHeight > 0 ? pic.originalHeight : pic.height;
            HwpBinaryWriter pw = new HwpBinaryWriter(64);
            pw.writeUInt8(0); // 모서리 반경 %
            pw.writeInt32(0);     pw.writeInt32(0);      // P0 = (0, 0)
            pw.writeInt32(rectW); pw.writeInt32(0);      // P1 = (w, 0)
            pw.writeInt32(rectW); pw.writeInt32(rectH);  // P2 = (w, h)
            pw.writeInt32(0);     pw.writeInt32(rectH);  // P3 = (0, h)
            out.write(RecordWriter.buildRecord(shapeTag, level + 2, pw.toByteArray()));
        } else if ("line".equals(pic.shapeType)) {
            // SHAPE_COMPONENT_LINE: startX(4)+startY(4)+endX(4)+endY(4)+flags(2) = 18 byte
            HwpBinaryWriter pw = new HwpBinaryWriter(32);
            pw.writeInt32(0); pw.writeInt32(0);
            pw.writeInt32(pic.width); pw.writeInt32(pic.height);
            pw.writeUInt16(0);
            out.write(RecordWriter.buildRecord(shapeTag, level + 2, pw.toByteArray()));
        } else if ("ellipse".equals(pic.shapeType)) {
            // SHAPE_COMPONENT_ELLIPSE: 60 byte (간략화)
            HwpBinaryWriter pw = new HwpBinaryWriter(64);
            pw.writeUInt32(0); // 속성
            int cx = pic.width / 2, cy = pic.height / 2;
            pw.writeInt32(cx); pw.writeInt32(cy); // 중심
            pw.writeInt32(pic.width); pw.writeInt32(0); // 축1
            pw.writeInt32(0); pw.writeInt32(pic.height); // 축2
            pw.writePad(32); // 시작/끝 위치
            out.write(RecordWriter.buildRecord(shapeTag, level + 2, pw.toByteArray()));
        } else {
            // 일반: 빈 shape 레코드 기록
            out.write(RecordWriter.buildRecord(shapeTag, level + 2, new byte[0]));
        }
    }

    // --- CtrlHeaderFooter ---

    /**
     * 머리말/꼬리말 컨트롤.
     * CTRL_HEADER: ctrlId(4) + property(4) = 8 byte
     * LIST_HEADER: paraCount(2) + listProp(4) + textWidth(4) + textHeight(4) +
     *              textRefFlag(1) + numRefFlag(1) = 16 byte
     * 그 다음 문단이 level+2에 위치.
     */
    private static void writeHeaderFooter(ByteArrayOutputStream out, CtrlHeaderFooter hf, int level)
            throws IOException {
        // CTRL_HEADER
        // HEADER/FOOTER CTRL_HEADER — 한컴 참조본과 일치시키기 위해 12 byte:
        //   ctrlId(4) + property(4) + 예약된 0 4 byte.
        // (한글이 생성하는 HWP는 일관되게 후속 4 byte를 포함하며,
        //  엄격한 리더가 추가 슬롯을 사용하므로 반드시 기록해야 한다.)
        HwpBinaryWriter w = new HwpBinaryWriter(32);
        w.writeUInt32(hf.ctrlId);
        w.writeUInt32(hf.property);
        w.writeUInt32(0);
        out.write(RecordWriter.buildRecord(HwpTagId.CTRL_HEADER, level, w.toByteArray()));

        // v15.6: 머리말/꼬리말용 LIST_HEADER (34 byte) — hwplib 의
        // ForListHeaderForHeaderFooter.write 와 1:1 매핑.
        //   INT32 paraCount(4) + UINT32 property(4) +
        //   UINT32 textWidth(4) + UINT32 textHeight(4) + 18 byte zero
        // 이전 v15.5 까지는 paraCount 를 INT16 으로 쓰고 textRef/numRef byte 를
        // 끼워넣어 한/글 원본과 모든 필드 offset 이 어긋났다. byte 비교 결과
        // 524 부터 부터 한/글 입찰공고문.hwp 원본과 mismatch 발생.
        HwpBinaryWriter lw = new HwpBinaryWriter(40);
        lw.writeInt32(hf.paragraphs.size());    // 4 byte: paraCount (INT32)
        lw.writeUInt32(0);                      // 4 byte: listProperty
        lw.writeHwpUnit(hf.textWidth);          // 4 byte: textWidth (UINT32)
        lw.writeHwpUnit(hf.textHeight);         // 4 byte: textHeight (UINT32)
        lw.writePad(18);                        // 18 byte: 예약 zero
        out.write(RecordWriter.buildRecord(HwpTagId.LIST_HEADER, level + 1, lw.toByteArray()));

        // 한컴 참조본에서 문단은 LIST_HEADER의 동급 형제(level+1)이며 —
        // 예전 코드의 level+2가 아니다. 올바른 깊이에 기록하면 적절한
        // 부모/자식 관계가 유지되어 한글이 다음 섹션의 페이지 레이아웃을
        // 정확히 계산한다 (표-텍스트 간 간격 붕괴 수정).
        for (int pi = 0; pi < hf.paragraphs.size(); pi++) {
            boolean isLast = (pi == hf.paragraphs.size() - 1);
            writeParagraph(out, hf.paragraphs.get(pi), level + 1, false, isLast);
        }
    }

    // --- CtrlField ---

    /**
     * 필드 컨트롤 (하이퍼링크, 책갈피, clickhere, 수식, 날짜 등).
     * ctrlId(4) + fieldProperty(4) + extraProperty(1) +
     * WORD cmdLen + WCHAR[] command + UINT32 fieldId
     */
    private static void writeField(ByteArrayOutputStream out, CtrlField fld, int level)
            throws IOException {
        HwpBinaryWriter w = new HwpBinaryWriter(64);
        w.writeUInt32(fld.ctrlId);
        w.writeUInt32(fld.fieldProperty);
        w.writeUInt8(fld.extraProperty);
        // 명령 문자열
        String cmd = (fld.command != null) ? fld.command : "";
        w.writeUInt16(cmd.length());
        w.writeUtf16Le(cmd);
        w.writeUInt32(fld.fieldId);
        w.writeUInt32(0); // 추가 4 byte 패딩 (참조본에서 관측됨)
        out.write(RecordWriter.buildRecord(HwpTagId.CTRL_HEADER, level, w.toByteArray()));

        // BOOKMARK 필드용 HWPTAG_CTRL_DATA: 책갈피 이름을 ParameterSet 형태로
        // 전달한다. 문서를 열 때 "?section_A"와 같은 내부 하이퍼링크 명령이
        // 올바른 앵커로 해석되도록 하기 위해 필요하다.
        // 스펙: HWP 5.0 §4.3.10.11 (책갈피) + §4.2.12 표 50 (Parameter Set).
        if (fld.ctrlId == CtrlId.FIELD_BOOKMARK && fld.name != null && !fld.name.isEmpty()) {
            out.write(buildBookmarkCtrlData(fld.name, level + 1));
        }
    }

    /**
     * 수식 개체용 HWPTAG_EQEDIT 레코드 생성. byte 레이아웃
     * (한글 참조본 TA-04에서 관측되었고 HWP 5.0 수식 스펙과 일치):
     * <pre>
     *   UINT32 property           (보통 0)
     *   WORD   scriptLen          (WCHAR 개수)
     *   WCHAR  script[]           수식 텍스트 (예: "15,000 × 240 = ?")
     *   UINT32 baseUnit           HWPUNIT (기본 1000 = 1 문자)
     *   UINT32 reserved           0
     *   UINT32 baseLine           텍스트 기준 백분율 (기본 86)
     *   WORD   versionLen
     *   WCHAR  version[]          "Equation Version 60"
     *   WORD   fontLen
     *   WCHAR  font[]             "HancomEQN"
     * </pre>
     */
    private static byte[] buildEquationRecord(CtrlPicture eq, int level) {
        String script = eq.eqScript != null ? eq.eqScript : "";
        String version = eq.eqVersion != null ? eq.eqVersion : "Equation Version 60";
        String font = eq.eqFont != null ? eq.eqFont : "HancomEQN";

        HwpBinaryWriter w = new HwpBinaryWriter(64 + 2 * (script.length() + version.length() + font.length()));
        w.writeUInt32(eq.eqProperty);
        w.writeUInt16(script.length());
        w.writeUtf16Le(script);
        w.writeUInt32(eq.eqBaseUnit);
        w.writeUInt32(0);                 // 예약
        w.writeUInt32(eq.eqBaseLine);
        w.writeUInt16(version.length());
        w.writeUtf16Le(version);
        w.writeUInt16(font.length());
        w.writeUtf16Le(font);
        return RecordWriter.buildRecord(HwpTagId.EQEDIT, level, w.toByteArray());
    }

    /**
     * 책갈피 이름용 HWPTAG_CTRL_DATA 레코드 생성.
     *
     * byte 레이아웃 (한컴 참조 출력과 일치):
     *   WORD   setID          = 0x021B
     *   INT16  itemCount      = 1
     *   UINT32 itemHeader     = 0x40000000  (item id = 0, high flag = 0x4000)
     *   WORD   itemType       = 1  (PIT_BSTR)
     *   WORD   strLen         = N  (WCHAR 개수)
     *   WCHAR  name[strLen]   (UTF-16 LE)
     * 총합: 12 + 2*N byte.
     */
    private static byte[] buildBookmarkCtrlData(String name, int level) {
        HwpBinaryWriter w = new HwpBinaryWriter(12 + 2 * name.length());
        w.writeUInt16(0x021B);     // paramSetID
        w.writeInt16(1);           // 항목 개수
        w.writeUInt32(0x40000000L);// 항목 헤더 (id=0, flag=0x4000)
        w.writeUInt16(1);          // itemType = PIT_BSTR
        w.writeUInt16(name.length());
        w.writeUtf16Le(name);
        return RecordWriter.buildRecord(HwpTagId.CTRL_DATA, level, w.toByteArray());
    }

    // -----------------------------------------------------------------------
    // 공용 헬퍼
    // -----------------------------------------------------------------------

    /**
     * 공통 개체 속성을 포함한 CTRL_HEADER 레코드 생성.
     * CtrlTable과 CtrlPicture 양쪽에서 사용.
     *
     * 형식: ctrlId(4) + objProperty(4) + vertOffset(4) + horzOffset(4) +
     *       width(4) + height(4) + zOrder(4) + margins[4](8) + instanceId(4) +
     *       pageBreakPrev(4) + descLen(2) + desc(2*descLen)
     * 총합: 46 + 2*descLen byte
     */
    private static byte[] buildCommonCtrlHeader(int ctrlId, long objProperty,
            int vertOffset, int horzOffset, int width, int height,
            int zOrder, int[] margins, long instanceId, int pageBreakPrev,
            String description, int level) {
        HwpBinaryWriter w = new HwpBinaryWriter(256);
        w.writeUInt32(ctrlId);             // 4 byte
        w.writeUInt32(objProperty);        // 4 byte
        w.writeHwpUnit(vertOffset);        // 4 byte
        w.writeHwpUnit(horzOffset);        // 4 byte
        w.writeHwpUnit(width);             // 4 byte
        w.writeHwpUnit(height);            // 4 byte
        w.writeInt32(zOrder);              // 4 byte
        for (int i = 0; i < 4; i++) {
            w.writeHwpUnit16(margins[i]);  // 총 8 byte
        }
        w.writeUInt32(instanceId);         // 4 byte
        w.writeInt32(pageBreakPrev);       // 4 byte
        // description 문자열
        String desc = (description != null) ? description : "";
        w.writeUInt16(desc.length());      // 2 byte
        w.writeUtf16Le(desc);              // 2*descLen byte
        return RecordWriter.buildRecord(HwpTagId.CTRL_HEADER, level, w.toByteArray());
    }

    /** double를 8 byte little-endian (IEEE 754)로 기록. */
    private static void writeDouble(HwpBinaryWriter w, double v) {
        long bits = Double.doubleToLongBits(v);
        w.writeUInt32(bits & 0xFFFFFFFFL);
        w.writeUInt32((bits >>> 32) & 0xFFFFFFFFL);
    }
}
