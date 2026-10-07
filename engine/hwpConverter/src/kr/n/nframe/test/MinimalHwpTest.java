package kr.n.nframe.test;

import kr.n.nframe.hwplib.binary.HwpBinaryWriter;
import kr.n.nframe.hwplib.binary.RecordWriter;
import kr.n.nframe.hwplib.binary.ZlibCompressor;
import kr.n.nframe.hwplib.constants.CtrlId;
import kr.n.nframe.hwplib.constants.HwpTagId;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 한글에서 열 수 있는지 테스트하기 위한 최소한의 유효한 HWP 파일을 생성한다.
 */
public class MinimalHwpTest {

    public static void main(String[] args) throws Exception {
        String outPath = args.length > 0 ? args[0] : "output/minimal_test.hwp";

        // ---- FileHeader (256 byte) ----
        byte[] fileHeader = new byte[256];
        byte[] sig = "HWP Document File".getBytes("US-ASCII");
        System.arraycopy(sig, 0, fileHeader, 0, sig.length);
        // offset 32에 Version 5.1.1.0
        fileHeader[32] = 0x00; fileHeader[33] = 0x01; fileHeader[34] = 0x01; fileHeader[35] = 0x05;
        // Properties: compressed=1
        fileHeader[36] = 0x01;
        // offset 44에 EncryptVersion=4
        fileHeader[44] = 0x04;

        // ---- DocInfo (최소 Record) ----
        ByteArrayOutputStream docInfoRaw = new ByteArrayOutputStream();

        // 1. DOCUMENT_PROPERTIES (26 byte)
        HwpBinaryWriter dp = new HwpBinaryWriter();
        dp.writeUInt16(1);  // sectionCount
        dp.writeUInt16(1);  // pageStartNum
        dp.writeUInt16(1);  // footnoteStartNum
        dp.writeUInt16(1);  // endnoteStartNum
        dp.writeUInt16(1);  // pictureStartNum
        dp.writeUInt16(1);  // tableStartNum
        dp.writeUInt16(1);  // equationStartNum
        dp.writeUInt32(0);  // caretListId
        dp.writeUInt32(0);  // caretParaId
        dp.writeUInt32(0);  // caretPos
        docInfoRaw.write(RecordWriter.buildRecord(HwpTagId.DOCUMENT_PROPERTIES, 0, dp.toByteArray()));

        // 2. ID_MAPPINGS (72 byte) - 언어당 폰트 1개, charshape 1개, parashape 1개, style 1개, borderfill 1개
        HwpBinaryWriter im = new HwpBinaryWriter();
        im.writeInt32(0);   // binData
        im.writeInt32(1);   // 한글 font
        im.writeInt32(1);   // latin font
        im.writeInt32(1);   // 한자 font
        im.writeInt32(1);   // 일본어 font
        im.writeInt32(1);   // 기타 font
        im.writeInt32(1);   // symbol font
        im.writeInt32(1);   // user font
        im.writeInt32(1);   // borderFill
        im.writeInt32(1);   // charShape
        im.writeInt32(1);   // tabDef
        im.writeInt32(0);   // numbering
        im.writeInt32(0);   // bullet
        im.writeInt32(1);   // paraShape
        im.writeInt32(1);   // style
        im.writeInt32(0);   // memoShape
        im.writeInt32(0);   // trackChangeAuthor
        im.writeInt32(0);   // trackChange
        docInfoRaw.write(RecordWriter.buildRecord(HwpTagId.ID_MAPPINGS, 0, im.toByteArray()));

        // 3. FACE_NAME x7 (언어별로 "함초롬돋움" 하나씩)
        for (int lang = 0; lang < 7; lang++) {
            HwpBinaryWriter fn = new HwpBinaryWriter();
            fn.writeUInt8(0x40); // property: hasTypeInfo만
            String fontName = "함초롬돋움";
            fn.writeUInt16(fontName.length());
            fn.writeUtf16Le(fontName);
            // TypeInfo (10 byte)
            fn.writeUInt8(1); // familyType
            fn.writeUInt8(1); // serifStyle
            fn.writeUInt8(5); // weight
            fn.writeUInt8(0); fn.writeUInt8(0); fn.writeUInt8(0);
            fn.writeUInt8(0); fn.writeUInt8(0); fn.writeUInt8(0); fn.writeUInt8(0);
            docInfoRaw.write(RecordWriter.buildRecord(HwpTagId.FACE_NAME, 1, fn.toByteArray()));
        }

        // 4. BORDER_FILL (최소 - 테두리 없음, 채우기 없음)
        HwpBinaryWriter bf = new HwpBinaryWriter();
        bf.writeUInt16(0); // property
        for (int i = 0; i < 4; i++) bf.writeUInt8(0);  // 테두리 타입
        for (int i = 0; i < 4; i++) bf.writeUInt8(0);  // 테두리 두께
        for (int i = 0; i < 4; i++) bf.writeColorRef(0); // 테두리 색상
        bf.writeUInt8(0); bf.writeUInt8(0); bf.writeColorRef(0); // 대각선
        bf.writeUInt32(0); // fillType = none
        bf.writeColorRef(0xFFFFFFFFL); bf.writeColorRef(0); bf.writeInt32(-1); // 단색 채우기 기본값
        bf.writeUInt32(0); // 추가 크기
        docInfoRaw.write(RecordWriter.buildRecord(HwpTagId.BORDER_FILL, 1, bf.toByteArray()));

        // 5. CHAR_SHAPE (최소)
        HwpBinaryWriter cs = new HwpBinaryWriter();
        for (int i = 0; i < 7; i++) cs.writeUInt16(0); // 언어별 fontId
        for (int i = 0; i < 7; i++) cs.writeUInt8(100); // ratio
        for (int i = 0; i < 7; i++) cs.writeInt8(0);    // spacing
        for (int i = 0; i < 7; i++) cs.writeUInt8(100); // relSize
        for (int i = 0; i < 7; i++) cs.writeInt8(0);    // charOffset
        cs.writeInt32(1000);   // baseSize (10pt)
        cs.writeUInt32(0);     // property
        cs.writeInt8(0);       // shadowX
        cs.writeInt8(0);       // shadowY
        cs.writeColorRef(0);   // textColor
        cs.writeColorRef(0);   // underlineColor
        cs.writeColorRef(0xFFFFFFFFL); // shadeColor
        cs.writeColorRef(0);   // shadowColor
        cs.writeUInt16(0);     // borderFillId
        cs.writeColorRef(0);   // strikeColor
        docInfoRaw.write(RecordWriter.buildRecord(HwpTagId.CHAR_SHAPE, 1, cs.toByteArray()));

        // 6. TAB_DEF (최소)
        HwpBinaryWriter td = new HwpBinaryWriter();
        td.writeUInt32(0); // property
        td.writeInt16(0);  // count
        docInfoRaw.write(RecordWriter.buildRecord(HwpTagId.TAB_DEF, 1, td.toByteArray()));

        // 7. PARA_SHAPE (최소)
        HwpBinaryWriter ps = new HwpBinaryWriter();
        ps.writeUInt32(0x00040220L); // property1: justify, snapToGrid, percent lineSpacing
        ps.writeInt32(0); ps.writeInt32(0); ps.writeInt32(0); // margin
        ps.writeInt32(0); ps.writeInt32(0); // 문단 위/아래 간격
        ps.writeInt32(160); // lineSpacing (old)
        ps.writeUInt16(0);  // tabDefId
        ps.writeUInt16(0);  // numberingId
        ps.writeUInt16(1);  // borderFillId (1부터 시작)
        ps.writeInt16(0); ps.writeInt16(0); ps.writeInt16(0); ps.writeInt16(0); // border pad
        ps.writeUInt32(0);  // property2
        ps.writeUInt32(0);  // property3
        ps.writeUInt32(160); // lineSpacing2
        docInfoRaw.write(RecordWriter.buildRecord(HwpTagId.PARA_SHAPE, 1, ps.toByteArray()));

        // 8. STYLE (최소 - "바탕글")
        HwpBinaryWriter st = new HwpBinaryWriter();
        String name = "바탕글";
        st.writeUInt16(name.length());
        st.writeUtf16Le(name);
        String ename = "Normal";
        st.writeUInt16(ename.length());
        st.writeUtf16Le(ename);
        st.writeUInt8(0);  // type = 문단
        st.writeUInt8(0);  // nextStyleId
        st.writeInt16(1042); // langId (한국어)
        st.writeUInt16(0);  // paraShapeId
        st.writeUInt16(0);  // charShapeId
        docInfoRaw.write(RecordWriter.buildRecord(HwpTagId.STYLE, 1, st.toByteArray()));

        // 9. COMPATIBLE_DOCUMENT
        HwpBinaryWriter cd = new HwpBinaryWriter();
        cd.writeUInt32(0);
        docInfoRaw.write(RecordWriter.buildRecord(HwpTagId.COMPATIBLE_DOCUMENT, 0, cd.toByteArray()));

        // 10. LAYOUT_COMPATIBILITY
        HwpBinaryWriter lc = new HwpBinaryWriter();
        for (int i = 0; i < 5; i++) lc.writeUInt32(0);
        docInfoRaw.write(RecordWriter.buildRecord(HwpTagId.LAYOUT_COMPATIBILITY, 1, lc.toByteArray()));

        byte[] compressedDocInfo = ZlibCompressor.compress(docInfoRaw.toByteArray());

        // ---- Section0 (최소 - section def가 포함된 문단 1개) ----
        ByteArrayOutputStream sec0Raw = new ByteArrayOutputStream();

        // 문단 1: section def + column def + 텍스트 "테스트"
        // PARA_HEADER
        HwpBinaryWriter ph = new HwpBinaryWriter();
        int nChars = 8 + 8 + 4 + 1; // secd(8) + cold(8) + "테스트"(3) + linebreak(1) + paraEnd implicit...
        // 실제로는: secd=8 WCHAR, cold=8 WCHAR, "테스트"=3 WCHAR, paraEnd=1 WCHAR = 20
        nChars = 8 + 8 + 3 + 1; // = 20
        ph.writeUInt32(nChars);
        ph.writeUInt32((1L << 2) | (1L << 13)); // controlMask: bit2=secd(code2), bit13=paraEnd(code13)
        ph.writeUInt16(0);  // paraShapeId
        ph.writeUInt8(0);   // styleId
        ph.writeUInt8(0);   // colBreak
        ph.writeUInt16(1);  // charShapeCount
        ph.writeUInt16(0);  // rangeTagCount
        ph.writeUInt16(1);  // lineSegCount
        ph.writeUInt32(1);  // instanceId
        ph.writeUInt16(0);  // mergeFlag
        sec0Raw.write(RecordWriter.buildRecord(HwpTagId.PARA_HEADER, 0, ph.toByteArray()));

        // PARA_TEXT
        HwpBinaryWriter pt = new HwpBinaryWriter();
        // secd 컨트롤 (8 WCHAR = 16 byte)
        pt.writeUInt16(0x0002); // ctrl code
        pt.writeUInt32(CtrlId.SECTION_DEF); // ctrlId (LE)
        pt.writePad(8); // padding
        pt.writeUInt16(0x0002); // ctrl code 복사
        // cold 컨트롤 (8 WCHAR = 16 byte)
        pt.writeUInt16(0x0002); // ctrl code
        pt.writeUInt32(CtrlId.COLUMN_DEF);
        pt.writePad(8);
        pt.writeUInt16(0x0002); // ctrl code 복사
        // 텍스트 "테스트"
        pt.writeUtf16Le("테스트");
        // 문단 끝
        pt.writeUInt16(0x000D);
        sec0Raw.write(RecordWriter.buildRecord(HwpTagId.PARA_TEXT, 1, pt.toByteArray()));

        // PARA_CHAR_SHAPE
        HwpBinaryWriter pcs = new HwpBinaryWriter();
        pcs.writeUInt32(0); // position
        pcs.writeUInt32(0); // charShapeId
        sec0Raw.write(RecordWriter.buildRecord(HwpTagId.PARA_CHAR_SHAPE, 1, pcs.toByteArray()));

        // PARA_LINE_SEG
        HwpBinaryWriter pls = new HwpBinaryWriter();
        pls.writeUInt32(0);    // textStartPos
        pls.writeInt32(0);     // lineVertPos
        pls.writeInt32(1500);  // lineHeight
        pls.writeInt32(1500);  // textHeight
        pls.writeInt32(1200);  // baselineDistance
        pls.writeInt32(1800);  // lineSpacing
        pls.writeInt32(0);     // columnStartPos
        pls.writeInt32(42520); // segWidth
        pls.writeUInt32(0x00060000L); // tag
        sec0Raw.write(RecordWriter.buildRecord(HwpTagId.PARA_LINE_SEG, 1, pls.toByteArray()));

        // CTRL_HEADER: section 정의
        HwpBinaryWriter sh = new HwpBinaryWriter();
        sh.writeUInt32(CtrlId.SECTION_DEF);
        sh.writeUInt32(0); // property
        sh.writeHwpUnit16(1134); // colGap
        sh.writeHwpUnit16(0);    // vertGrid
        sh.writeHwpUnit16(0);    // horizGrid
        sh.writeHwpUnit(8000);   // defaultTab
        sh.writeUInt16(0);       // numberingParaShapeId
        sh.writeUInt16(0);       // pageNum
        sh.writeUInt16(0); sh.writeUInt16(0); sh.writeUInt16(0); // fig/tbl/eqn
        sh.writeUInt16(0);       // defaultLang
        sh.writePad(17);         // 버전 padding
        sec0Raw.write(RecordWriter.buildRecord(HwpTagId.CTRL_HEADER, 1, sh.toByteArray()));

        // PAGE_DEF
        HwpBinaryWriter pd = new HwpBinaryWriter();
        pd.writeHwpUnit(59528); pd.writeHwpUnit(84188); // A4 크기
        pd.writeHwpUnit(8504); pd.writeHwpUnit(8504);   // 좌/우 margin
        pd.writeHwpUnit(5668); pd.writeHwpUnit(4252);   // 상/하 margin
        pd.writeHwpUnit(4252); pd.writeHwpUnit(4252);   // 머리말/꼬리말 margin
        pd.writeHwpUnit(0);    // gutter
        pd.writeUInt32(0);     // property (세로 방향)
        sec0Raw.write(RecordWriter.buildRecord(HwpTagId.PAGE_DEF, 2, pd.toByteArray()));

        // FOOTNOTE_SHAPE (각주용)
        HwpBinaryWriter fn1 = new HwpBinaryWriter();
        fn1.writeUInt32(0); fn1.writeWChar('\0'); fn1.writeWChar('\0');
        fn1.writeWChar(')'); fn1.writeUInt16(1);
        fn1.writeHwpUnit16(-1); fn1.writeHwpUnit16(850); fn1.writeHwpUnit16(567);
        fn1.writeHwpUnit16(283); fn1.writeUInt8(0); fn1.writeUInt8(1);
        fn1.writeColorRef(0); fn1.writeUInt16(0x0101);
        sec0Raw.write(RecordWriter.buildRecord(HwpTagId.FOOTNOTE_SHAPE, 2, fn1.toByteArray()));

        // FOOTNOTE_SHAPE (미주용)
        HwpBinaryWriter fn2 = new HwpBinaryWriter();
        fn2.writeUInt32(0); fn2.writeWChar('\0'); fn2.writeWChar('\0');
        fn2.writeWChar(')'); fn2.writeUInt16(1);
        fn2.writeHwpUnit16(0); fn2.writeHwpUnit16(850); fn2.writeHwpUnit16(567);
        fn2.writeHwpUnit16(0); fn2.writeUInt8(0); fn2.writeUInt8(1);
        fn2.writeColorRef(0); fn2.writeUInt16(0x0101);
        sec0Raw.write(RecordWriter.buildRecord(HwpTagId.FOOTNOTE_SHAPE, 2, fn2.toByteArray()));

        // PAGE_BORDER_FILL x3
        for (int type = 0; type < 3; type++) {
            HwpBinaryWriter pbf = new HwpBinaryWriter();
            pbf.writeUInt32(1); // property (용지 기준)
            pbf.writeHwpUnit16(1417); pbf.writeHwpUnit16(1417);
            pbf.writeHwpUnit16(1417); pbf.writeHwpUnit16(1417);
            pbf.writeUInt16(1); // borderFillId
            sec0Raw.write(RecordWriter.buildRecord(HwpTagId.PAGE_BORDER_FILL, 2, pbf.toByteArray()));
        }

        // CTRL_HEADER: 단 정의
        HwpBinaryWriter ch = new HwpBinaryWriter();
        ch.writeUInt32(CtrlId.COLUMN_DEF);
        ch.writeUInt16(0x0410); // propertyLow: type=신문식, count=1
        ch.writeHwpUnit16(0);   // colGap
        ch.writeUInt16(0);      // propertyHigh
        ch.writeUInt8(0);       // dividerType
        ch.writeUInt8(0);       // dividerWidth
        ch.writeColorRef(0);    // dividerColor
        sec0Raw.write(RecordWriter.buildRecord(HwpTagId.CTRL_HEADER, 1, ch.toByteArray()));

        byte[] compressedSec0 = ZlibCompressor.compress(sec0Raw.toByteArray());

        // ---- OLE2 조립 ----
        POIFSFileSystem poifs = new POIFSFileSystem();
        DirectoryEntry root = poifs.getRoot();

        root.createDocument("FileHeader", new ByteArrayInputStream(fileHeader));
        root.createDocument("DocInfo", new ByteArrayInputStream(compressedDocInfo));

        DirectoryEntry bodyText = root.createDirectory("BodyText");
        bodyText.createDocument("Section0", new ByteArrayInputStream(compressedSec0));

        // \005HwpSummaryInformation 사용 (\005 prefix는 OLE property set용)
        root.createDocument("\005HwpSummaryInformation",
            new ByteArrayInputStream(buildSummaryInfo()));

        root.createDocument("PrvText", new ByteArrayInputStream(new byte[0]));
        root.createDocument("PrvImage", new ByteArrayInputStream(new byte[0]));

        DirectoryEntry scripts = root.createDirectory("Scripts");
        scripts.createDocument("JScriptVersion",
            new ByteArrayInputStream("JScript1.0".getBytes("UTF-16LE")));
        scripts.createDocument("DefaultJScript", new ByteArrayInputStream(new byte[0]));

        try (OutputStream fos = new FileOutputStream(outPath)) {
            poifs.writeFilesystem(fos);
        }
        poifs.close();

        System.out.println("Created minimal HWP: " + outPath);
    }

    static byte[] buildSummaryInfo() {
        // 유효한 HWP 파일의 정확한 포맷을 복사
        // HwpSummaryInformation의 FMTID: {9FA2B660-1061-11D4-B4C6-006097C09D8C}
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(256);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);

        // Property set 스트림 헤더
        buf.putShort((short) 0xFFFE);  // byte order
        buf.putShort((short) 0x0000);  // format
        buf.putInt(0x00000006);         // OS version (Win32)
        buf.put(new byte[16]);          // CLSID (0으로 채움)
        buf.putInt(1);                  // property set 개수

        // FMTID {9FA2B660-1061-11D4-B4C6-006097C09D8C}
        buf.putInt(0x60B6A29F);
        buf.putShort((short) 0x6110);
        buf.putShort((short) 0xD411);
        buf.put(new byte[]{(byte)0xB4, (byte)0xC6, (byte)0x00, (byte)0x60, (byte)0x97, (byte)0xC0, (byte)0x9D, (byte)0x8C});
        buf.putInt(48);  // offset

        // Property set
        int psStart = buf.position();
        buf.putInt(0); // size 자리 예약
        buf.putInt(0); // 속성 개수 = 0

        int psSize = buf.position() - psStart;
        buf.putInt(psStart, psSize);

        byte[] result = new byte[buf.position()];
        buf.flip();
        buf.get(result);
        return result;
    }
}
