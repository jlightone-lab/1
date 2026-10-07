package kr.n.nframe.hwplib.writer;

import kr.n.nframe.hwplib.binary.HwpBinaryWriter;
import kr.n.nframe.hwplib.binary.RecordWriter;
import kr.n.nframe.hwplib.constants.HwpTagId;
import kr.n.nframe.hwplib.model.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * HWP 5.0 파일의 DocInfo 스트림 레코드를 생성한다.
 * 원시(비압축) byte를 반환한다. 압축은 HwpWriter가 처리한다.
 *
 * 모든 레코드 포맷은 한글 워드프로세서가 생성한 참조 HWP 파일의 byte 분석과 정확히 일치한다.
 * 한글 워드프로세서가 생성한 결과.
 */
public class DocInfoWriter {

    private DocInfoWriter() {}

    /**
     * 모든 DocInfo 레코드를 올바른 순서로 연결하여 생성한다.
     *
     * @param doc HWP 문서
     * @return 원시 레코드 byte (비압축)
     */
    public static byte[] build(HwpDocument doc) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);

            out.write(buildDocumentProperties(doc.docProperties));
            out.write(buildIdMappings(doc.idMappings));

            // BIN_DATA 레코드 (레벨 1)
            for (BinDataItem item : doc.binDataItems) {
                out.write(buildBinData(item));
            }

            // FACE_NAME 레코드 (레벨 1) - 7개 언어 그룹을 순서대로
            for (int lang = 0; lang < 7; lang++) {
                if (lang < doc.faceNames.size()) {
                    List<FaceName> faceNames = doc.faceNames.get(lang);
                    for (FaceName fn : faceNames) {
                        out.write(buildFaceName(fn));
                    }
                }
            }

            // BORDER_FILL 레코드 (레벨 1)
            for (BorderFill bf : doc.borderFills) {
                out.write(buildBorderFill(bf));
            }

            // CHAR_SHAPE 레코드 (레벨 1)
            for (CharShape cs : doc.charShapes) {
                out.write(buildCharShape(cs));
            }

            // TAB_DEF 레코드 (레벨 1)
            for (TabDef td : doc.tabDefs) {
                out.write(buildTabDef(td));
            }

            // NUMBERING 레코드 (레벨 1)
            for (Numbering nb : doc.numberings) {
                out.write(buildNumbering(nb));
            }

            // BULLET 레코드 (레벨 1)
            for (Bullet bl : doc.bullets) {
                out.write(buildBullet(bl));
            }

            // PARA_SHAPE 레코드 (레벨 1)
            for (ParaShape ps : doc.paraShapes) {
                out.write(buildParaShape(ps));
            }

            // STYLE 레코드 (레벨 1)
            for (Style st : doc.styles) {
                out.write(buildStyle(st));
            }

            // DOC_DATA (레벨 0) - 한글 워드프로세서에서 필수
            out.write(buildDocData());

            // FORBIDDEN_CHAR (레벨 1)
            out.write(buildForbiddenChar());

            // COMPATIBLE_DOCUMENT (레벨 0) — HWPX 소스에서 가져온 값
            // ODT 소스 경로(emitCompatibleDocument=false)에서는 생략한다(변형B).
            if (doc.emitCompatibleDocument) {
                out.write(buildCompatibleDocument(doc.compatTargetProgram));
            }

            // LAYOUT_COMPATIBILITY (레벨 1) — 5개의 UINT32 레벨 마스크
            out.write(buildLayoutCompatibility(doc.layoutCompatLevels));

            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build DocInfo", e);
        }
    }

    // -----------------------------------------------------------------------
    // 개별 레코드 빌더
    // -----------------------------------------------------------------------

    /** DOCUMENT_PROPERTIES: 26 byte. */
    private static byte[] buildDocumentProperties(DocProperties dp) {
        HwpBinaryWriter w = new HwpBinaryWriter(26);
        w.writeUInt16(dp.sectionCount);
        w.writeUInt16(dp.pageStartNum);
        w.writeUInt16(dp.footnoteStartNum);
        w.writeUInt16(dp.endnoteStartNum);
        w.writeUInt16(dp.pictureStartNum);
        w.writeUInt16(dp.tableStartNum);
        w.writeUInt16(dp.equationStartNum);
        w.writeUInt32(dp.caretListId);
        w.writeUInt32(dp.caretParaId);
        w.writeUInt32(dp.caretPos);
        return RecordWriter.buildRecord(HwpTagId.DOCUMENT_PROPERTIES, 0, w.toByteArray());
    }

    /** ID_MAPPINGS: 72 byte (18개의 INT32 카운트). */
    private static byte[] buildIdMappings(IdMappings im) {
        HwpBinaryWriter w = new HwpBinaryWriter(72);
        for (int i = 0; i < 18; i++) {
            w.writeInt32(im.counts[i]);
        }
        return RecordWriter.buildRecord(HwpTagId.ID_MAPPINGS, 0, w.toByteArray());
    }

    /** BIN_DATA: UINT16 property + UINT16 binDataId + WORD nameLen + WCHAR[] ext. */
    private static byte[] buildBinData(BinDataItem item) {
        HwpBinaryWriter w = new HwpBinaryWriter(64);
        // property: 비트 0-3에 타입; 1=EMBEDDING
        int prop = (item.type & 0x0F);
        w.writeUInt16(prop);
        w.writeUInt16(item.binDataId);
        // 확장자를 WORD 길이 + WCHAR[]로 기록
        String ext = (item.extension != null) ? item.extension : "";
        w.writeUInt16(ext.length());
        w.writeUtf16Le(ext);
        return RecordWriter.buildRecord(HwpTagId.BIN_DATA, 1, w.toByteArray());
    }

    /**
     * FACE_NAME 레코드.
     * Property byte 레이아웃:
     *   bit 7 (0x80): hasAltFont
     *   bit 6 (0x40): hasTypeInfo
     *   bit 5 (0x20): hasDefaultFont
     *   bits 0-4: fontType/flags (참조 파일은 하위 비트에 0x01 사용)
     */
    private static byte[] buildFaceName(FaceName fn) {
        HwpBinaryWriter w = new HwpBinaryWriter(128);
        // property byte: boolean 플래그 + 하위 비트의 fontType으로 구성
        int prop = (fn.fontType & 0x1F);
        if (fn.hasAltFont) prop |= 0x80;
        if (fn.hasTypeInfo) prop |= 0x40;
        if (fn.hasDefaultFont) prop |= 0x20;
        w.writeUInt8(prop);

        // 이름: WORD 길이 + WCHAR[]
        String name = (fn.name != null) ? fn.name : "";
        w.writeUInt16(name.length());
        w.writeUtf16Le(name);

        // 선택적 대체 폰트
        if (fn.hasAltFont) {
            w.writeUInt8(fn.altFontType);
            String altName = (fn.altFontName != null) ? fn.altFontName : "";
            w.writeUInt16(altName.length());
            w.writeUtf16Le(altName);
        }

        // 선택적 타입 정보 (10 byte PANOSE)
        if (fn.hasTypeInfo) {
            if (fn.typeInfo != null && fn.typeInfo.length == 10) {
                w.writeBytes(fn.typeInfo);
            } else {
                w.writePad(10);
            }
        }

        // 선택적 기본 폰트
        if (fn.hasDefaultFont) {
            String defName = (fn.defaultFontName != null) ? fn.defaultFontName : "";
            w.writeUInt16(defName.length());
            w.writeUtf16Le(defName);
        }

        return RecordWriter.buildRecord(HwpTagId.FACE_NAME, 1, w.toByteArray());
    }

    /**
     * BORDER_FILL 레코드 - 참조 바이너리 분석에서 도출한 정확한 포맷.
     *
     * 구조:
     *   UINT16 property (2)
     *   Border 데이터: 4*(type(1)+width(1)+color(4)) = L/R/T/B에 대해 24 byte
     *   Diagonal: type(1)+width(1)+color(4) = 6 byte
     *   Total border: 2 + 24 + 6 = 32 byte
     *
     * Border 데이터 뒤의 Fill 섹션:
     *   UINT32 fillType (4)
     *   if (fillType & 1): COLORREF bgColor(4) + COLORREF patColor(4) + INT32 patType(4)
     *   if (fillType & 4): INT16 gradType(2) + INT16 angle(2) + INT16 centerX(2) +
     *                       INT16 centerY(2) + INT16 step(2) + INT16 colorNum(2) +
     *                       [INT32 positions(4*num) if num>2] + COLORREF colors(4*num)
     *   if (fillType & 2): BYTE imgType(1) + INT8 brightness(1) + INT8 contrast(1) +
     *                       BYTE effect(1) + UINT16 binItemId(2)
     *   UINT32 additionalSize (4) -- gradient 비트가 설정되면 1, 아니면 0
     *   if additionalSize > 0: BYTE gradCenter(1)
     */
    private static byte[] buildBorderFill(BorderFill bf) {
        HwpBinaryWriter w = new HwpBinaryWriter(128);

        // Property (2 byte)
        w.writeUInt16(bf.property);

        // Border 데이터: left, right, top, bottom (각각: type + width + color)
        for (int i = 0; i < 4; i++) {
            w.writeUInt8(bf.borderTypes[i]);
            w.writeUInt8(bf.borderWidths[i]);
            w.writeColorRef(bf.borderColors[i]);
        }

        // 대각선 테두리
        w.writeUInt8(bf.diagType);
        w.writeUInt8(bf.diagWidth);
        w.writeColorRef(bf.diagColor);

        // --- Fill 섹션 ---
        w.writeUInt32(bf.fillType);

        boolean hasSolid = (bf.fillType & 0x01) != 0;
        boolean hasImage = (bf.fillType & 0x02) != 0;
        boolean hasGradient = (bf.fillType & 0x04) != 0;

        // 단색 채우기 데이터
        if (hasSolid) {
            w.writeColorRef(bf.fillBgColor);   // 4 byte
            w.writeColorRef(bf.fillPatColor);  // 4 byte
            w.writeInt32(bf.fillPatType);      // 4 byte
        }

        // 그라디언트 채우기 데이터 — HWP 5.0 spec 정합 포맷.
        // v15.10: 입찰공고문.hwp 참조본 byte 비교 결과 spec 표준 layout 확인:
        //   BYTE type(1) + INT32 angle(4) + INT32 centerX(4) + INT32 centerY(4)
        //   + INT32 step(4) + INT32 colorNum(4) + COLORREF colors[colorNum]*4
        //   = 21 byte 헤더 + 4*N byte colors
        // 이전 v15.9 까지: INT16 필드 + 다중 padding 으로 21 byte 를 채웠으나
        //   spec 와 어긋남. 한/글이 gradient 헤더를 잘못 해석하여 colorNum 이
        //   garbage 값으로 읽혀 black fallback 으로 셀이 검정 채움 → "검은배경
        //   의 그림" 회귀의 진짜 원인.
        if (hasGradient) {
            w.writeUInt8(bf.gradType);         // [+0]: 그라디언트 타입 (BYTE)
            w.writeInt32(bf.gradAngle);        // [+1..+4]: 시작 각도 (INT32)
            w.writeInt32(bf.gradCenterX);      // [+5..+8]: 중심 X (INT32, 퍼센트)
            w.writeInt32(bf.gradCenterY);      // [+9..+12]: 중심 Y (INT32, 퍼센트)
            w.writeInt32(bf.gradStep);         // [+13..+16]: step (INT32)
            w.writeInt32(bf.gradColorNum);     // [+17..+20]: 색상 개수 (INT32)
            // 색상 배열
            if (bf.gradColors != null) {
                for (int i = 0; i < bf.gradColorNum && i < bf.gradColors.length; i++) {
                    w.writeColorRef(bf.gradColors[i]);
                }
            }
        }

        // 이미지 채우기 데이터
        if (hasImage) {
            w.writeUInt8(bf.imgType);          // 1 byte
            w.writeInt8(bf.imgBright);         // 1 byte
            w.writeInt8(bf.imgContrast);       // 1 byte
            w.writeUInt8(bf.imgEffect);        // 1 byte
            w.writeUInt16(bf.imgBinItemId);    // 2 byte
        }

        // 후행 채우기 데이터 (참조 바이너리로 검증됨):
        // - gradient: additionalSize=1 + step byte
        // - solid/image: additionalSize=0 + 1 패딩 byte
        // - 없음: additionalSize=0, 추가 byte 없음
        if (hasGradient) {
            w.writeUInt32(1);  // additionalSize=1
            w.writeUInt8(bf.gradStepCenter); // stepCenter 값 (참조: 0x32=50)
            w.writeUInt8(0);   // 후행 패딩 byte (참조 파일에 존재)
        } else if (bf.fillType != 0) {
            w.writeUInt32(0);  // additionalSize=0
            w.writeUInt8(0);   // 1 byte 패딩
        } else {
            w.writeUInt32(0);  // additionalSize=0, 추가 byte 없음
        }

        return RecordWriter.buildRecord(HwpTagId.BORDER_FILL, 1, w.toByteArray());
    }

    /**
     * CHAR_SHAPE 레코드.
     * WORD[7] fontIds + UINT8[7] ratios + INT8[7] spacings + UINT8[7] relSizes +
     * INT8[7] charOffsets + INT32 baseSize + UINT32 property + INT8 shadowX +
     * INT8 shadowY + COLORREF textColor + COLORREF underlineColor +
     * COLORREF shadeColor + COLORREF shadowColor + UINT16 borderFillId +
     * COLORREF strikeColor
     */
    private static byte[] buildCharShape(CharShape cs) {
        HwpBinaryWriter w = new HwpBinaryWriter(80);

        // WORD[7] fontIds
        for (int i = 0; i < 7; i++) w.writeUInt16(cs.fontId[i]);
        // UINT8[7] ratios
        for (int i = 0; i < 7; i++) w.writeUInt8(cs.ratio[i]);
        // INT8[7] spacings
        for (int i = 0; i < 7; i++) w.writeInt8(cs.spacing[i]);
        // UINT8[7] relSizes
        for (int i = 0; i < 7; i++) w.writeUInt8(cs.relSize[i]);
        // INT8[7] charOffsets
        for (int i = 0; i < 7; i++) w.writeInt8(cs.charOffset[i]);

        // INT32 baseSize
        w.writeInt32(cs.baseSize);
        // UINT32 property
        w.writeUInt32(cs.property);
        // INT8 : shadowOffsetX / shadowOffsetY
        w.writeInt8(cs.shadowOffsetX);
        w.writeInt8(cs.shadowOffsetY);
        // COLORREF : textColor / underlineColor / shadeColor / shadowColor
        w.writeColorRef(cs.textColor);
        w.writeColorRef(cs.underlineColor);
        w.writeColorRef(cs.shadeColor);
        w.writeColorRef(cs.shadowColor);
        // UINT16 : borderFillId
        w.writeUInt16(cs.borderFillId);
        // COLORREF : strikeColor
        w.writeColorRef(cs.strikeColor);

        return RecordWriter.buildRecord(HwpTagId.CHAR_SHAPE, 1, w.toByteArray());
    }

    /**
     * TAB_DEF 레코드.
     * 참조 파일은 다음을 사용: property(4) + count(4) + items.
     * Count는 INT16이 아닌 INT32 (4 byte)이다.
     * 각 탭 항목: position(4) + type(1) + fillType(1) + padding(2) = 8 byte.
     */
    private static byte[] buildTabDef(TabDef td) {
        HwpBinaryWriter w = new HwpBinaryWriter(64);

        w.writeUInt32(td.property);
        w.writeInt32(td.items.size()); // 4 byte count
        for (TabDef.TabItem item : td.items) {
            w.writeHwpUnit(item.position);
            w.writeUInt8(item.type);
            w.writeUInt8(item.fillType);
            w.writeUInt16(item.padding);
        }

        return RecordWriter.buildRecord(HwpTagId.TAB_DEF, 1, w.toByteArray());
    }

    /**
     * NUMBERING 레코드.
     * ParaHead는 12 byte: property(4) + widthAdjust(2) + textOffset(2) + charShapeId(4).
     * 7개의 기본 레벨: 각각 = paraHead(12) + WORD fmtLen + WCHAR[] fmtStr
     * 이어서: UINT16 startNumber
     * 이어서: 7개의 UINT32 levelStartNumbers
     * 이어서: 3개의 확장 레벨 (8-10): paraHead(12) + WORD fmtLen + WCHAR[] fmtStr
     * 이어서: 3개의 UINT32 extLevelStartNumbers
     */
    private static byte[] buildNumbering(Numbering nb) {
        HwpBinaryWriter w = new HwpBinaryWriter(512);

        // 7개의 기본 레벨 기록: paraHead(12 byte) + 포맷 문자열
        for (int level = 0; level < 7; level++) {
            if (level < nb.paraHeads.size()) {
                Numbering.ParaHead ph = nb.paraHeads.get(level);
                w.writeUInt32(ph.property);       // 4 byte
                w.writeHwpUnit16(ph.widthAdjust); // 2 byte
                w.writeHwpUnit16(ph.textOffset);  // 2 byte
                w.writeUInt32(ph.charShapeId);    // 4 byte
                String fmt = (ph.formatString != null) ? ph.formatString : "";
                w.writeUInt16(fmt.length());
                w.writeUtf16Le(fmt);
            } else {
                // 빈 레벨: 12 byte paraHead + 길이 0의 포맷 문자열
                w.writeUInt32(0);
                w.writeHwpUnit16(0);
                w.writeHwpUnit16(0);
                w.writeUInt32(0);
                w.writeUInt16(0);
            }
        }

        w.writeUInt16(nb.startNumber);

        // 7개의 기본 레벨 시작 번호
        for (int i = 0; i < 7; i++) {
            w.writeUInt32(nb.levelStartNumbers[i]);
        }

        // 확장 레벨 8-10 (HWP 5.1+): paraHead(12) + 포맷 문자열
        for (int level = 0; level < 3; level++) {
            if (level < nb.extParaHeads.size()) {
                Numbering.ParaHead ph = nb.extParaHeads.get(level);
                w.writeUInt32(ph.property);
                w.writeHwpUnit16(ph.widthAdjust);
                w.writeHwpUnit16(ph.textOffset);
                w.writeUInt32(ph.charShapeId);
                String fmt = (ph.formatString != null) ? ph.formatString : "";
                w.writeUInt16(fmt.length());
                w.writeUtf16Le(fmt);
            } else {
                w.writeUInt32(0);
                w.writeHwpUnit16(0);
                w.writeHwpUnit16(0);
                w.writeUInt32(0);
                w.writeUInt16(0);
            }
        }

        // 3개의 확장 레벨 시작 번호
        for (int i = 0; i < 3; i++) {
            w.writeUInt32(nb.extLevelStartNumbers[i]);
        }

        return RecordWriter.buildRecord(HwpTagId.NUMBERING, 1, w.toByteArray());
    }

    /**
     * BULLET 레코드.
     * ParaHead(12) + WCHAR bulletChar(2) + INT32 imageBullet(4) +
     * BYTE[4] imageBulletInfo(4) + WCHAR checkBulletChar(2) + BYTE padding(1) = 25 byte.
     */
    private static byte[] buildBullet(Bullet bl) {
        HwpBinaryWriter w = new HwpBinaryWriter(64);

        // ParaHead 정보 (12 byte)
        Numbering.ParaHead ph = bl.paraHead;
        if (ph != null) {
            w.writeUInt32(ph.property);       // 4 byte
            w.writeHwpUnit16(ph.widthAdjust); // 2 byte
            w.writeHwpUnit16(ph.textOffset);  // 2 byte
            w.writeUInt32(ph.charShapeId);    // 4 byte
        } else {
            w.writePad(12);
        }

        // WCHAR : bulletChar
        w.writeWChar(bl.bulletChar);
        // INT32 : imageBullet
        w.writeInt32(bl.imageBullet);
        // BYTE[4] : imageBulletInfo
        if (bl.imageBulletInfo != null && bl.imageBulletInfo.length == 4) {
            w.writeBytes(bl.imageBulletInfo);
        } else {
            w.writePad(4);
        }
        // WCHAR : checkBulletChar
        w.writeWChar(bl.checkBulletChar);
        // 정렬용 1 byte 패딩 (총 25 byte)
        w.writeUInt8(0);

        return RecordWriter.buildRecord(HwpTagId.BULLET, 1, w.toByteArray());
    }

    /**
     * PARA_SHAPE: 정확히 58 byte.
     *
     * UINT32 prop1(4) + INT32 leftMargin(4) + INT32 rightMargin(4) +
     * INT32 indent(4) + INT32 spaceBefore(4) + INT32 spaceAfter(4) +
     * INT32 lineSpacing_old(4) + UINT16 tabDefId(2) + UINT16 numberingId(2) +
     * UINT16 borderFillId(2) + INT16 borderPadL(2) + INT16 borderPadR(2) +
     * INT16 borderPadT(2) + INT16 borderPadB(2) + UINT32 prop2(4) +
     * UINT32 prop3(4) + UINT32 lineSpacing2(4) + UINT32 unknown_pad(4)
     * = 58 byte
     */
    private static byte[] buildParaShape(ParaShape ps) {
        HwpBinaryWriter w = new HwpBinaryWriter(64);

        w.writeUInt32(ps.property1);         // 4
        w.writeInt32(ps.leftMargin);         // 4
        w.writeInt32(ps.rightMargin);        // 4
        w.writeInt32(ps.indent);             // 4
        w.writeInt32(ps.spaceBefore);        // 4
        w.writeInt32(ps.spaceAfter);         // 4
        w.writeInt32(ps.lineSpacing);        // 4 (lineSpacing_old, 5.0.2.5 이전)
        w.writeUInt16(ps.tabDefId);          // 2
        w.writeUInt16(ps.numberingId);       // 2
        w.writeUInt16(ps.borderFillId);      // 2 (1부터 시작!)
        w.writeInt16(ps.borderPadLeft);      // 2
        w.writeInt16(ps.borderPadRight);     // 2
        w.writeInt16(ps.borderPadTop);       // 2
        w.writeInt16(ps.borderPadBottom);    // 2
        w.writeUInt32(ps.property2);         // 4 (5.0.1.7+)
        w.writeUInt32(ps.property3);         // 4 (5.0.2.5+)
        w.writeUInt32(ps.lineSpacing2);      // 4 (5.0.2.5+)
        w.writePad(4);                       // 4 (unknown_pad, 항상 0)
        // 총: 58 byte

        return RecordWriter.buildRecord(HwpTagId.PARA_SHAPE, 1, w.toByteArray());
    }

    /**
     * STYLE 레코드: 일반적인 레코드의 경우 정확히 32 byte.
     *
     * WORD nameLen(2) + WCHAR[] name + WORD engNameLen(2) + WCHAR[] engName +
     * BYTE type(1) + BYTE nextStyleId(1) + INT16 langId(2) +
     * UINT16 paraShapeId(2) + UINT16 charShapeId(2) + UINT16 lockForm(2)
     *
     * 참고: paraShapeId는 참조 파일에서 1부터 시작한다.
     */
    private static byte[] buildStyle(Style st) {
        HwpBinaryWriter w = new HwpBinaryWriter(128);

        // WORD nameLen + WCHAR[] name
        String localName = (st.localName != null) ? st.localName : "";
        w.writeUInt16(localName.length());
        w.writeUtf16Le(localName);

        // WORD engNameLen + WCHAR[] engName
        String engName = (st.englishName != null) ? st.englishName : "";
        w.writeUInt16(engName.length());
        w.writeUtf16Le(engName);

        w.writeUInt8(st.type);               // 1 byte: 0=문단, 1=글자
        w.writeUInt8(st.nextStyleId);        // 1 byte
        w.writeInt16(st.langId);             // 2 byte
        w.writeUInt16(st.paraShapeId);       // 2 byte (참조에서 1부터 시작!)
        w.writeUInt16(st.charShapeId);       // 2 byte
        w.writeUInt16(st.lockForm);          // 2 byte (항상 0)

        return RecordWriter.buildRecord(HwpTagId.STYLE, 1, w.toByteArray());
    }

    /** COMPATIBLE_DOCUMENT: 4 byte, HWPX 소스에 따라 target=0/1/2. */
    private static byte[] buildCompatibleDocument(int targetProgram) {
        HwpBinaryWriter w = new HwpBinaryWriter(4);
        w.writeUInt32(targetProgram);
        return RecordWriter.buildRecord(HwpTagId.COMPATIBLE_DOCUMENT, 0, w.toByteArray());
    }

    /** LAYOUT_COMPATIBILITY: 20 byte (5개의 UINT32 레벨 마스크). */
    private static byte[] buildLayoutCompatibility(long[] levels) {
        HwpBinaryWriter w = new HwpBinaryWriter(20);
        for (int i = 0; i < 5; i++) {
            w.writeUInt32(levels != null && i < levels.length ? levels[i] : 0L);
        }
        return RecordWriter.buildRecord(HwpTagId.LAYOUT_COMPATIBILITY, 1, w.toByteArray());
    }

    /**
     * DOC_DATA 레코드 (태그 0x01B, 레벨 0).
     * 문서의 임의 데이터를 ParameterSet으로 담는다.
     *
     * <p>v14.5: 원본 case1.hwp (한/글 작성) 의 DOC_DATA 80 byte 와 byte-for-byte
     * 일치하도록 수정. 이전 코드는 다음 4가지 차이가 있어 한/글 호환을 깨뜨렸다.
     * <ul>
     *   <li>ParameterSet 의 itemCount 가 INT16(2byte) 로 작성됨 → 원본은 INT32(4byte)</li>
     *   <li>외부 itemId 0x0007 → 원본은 0x0207</li>
     *   <li>중첩 setId 0x0680 → 원본은 0x0207</li>
     *   <li>itemId 패턴 / type(0x0006/0x0007 vs 0x0004) / value 가 원본과 모두 다름</li>
     * </ul>
     * 외부 6 + item-header 4 + 중첩 6 + 8×8 = 80 byte 로 정확히 맞춤.
     */
    private static byte[] buildDocData() {
        HwpBinaryWriter w = new HwpBinaryWriter(128);
        // 외부 ParameterSet: setId(UINT16) + itemCount(INT32) = 6 byte
        w.writeUInt16(0x021C);  // setId = 540 (인쇄 정보 세트)
        w.writeInt32(1);        // INT32 itemCount

        // 항목 0: PIT_SET — itemId(2) + type(2) = 4 byte
        w.writeUInt16(0x0207);  // itemId 0x0207 (원본 일치)
        w.writeUInt16(0x8000);  // type = PIT_SET

        // 중첩 ParameterSet: setId(UINT16) + itemCount(INT32) = 6 byte
        w.writeUInt16(0x0207);  // nested setId 0x0207
        w.writeInt32(8);        // INT32 nested itemCount

        // 8개 nested items: itemId(2) + type(2) + value(INT32, 4byte) = 8 byte each, 64 byte total
        int[] itemIds = {0x4006, 0x400E, 0x400A, 0x401F, 0x401D, 0x401A, 0x4010, 0x4020};
        int[] types   = {0x0006, 0x0006, 0x0006, 0x0007, 0x0006, 0x0006, 0x0007, 0x0007};
        int[] values  = {     4,      0,      0,    100,      0,      0,      0,    100};
        for (int i = 0; i < 8; i++) {
            w.writeUInt16(itemIds[i]);
            w.writeUInt16(types[i]);
            w.writeInt32(values[i]);
        }

        return RecordWriter.buildRecord(HwpTagId.DOC_DATA, 0, w.toByteArray());
    }

    /**
     * FORBIDDEN_CHAR 레코드 (태그 0x05E, 레벨 1).
     * 금칙 문자열 쌍(줄바꿈 금칙 문자)을 담는다.
     * 최소: 0으로 채워진 16 byte.
     */
    private static byte[] buildForbiddenChar() {
        HwpBinaryWriter w = new HwpBinaryWriter(16);
        w.writePad(16);
        return RecordWriter.buildRecord(HwpTagId.FORBIDDEN_CHAR, 1, w.toByteArray());
    }
}
