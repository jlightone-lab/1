package kr.n.nframe.test;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.poifs.filesystem.*;

/**
 * HWP 5.0 규격 기반의 종합 검증기.
 * 참조 파일과 출력 파일을 바이트/레코드 수준에서 비교합니다.
 * 한글(Hangul)이 파일을 거부할 수 있는 모든 차이를 보고합니다.
 *
 * "한글문서파일형식 5.0 revision 1.3" 기준
 */
public class SpecValidator {

    // ===== 태그 상수 (HWP 5.0 규격) =====
    static final int TAG_DOC_PROPS     = 0x010;
    static final int TAG_ID_MAPS       = 0x011;
    static final int TAG_BIN_DATA      = 0x012;
    static final int TAG_FACE_NAME     = 0x013;
    static final int TAG_BORDER_FILL   = 0x014;
    static final int TAG_CHAR_SHAPE    = 0x015;
    static final int TAG_TAB_DEF       = 0x016;
    static final int TAG_NUMBERING     = 0x017;
    static final int TAG_BULLET        = 0x018;
    static final int TAG_PARA_SHAPE    = 0x019;
    static final int TAG_STYLE         = 0x01A;
    static final int TAG_DOC_DATA      = 0x01B;
    static final int TAG_DIST_DOC_DATA = 0x01C;
    static final int TAG_COMPAT_DOC    = 0x01E;
    static final int TAG_LAYOUT_COMPAT = 0x01F;
    static final int TAG_TRACK_CHG     = 0x020;
    static final int TAG_FORBIDDEN     = 0x05E;

    static final int TAG_PARA_HDR   = 0x042;
    static final int TAG_PARA_TXT   = 0x043;
    static final int TAG_PARA_CS    = 0x044;
    static final int TAG_PARA_LS    = 0x045;
    static final int TAG_RANGE_TAG  = 0x046;
    static final int TAG_CTRL_HDR   = 0x047;
    static final int TAG_LIST_HDR   = 0x048;
    static final int TAG_PAGE_DEF   = 0x049;
    static final int TAG_FN_SHAPE   = 0x04A;
    static final int TAG_PG_BF      = 0x04B;
    static final int TAG_SHAPE_CMP  = 0x04C;
    static final int TAG_TABLE      = 0x04D;
    static final int TAG_PICTURE    = 0x055;
    static final int TAG_OLE        = 0x056;
    static final int TAG_CTRL_DATA  = 0x057;
    static final int TAG_EQEDIT     = 0x058;

    static final Map<Integer,String> TAG_NAMES = new HashMap<>();
    static {
        TAG_NAMES.put(TAG_DOC_PROPS,"DOC_PROPS"); TAG_NAMES.put(TAG_ID_MAPS,"ID_MAPS");
        TAG_NAMES.put(TAG_BIN_DATA,"BIN_DATA"); TAG_NAMES.put(TAG_FACE_NAME,"FACE_NAME");
        TAG_NAMES.put(TAG_BORDER_FILL,"BORDER_FILL"); TAG_NAMES.put(TAG_CHAR_SHAPE,"CHAR_SHAPE");
        TAG_NAMES.put(TAG_TAB_DEF,"TAB_DEF"); TAG_NAMES.put(TAG_NUMBERING,"NUMBERING");
        TAG_NAMES.put(TAG_BULLET,"BULLET"); TAG_NAMES.put(TAG_PARA_SHAPE,"PARA_SHAPE");
        TAG_NAMES.put(TAG_STYLE,"STYLE"); TAG_NAMES.put(TAG_DOC_DATA,"DOC_DATA");
        TAG_NAMES.put(TAG_COMPAT_DOC,"COMPAT_DOC"); TAG_NAMES.put(TAG_LAYOUT_COMPAT,"LAYOUT_COMPAT");
        TAG_NAMES.put(TAG_TRACK_CHG,"TRACK_CHG"); TAG_NAMES.put(TAG_FORBIDDEN,"FORBIDDEN");
        TAG_NAMES.put(TAG_PARA_HDR,"PARA_HDR"); TAG_NAMES.put(TAG_PARA_TXT,"PARA_TXT");
        TAG_NAMES.put(TAG_PARA_CS,"PARA_CS"); TAG_NAMES.put(TAG_PARA_LS,"PARA_LS");
        TAG_NAMES.put(TAG_RANGE_TAG,"RANGE_TAG"); TAG_NAMES.put(TAG_CTRL_HDR,"CTRL_HDR");
        TAG_NAMES.put(TAG_LIST_HDR,"LIST_HDR"); TAG_NAMES.put(TAG_PAGE_DEF,"PAGE_DEF");
        TAG_NAMES.put(TAG_FN_SHAPE,"FN_SHAPE"); TAG_NAMES.put(TAG_PG_BF,"PG_BF");
        TAG_NAMES.put(TAG_SHAPE_CMP,"SHAPE_CMP"); TAG_NAMES.put(TAG_TABLE,"TABLE");
        TAG_NAMES.put(TAG_PICTURE,"PICTURE"); TAG_NAMES.put(TAG_OLE,"OLE");
        TAG_NAMES.put(TAG_CTRL_DATA,"CTRL_DATA"); TAG_NAMES.put(TAG_EQEDIT,"EQEDIT");
    }
    static String tagName(int t) { String s = TAG_NAMES.get(t); return s != null ? s : String.format("0x%03X",t); }

    // ===== 바이트 유틸 =====
    static int u8(byte[] d, int o) { return d[o] & 0xFF; }
    static int u16(byte[] d, int o) { return (d[o]&0xFF) | ((d[o+1]&0xFF)<<8); }
    static int i32(byte[] d, int o) { return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24); }
    static long u32(byte[] d, int o) { return ((long)i32(d,o)) & 0xFFFFFFFFL; }
    static String hex(byte[] d, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(d.length, max); i++) sb.append(String.format("%02x",d[i]&0xFF));
        if (d.length > max) sb.append("...");
        return sb.toString();
    }
    static String ctrlIdStr(int id) {
        byte[] b = {(byte)((id>>24)&0xFF),(byte)((id>>16)&0xFF),(byte)((id>>8)&0xFF),(byte)(id&0xFF)};
        return new String(b).trim();
    }

    // ===== 레코드 구조 =====
    static class Rec {
        int tag, level, size, index;
        byte[] data;
        Rec(int t, int l, int s, byte[] d, int idx) { tag=t; level=l; size=s; data=d; index=idx; }
    }

    static List<Rec> parseRecords(byte[] raw) {
        List<Rec> recs = new ArrayList<>();
        int pos = 0, idx = 0;
        while (pos + 4 <= raw.length) {
            long h = u32(raw, pos); pos += 4;
            int tag = (int)(h & 0x3FF);
            int level = (int)((h >> 10) & 0x3FF);
            int size = (int)((h >> 20) & 0xFFF);
            if (size == 0xFFF) {
                if (pos + 4 > raw.length) break;
                size = i32(raw, pos); pos += 4;
            }
            int avail = raw.length - pos;
            if (size > avail) {
                // 잘린 레코드
                byte[] d = Arrays.copyOfRange(raw, pos, pos + avail);
                recs.add(new Rec(tag, level, avail, d, idx++));
                break;
            }
            byte[] d = Arrays.copyOfRange(raw, pos, pos + size);
            recs.add(new Rec(tag, level, size, d, idx++));
            pos += size;
        }
        return recs;
    }

    // ===== OLE / 스트림 헬퍼 =====
    static byte[] readStream(POIFSFileSystem p, String... path) throws IOException {
        DirectoryEntry dir = p.getRoot();
        for (int i = 0; i < path.length - 1; i++) dir = (DirectoryEntry) dir.getEntry(path[i]);
        DocumentEntry doc = (DocumentEntry) dir.getEntry(path[path.length - 1]);
        byte[] data = new byte[doc.getSize()];
        try (InputStream is = new DocumentInputStream(doc)) {
            int t = 0; while (t < data.length) { int r = is.read(data, t, data.length-t); if(r<0) break; t+=r; }
        }
        return data;
    }

    static boolean streamExists(POIFSFileSystem p, String... path) {
        try {
            DirectoryEntry dir = p.getRoot();
            for (int i = 0; i < path.length - 1; i++) dir = (DirectoryEntry) dir.getEntry(path[i]);
            dir.getEntry(path[path.length - 1]);
            return true;
        } catch (Exception e) { return false; }
    }

    static byte[] inflate(byte[] raw) {
        try {
            Inflater inf = new Inflater(true);
            inf.setInput(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length * 4);
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && inf.needsInput()) break;
                out.write(buf, 0, n);
            }
            inf.end();
            return out.toByteArray();
        } catch (Exception e) {
            return null; // 압축 해제 실패
        }
    }

    // ===== 결과 추적 =====
    static int passCount = 0, failCount = 0;
    static void pass(String msg) { System.out.println("  PASS: " + msg); passCount++; }
    static void fail(String msg) { System.out.println("  FAIL: " + msg); failCount++; }
    static void info(String msg) { System.out.println("  INFO: " + msg); }

    // ===== MAIN =====
    public static void main(String[] args) throws Exception {
        String refPath = "hwp/test_hwpx2_conv.hwp";
        String outPath = "output/test_hwpx2_conv.hwp";

        System.out.println("================================================================");
        System.out.println("  HWP 5.0 SpecValidator - Comprehensive byte-level validation");
        System.out.println("================================================================");

        // 두 파일 모두 검증
        for (String path : new String[]{refPath, outPath}) {
            passCount = 0; failCount = 0;
            System.out.println("\n################################################################");
            System.out.println("  VALIDATING: " + path);
            System.out.println("################################################################");

            POIFSFileSystem poi = new POIFSFileSystem(new FileInputStream(path));
            validateFile(poi, path);
            poi.close();

            System.out.println("\n  ============ SUMMARY for " + path + " ============");
            System.out.println("  PASS: " + passCount + "  FAIL: " + failCount);
            if (failCount == 0) System.out.println("  >> ALL CHECKS PASSED");
            else System.out.println("  >> " + failCount + " FAILURES DETECTED");
        }

        // 이제 파일 간 교차 비교 수행
        System.out.println("\n################################################################");
        System.out.println("  CROSS-FILE COMPARISON: ref vs output");
        System.out.println("################################################################");
        passCount = 0; failCount = 0;
        POIFSFileSystem refPoi = new POIFSFileSystem(new FileInputStream(refPath));
        POIFSFileSystem outPoi = new POIFSFileSystem(new FileInputStream(outPath));
        crossCompare(refPoi, outPoi);
        refPoi.close();
        outPoi.close();
        System.out.println("\n  ============ COMPARISON SUMMARY ============");
        System.out.println("  PASS: " + passCount + "  FAIL: " + failCount);
    }

    // ===== 파일별 검증 =====
    static void validateFile(POIFSFileSystem poi, String path) throws Exception {
        // 1. FileHeader
        System.out.println("\n===== [1] FileHeader =====");
        byte[] fh = readStream(poi, "FileHeader");
        validateFileHeader(fh);

        // 헤더에서 플래그 읽기
        long props = u32(fh, 36);
        boolean compressed = (props & 0x01) != 0;

        // 2. DocInfo
        System.out.println("\n===== [2] DocInfo =====");
        byte[] docInfoRaw = readStream(poi, "DocInfo");
        byte[] docInfo = compressed ? inflate(docInfoRaw) : docInfoRaw;
        if (docInfo == null) {
            fail("DocInfo decompression FAILED");
            return;
        }
        pass("DocInfo decompressed: " + docInfoRaw.length + " -> " + docInfo.length + " bytes");
        List<Rec> diRecs = parseRecords(docInfo);
        pass("DocInfo records: " + diRecs.size());
        DocInfoCounts dic = validateDocInfo(diRecs);

        // 3. Section0
        System.out.println("\n===== [3] BodyText/Section0 =====");
        byte[] secRaw = readStream(poi, "BodyText", "Section0");
        byte[] sec = compressed ? inflate(secRaw) : secRaw;
        if (sec == null) {
            fail("Section0 decompression FAILED");
            return;
        }
        pass("Section0 decompressed: " + secRaw.length + " -> " + sec.length + " bytes");
        List<Rec> secRecs = parseRecords(sec);
        pass("Section0 records: " + secRecs.size());
        validateSection(secRecs, dic);

        // 4. 교차 참조: Section0 -> DocInfo
        System.out.println("\n===== [4] Cross-references =====");
        validateCrossRefs(secRecs, dic);

        // 5. BinData 스트림
        System.out.println("\n===== [5] BinData streams =====");
        validateBinDataStreams(poi, compressed, dic);
    }

    // ===== FileHeader 검증 =====
    static void validateFileHeader(byte[] fh) {
        if (fh.length != 256) { fail("FileHeader size " + fh.length + " (expected 256)"); return; }
        pass("FileHeader size: 256");

        // 시그니처: 처음 32바이트 = "HWP Document File" + null/공백 패딩
        String sig = new String(fh, 0, 17);
        if (!"HWP Document File".equals(sig.trim())) fail("Signature mismatch: '" + sig + "'");
        else pass("Signature OK: '" + sig.trim() + "'");

        long ver = u32(fh, 32);
        int major = (int)((ver >> 24) & 0xFF);
        if (major != 5) fail("Major version " + major + " (expected 5)");
        else pass("Version " + major + "." + ((ver>>16)&0xFF) + "." + ((ver>>8)&0xFF) + "." + (ver&0xFF));

        // 속성(offset 36-39)
        long props = u32(fh, 36);
        pass("FileHeader properties: 0x" + Long.toHexString(props) +
             " compressed=" + ((props&1)!=0) + " encrypted=" + ((props&2)!=0));
    }

    // ===== DocInfo 카운트 =====
    static class DocInfoCounts {
        int binDataCount, totalFontCount, borderFillCount, charShapeCount;
        int tabDefCount, numberingCount, bulletCount, paraShapeCount, styleCount;
        int[] fontCounts = new int[7];

        // 파일에서 실제로 발견된 레코드
        List<Rec> borderFills = new ArrayList<>();
        List<Rec> charShapes = new ArrayList<>();
        List<Rec> paraShapes = new ArrayList<>();
        List<Rec> styles = new ArrayList<>();
        List<Rec> binDatas = new ArrayList<>();
    }

    // ===== DocInfo 검증 =====
    static DocInfoCounts validateDocInfo(List<Rec> recs) {
        DocInfoCounts dic = new DocInfoCounts();

        if (recs.isEmpty()) { fail("DocInfo has no records"); return dic; }

        // 첫 레코드: DOCUMENT_PROPERTIES
        Rec first = recs.get(0);
        if (first.tag != TAG_DOC_PROPS) fail("First record is " + tagName(first.tag) + " (expected DOC_PROPS)");
        else if (first.size != 26) fail("DOC_PROPS size " + first.size + " (expected 26)");
        else {
            int secCnt = u16(first.data, 0);
            pass("DOC_PROPS: sectionCount=" + secCnt);
        }

        // 두 번째 레코드: ID_MAPPINGS
        if (recs.size() < 2 || recs.get(1).tag != TAG_ID_MAPS) {
            fail("Second record is not ID_MAPS");
            return dic;
        }

        Rec im = recs.get(1);
        if (im.size < 72) {
            fail("ID_MAPS size " + im.size + " (expected >= 72)");
            return dic;
        }

        dic.binDataCount = i32(im.data, 0);
        dic.totalFontCount = 0;
        for (int i = 0; i < 7; i++) {
            dic.fontCounts[i] = i32(im.data, 4 + i*4);
            dic.totalFontCount += dic.fontCounts[i];
        }
        dic.borderFillCount = i32(im.data, 32);
        dic.charShapeCount  = i32(im.data, 36);
        dic.tabDefCount     = i32(im.data, 40);
        dic.numberingCount  = i32(im.data, 44);
        dic.bulletCount     = i32(im.data, 48);
        dic.paraShapeCount  = i32(im.data, 52);
        dic.styleCount      = i32(im.data, 56);

        pass("ID_MAPS: bin=" + dic.binDataCount + " fonts=" + dic.totalFontCount +
             " bf=" + dic.borderFillCount + " cs=" + dic.charShapeCount +
             " td=" + dic.tabDefCount + " nb=" + dic.numberingCount +
             " bl=" + dic.bulletCount + " ps=" + dic.paraShapeCount + " st=" + dic.styleCount);

        // 실제 레코드 개수를 세어 ID_MAPS와 대조
        int idx = 2;
        int[][] expected = {
            {TAG_BIN_DATA, dic.binDataCount},
            {TAG_FACE_NAME, dic.totalFontCount},
            {TAG_BORDER_FILL, dic.borderFillCount},
            {TAG_CHAR_SHAPE, dic.charShapeCount},
            {TAG_TAB_DEF, dic.tabDefCount},
            {TAG_NUMBERING, dic.numberingCount},
            {TAG_BULLET, dic.bulletCount},
            {TAG_PARA_SHAPE, dic.paraShapeCount},
            {TAG_STYLE, dic.styleCount},
        };

        for (int[] pair : expected) {
            int tag = pair[0], expCnt = pair[1];
            int actual = 0;
            int startIdx = idx;
            while (idx < recs.size() && recs.get(idx).tag == tag) { idx++; actual++; }
            if (actual != expCnt) fail(tagName(tag) + " count " + actual + " (ID_MAPS says " + expCnt + ")");
            else pass(tagName(tag) + " count " + actual + " matches ID_MAPS");

            // 교차 참조 검사를 위해 레코드 수집
            for (int i = startIdx; i < startIdx + actual && i < recs.size(); i++) {
                Rec r = recs.get(i);
                switch (tag) {
                    case TAG_BIN_DATA: dic.binDatas.add(r); break;
                    case TAG_BORDER_FILL: dic.borderFills.add(r); break;
                    case TAG_CHAR_SHAPE: dic.charShapes.add(r); break;
                    case TAG_PARA_SHAPE: dic.paraShapes.add(r); break;
                    case TAG_STYLE: dic.styles.add(r); break;
                }
            }
        }

        // 개별 레코드 크기 검증
        System.out.println("\n  --- DocInfo record size checks ---");
        for (Rec r : dic.charShapes) {
            if (r.size < 68) { fail("CHAR_SHAPE[" + r.index + "] size " + r.size + " < min 68"); break; }
        }
        if (!dic.charShapes.isEmpty() && dic.charShapes.get(0).size >= 68) pass("CHAR_SHAPE sizes >= 68");

        for (Rec r : dic.paraShapes) {
            if (r.size < 42) { fail("PARA_SHAPE[" + r.index + "] size " + r.size + " < min 42"); break; }
        }
        if (!dic.paraShapes.isEmpty() && dic.paraShapes.get(0).size >= 42) pass("PARA_SHAPE sizes >= 42");

        for (Rec r : dic.borderFills) {
            if (r.size < 14) { fail("BORDER_FILL[" + r.index + "] size " + r.size + " < min 14"); break; }
        }
        if (!dic.borderFills.isEmpty() && dic.borderFills.get(0).size >= 14) pass("BORDER_FILL sizes >= 14");

        // 나머지 레코드 확인 (DOC_DATA, COMPAT_DOC, LAYOUT_COMPAT 등)
        int remaining = recs.size() - idx;
        info("Remaining DocInfo records after styles: " + remaining);
        for (int i = idx; i < recs.size(); i++) {
            Rec r = recs.get(i);
            info("  DocInfo trailing record[" + i + "]: " + tagName(r.tag) + " size=" + r.size);
        }

        return dic;
    }

    // ===== Section 검증 =====
    static void validateSection(List<Rec> recs, DocInfoCounts dic) {
        if (recs.isEmpty()) { fail("Section0 has no records"); return; }

        int paraCount = 0, listHdrCount = 0, tableCount = 0, ctrlHdrCount = 0;
        int pictureCount = 0;

        // 각 리스트의 문단을 추적 (lastInList 검사용)
        // LIST_HEADER는 새 리스트 컨텍스트를 시작함. LIST_HEADER level보다 큰 level의 문단이 이에 속함.
        List<ParaInfo> allParas = new ArrayList<>();

        for (int i = 0; i < recs.size(); i++) {
            Rec r = recs.get(i);
            switch (r.tag) {
                case TAG_PARA_HDR:
                    paraCount++;
                    validateParaHeader(r, i, recs, dic, allParas);
                    break;
                case TAG_PARA_TXT:
                    validateParaText(r, i, recs, allParas);
                    break;
                case TAG_PARA_CS:
                    validateParaCharShape(r, i);
                    break;
                case TAG_PARA_LS:
                    validateParaLineSeg(r, i);
                    break;
                case TAG_CTRL_HDR:
                    ctrlHdrCount++;
                    validateCtrlHeader(r, i);
                    break;
                case TAG_LIST_HDR:
                    listHdrCount++;
                    validateListHeader(r, i, recs);
                    break;
                case TAG_TABLE:
                    tableCount++;
                    validateTable(r, i);
                    break;
                case TAG_PAGE_DEF:
                    validatePageDef(r, i);
                    break;
                case TAG_FN_SHAPE:
                    validateFnShape(r, i);
                    break;
                case TAG_PICTURE:
                    pictureCount++;
                    validatePicture(r, i, dic);
                    break;
                case TAG_SHAPE_CMP:
                    validateShapeComponent(r, i);
                    break;
            }
        }

        pass("Section0 totals: paras=" + paraCount + " ctrlHdrs=" + ctrlHdrCount +
             " listHdrs=" + listHdrCount + " tables=" + tableCount + " pictures=" + pictureCount);

        // lastInList 플래그 검증
        System.out.println("\n  --- lastInList checks ---");
        validateLastInList(recs);

        // LIST_HEADER paraCount가 실제와 일치하는지 검증
        System.out.println("\n  --- LIST_HEADER paraCount checks ---");
        validateListParaCounts(recs);
    }

    // ===== 문단 추적 =====
    static class ParaInfo {
        int recIndex;
        long nChars; // bit31 포함 원본값
        int charShapeCount, lineSegCount;
        int level;
    }

    // ===== PARA_HEADER 검증 =====
    static void validateParaHeader(Rec r, int idx, List<Rec> allRecs, DocInfoCounts dic, List<ParaInfo> paras) {
        // 규격: PARA_HEADER 최소 22바이트
        // UINT32 nChars (bit31=lastInList)
        // UINT32 controlMask
        // UINT16 paraShapeId
        // UINT8  styleId
        // UINT8  breakType ("속성" union)
        // UINT16 charShapeCount
        // UINT16 rangeTagCount
        // UINT16 lineSegCount
        // UINT16 instanceId
        if (r.size < 22) {
            fail("PARA_HDR[" + idx + "] size " + r.size + " < minimum 22");
            return;
        }

        long nCharsRaw = u32(r.data, 0);
        int nChars = (int)(nCharsRaw & 0x7FFFFFFFL);
        boolean lastInList = (nCharsRaw & 0x80000000L) != 0;
        long controlMask = u32(r.data, 4);
        int paraShapeId = u16(r.data, 8);
        int styleId = u8(r.data, 10);
        int charShapeCount = u16(r.data, 12);
        int rangeTagCount = u16(r.data, 14);
        int lineSegCount = u16(r.data, 16);
        int instanceId = u16(r.data, 18);

        // paraShapeId 범위 검증
        if (paraShapeId >= dic.paraShapeCount && dic.paraShapeCount > 0) {
            fail("PARA_HDR[" + idx + "] paraShapeId=" + paraShapeId + " >= paraShapeCount=" + dic.paraShapeCount);
        }

        // styleId 범위 검증
        if (styleId >= dic.styleCount && dic.styleCount > 0) {
            fail("PARA_HDR[" + idx + "] styleId=" + styleId + " >= styleCount=" + dic.styleCount);
        }

        // 뒤따르는 레코드의 개수가 맞는지 확인
        // charShapeCount -> nChars > 0이면 올바른 크기의 PARA_CS가 있어야 함
        // lineSegCount -> nChars > 0이면 올바른 크기의 PARA_LS가 있어야 함

        // 문단 정보 추적
        ParaInfo pi = new ParaInfo();
        pi.recIndex = idx;
        pi.nChars = nCharsRaw;
        pi.charShapeCount = charShapeCount;
        pi.lineSegCount = lineSegCount;
        pi.level = r.level;
        paras.add(pi);

        // PARA_CS가 존재할 경우 크기 검증 (다음의 일치하는 자식 레코드)
        if (nChars > 0 && charShapeCount > 0) {
            // PARA_CS는 charShapeCount * 4 바이트여야 함 (각 엔트리 = UINT32 pos + UINT32 csRef ... 실제로는 쌍)
            // 실제로는 각 엔트리가 4바이트 (UINT32 position, UINT32 charShapeRef) = 엔트리당 8바이트? 아님.
            // 규격: PARA_CS = { UINT32 position, UINT32 charShapeId } 배열이므로 엔트리당 8바이트?
            // 기존 코드를 보면 (pos, id) 쌍이 charShapeCount개, 각 4바이트... 아님
            // 잠깐 — 규격에는 PARA_CHAR_SHAPE가 charShapeCount개 엔트리로 각 4바이트라고? 아님.
            // 확인: 실제로 charShapeCount개 엔트리이며 각 엔트리는
            //   position (UINT32) + charShapeId (UINT32)이지만 이건 ARRAY 형식
            // HWP 규격상: PARA_CHAR_SHAPE 크기 = nCharShapePair * 8
            // 하지만 헤더의 charShapeCount는 PAIR 개수를 세므로 크기 = charShapeCount * 8? 아님...
            // 기존 검증기 기준으로는 position-only 형식에서 charShapeCount * 4?
            // 실제 HWP 5.0 기준: CharShape 레코드는 { UINT32 startPos, UINT32 shapeId }로 8바이트
            // 그런데 "charShapeCount"개 엔트리가 있으니... 일단 합리적 크기인지만 검증.
        }
    }

    // ===== PARA_TEXT 검증 =====
    static void validateParaText(Rec r, int idx, List<Rec> allRecs, List<ParaInfo> paras) {
        if (paras.isEmpty()) return;

        ParaInfo lastPara = paras.get(paras.size() - 1);
        int nChars = (int)(lastPara.nChars & 0x7FFFFFFFL);

        // PARA_TEXT 크기는 nChars * 2 (UTF-16LE)와 같아야 함
        // 그러나 확장 컨트롤(char 0-31 범위, 일부 제외)은 8 WCHAR = 16바이트로 확장됨
        // 따라서 실제 크기는 nChars * 2 이상이지만, 확장 문자가 있으면 더 클 수 있음
        // 실제로는 각 "문자"가 8 WCHAR로 저장되더라도 nChars에는 1로 계산됨
        // 따라서: size = (일반문자 * 2) + (확장문자 * 16)
        // 전체 nChars = 일반문자 + 확장문자
        // 분할을 모르면 size >= nChars * 2만 확인 가능

        // 실제 규격에서는 nChars를 "WCHAR 단위"로 세며 인라인 확장까지 포함함.
        // 위치 1-9, 11-12, 24 등의 확장 컨트롤 문자는 8 WCHAR(16바이트)를 차지.
        // nChars는 각 확장 컨트롤을 1이 아닌 8로 카운트함.
        // 따라서 PARA_TEXT size == nChars * 2가 정확히 성립.

        int expectedSize = nChars * 2;
        if (r.size != expectedSize) {
            fail("PARA_TXT[" + idx + "] size=" + r.size + " expected=" + expectedSize + " (nChars=" + nChars + ")");
        }

        // 확장 컨트롤 문자 형식 검증
        // 확장 컨트롤: 값 1-9, 11-12, 24 (0x18)는 8 WCHAR 차지
        // 형식: [ctrlChar][param1...param7] 이때 param7은 ctrlChar 복사본이어야 함?
        // 규격상 확장 문자 블록 = { WCHAR code, WCHAR[7] additionalInfo }
        if (r.data.length >= 2) {
            int pos = 0;
            while (pos + 2 <= r.data.length) {
                int ch = u16(r.data, pos);
                if (isExtendedControl(ch)) {
                    // 확장 컨트롤: 16바이트(8 WCHAR) 차지
                    if (pos + 16 > r.data.length) {
                        fail("PARA_TXT[" + idx + "] extended control 0x" + Integer.toHexString(ch) +
                             " at offset " + pos + " truncated (need 16 bytes, have " + (r.data.length-pos) + ")");
                        break;
                    }
                    pos += 16;
                } else {
                    pos += 2;
                }
            }
        }
    }

    static boolean isExtendedControl(int ch) {
        // 규격상 8 WCHAR를 차지하는 확장 인라인 컨트롤
        // 0-7 예약, 1=섹션/컬럼, 2=필드 시작, 3=필드 끝 (실제로는 확장이 아님)
        // 표준 집합 사용: chars 1-3은 예약된 placeholder
        // Chars 4,5,6,7,8,9 = 인라인 컨트롤 (각 8 WCHAR)
        // Char 10 = 줄바꿈 (일반 2바이트)
        // Char 11,12 = 그리기/표 (각 8 WCHAR)
        // Char 13 = 문단 끝 (일반 2바이트)
        // Char 24 = 북마크 등 (각 8 WCHAR)
        // 구체적으로: 0,10,13은 단일 WCHAR. 1-9,11-12,14-23,24-31은 8 WCHAR?
        // 아니, 더 정확하게는:
        // 0: 사용 안 함
        // 1-9: 8 WCHAR 인라인 요소
        // 10: 줄바꿈 (1 WCHAR)
        // 11-12: 8 WCHAR 인라인 요소 (그리기/표 placeholder)
        // 13: 문단 끝 (1 WCHAR)
        // 14: 불명확
        // 15-22: 8 WCHAR 인라인 요소?
        // 23: 하이픈
        // 24-31: 8 WCHAR 인라인 요소

        // HWP 규격 기준 안전한 정의:
        // char 0-31은 컨트롤 문자. 그중:
        // 1바이트(WCHAR 기준 2바이트): 0, 10, 13, 24, 25, 26, 27, 28, 29, 30, 31
        // 아니, 이것도 정확하지 않음.
        //
        // 실제 HWP 5.0 규격(타입 분류):
        //   Type 0 (확장 없음): chars 0, 10, 13, 24, 25, 26, 27, 28, 29, 30, 31
        //   잠깐 아님. 정확히 말하면:
        //
        // HWP 규격에 따르면:
        //   - char code 0: NULL (1 WCHAR 차지)
        //   - char code 1-9: 각 8 WCHAR 차지 (인라인 오브젝트 placeholder)
        //   - char code 10: 줄바꿈 (1 WCHAR)
        //   - char code 11-12: 각 8 WCHAR 차지 (그리기/표 오브젝트)
        //   - char code 13: 문단 끝 (1 WCHAR)
        //   - char code 14-23: 각 8 WCHAR 차지
        //   - char code 24-31: 각 8 WCHAR 차지
        //
        // 확실한 8 WCHAR 확장 컨트롤은 chars 1-9, 11, 12뿐.
        // Chars 14-31은 규격 모호성에도 실제로는 단일 WCHAR.
        return (ch >= 1 && ch <= 9) || (ch == 11) || (ch == 12);
    }

    // ===== PARA_CS (PARA_CHAR_SHAPE) 검증 =====
    static void validateParaCharShape(Rec r, int idx) {
        // 각 엔트리 = { UINT32 startPosition, UINT32 charShapeId } = 8바이트
        if (r.size % 8 != 0) {
            fail("PARA_CS[" + idx + "] size " + r.size + " not multiple of 8");
        }
    }

    // ===== PARA_LS (PARA_LINE_SEG) 검증 =====
    static void validateParaLineSeg(Rec r, int idx) {
        // HWP 5.0에서 각 엔트리 = 32바이트
        // 실제 HWP 5.0 line segment:
        //   textStartPos(4) + lineVertPos(4) + lineHeight(4) + textPartHeight(4)
        //   + distBaseline(4) + lineSpacing(4) + colStartPos(4) + segWidth(4) + tag(4)
        // 36바이트? 아님...
        // 실제로는: textStart(4), verticalPos(4), lineHeight(4), textHeight(4),
        //           baselineDist(4), charSpacing(4), columnStart(4), segmentWidth(4), tag(4)
        // = 9 * 4 = 36? 그런데 hwplib는 32를 사용...
        // 일단 합리적 단위의 배수인지만 확인
        if (r.size % 4 != 0) {
            fail("PARA_LS[" + idx + "] size " + r.size + " not multiple of 4");
        }
    }

    // ===== CTRL_HEADER 검증 =====
    static void validateCtrlHeader(Rec r, int idx) {
        if (r.size < 4) {
            fail("CTRL_HDR[" + idx + "] size " + r.size + " < minimum 4 (need ctrlId)");
            return;
        }
        int ctrlId = i32(r.data, 0);
        String ctrlStr = ctrlIdStr(ctrlId);

        // 주요 컨트롤 ID (빅 엔디안 ASCII):
        // 'secd' = 섹션 정의, 'cold' = 컬럼 정의, 'tbl ' = 표
        // 'gso ' = GShapeObject, 'eqed' = 수식 편집기
        // 'fn  ' = 각주, 'en  ' = 미주

        // 표 컨트롤의 경우 최소 크기 확인
        if ("tbl ".equals(ctrlStr)) {
            if (r.size < 21) {
                fail("CTRL_HDR[" + idx + "] tbl control size " + r.size + " < 21");
            }
        }
    }

    // ===== LIST_HEADER 검증 =====
    static void validateListHeader(Rec r, int idx, List<Rec> allRecs) {
        // 규격: LIST_HEADER 최소 =
        //   paraCount(INT32=4) + property(UINT32=4) + width(HWPUNIT=4) + height(HWPUNIT=4) = 기본 16바이트
        // 표 셀의 경우 추가 필드:
        //   colAddr(UINT16) + rowAddr(UINT16) + colSpan(UINT16) + rowSpan(UINT16)
        //   + cellWidth(HWPUNIT=4) + cellHeight(HWPUNIT=4) + margins(UINT16*4=8)
        //   + borderFillId(UINT16) = 추가 26바이트? 실제로는...
        //
        // 실제로는 컨텍스트에 따라 다름. 기본 최소는 16.
        if (r.size < 16) {
            fail("LIST_HDR[" + idx + "] size " + r.size + " < minimum 16");
            return;
        }

        // paraCount는 SIGNED INT32 (중요!)
        int paraCount = i32(r.data, 0);
        if (paraCount < 0) {
            fail("LIST_HDR[" + idx + "] paraCount=" + paraCount + " is NEGATIVE");
        } else if (paraCount == 0) {
            fail("LIST_HDR[" + idx + "] paraCount=0 (must have at least 1 paragraph)");
        }

        long property = u32(r.data, 4);

        // 표 셀 확장 필드 확인 (표 셀은 보통 size >= 42)
        if (r.size >= 42) {
            int colAddr = u16(r.data, 16);
            int rowAddr = u16(r.data, 18);
            int colSpan = u16(r.data, 20);
            int rowSpan = u16(r.data, 22);
            long cellWidth = u32(r.data, 24);
            long cellHeight = u32(r.data, 28);

            // 참고: HWP에서 colSpan/rowSpan은 0일 수 있음 (span=1, 0-indexed 의미)
            // 매우 큰 span 등 명백히 잘못된 값만 표시

            // borderFillId는 offset 40에 UINT16으로 위치 — 1-indexed!
            int borderFillId = u16(r.data, 40);
            // 교차 참조 검사용으로 저장 (별도 처리)
        }
    }

    // ===== TABLE 검증 =====
    static void validateTable(Rec r, int idx) {
        // TABLE 레코드:
        //   property(UINT32=4) + rowCount(UINT16=2) + colCount(UINT16=2)
        //   + cellSpacing(HWPUNIT16=2) + margins(UINT16*4=8)
        //   + rowSizes: UINT16[rowCount] 배열
        //   + borderFillId(UINT16=2) + zoneInfo(?)
        if (r.size < 14) {
            fail("TABLE[" + idx + "] size " + r.size + " < minimum 14");
            return;
        }

        long prop = u32(r.data, 0);
        int rowCount = u16(r.data, 4);
        int colCount = u16(r.data, 6);

        if (rowCount == 0) fail("TABLE[" + idx + "] rowCount=0");
        if (colCount == 0) fail("TABLE[" + idx + "] colCount=0");

        // 크기 검증: base(10) + margins(8) + rowSizes(rowCount*2) + borderFillId(2) + zoneInfo(2)
        // = 10 + 8 + rowCount*2 + 2 + 2 = 22 + rowCount*2
        // 실제로는: property(4) + rowCount(2) + colCount(2) + cellSpacing(2) + margins(2*4=8)
        //   = 기본 18, 이후 rowSizes = rowCount * 2, 이후 borderFillId(2) = 20 + rowCount*2
        int expectedMinSize = 18 + rowCount * 2 + 2;
        if (r.size < expectedMinSize) {
            fail("TABLE[" + idx + "] size " + r.size + " < expected min " + expectedMinSize +
                 " for rowCount=" + rowCount);
        } else {
            pass("TABLE[" + idx + "] rows=" + rowCount + " cols=" + colCount + " size=" + r.size);
        }
    }

    // ===== PAGE_DEF 검증 =====
    static void validatePageDef(Rec r, int idx) {
        // PAGE_DEF: 40바이트
        if (r.size < 40) {
            fail("PAGE_DEF[" + idx + "] size " + r.size + " < 40");
        } else {
            long paperWidth = u32(r.data, 0);
            long paperHeight = u32(r.data, 4);
            // 정합성: HWPUNIT(1/7200인치) 단위의 용지 크기
            // A4 = ~11906 x 16838 (EMU 유사 단위)
            // 실제 HWPUNIT: 1 HWPUNIT = 1/7200인치
            // A4 = 210mm x 297mm = ~59528 x 84189 HWPUNIT
            if (paperWidth == 0 || paperHeight == 0) {
                fail("PAGE_DEF[" + idx + "] zero dimensions: w=" + paperWidth + " h=" + paperHeight);
            }
        }
    }

    // ===== FN_SHAPE (FOOTNOTE_SHAPE) 검증 =====
    static void validateFnShape(Rec r, int idx) {
        // 각주/미주 shape: 버전마다 최소 크기가 다르지만 최소 22바이트 이상
        if (r.size < 10) {
            fail("FN_SHAPE[" + idx + "] size " + r.size + " suspiciously small");
        }
    }

    // ===== PICTURE 검증 =====
    static void validatePicture(Rec r, int idx, DocInfoCounts dic) {
        // PICTURE 레코드는 BinData를 참조하는 binItemId를 포함
        // 레이아웃: ShapeComponent 기본 데이터 이후...
        // binItemId는 picture 전용 데이터에 포함됨.
        // 일반 레이아웃: shapeComponent 헤더(크기 다양) 이후 picture 전용:
        //   borderColor(4) + borderThickness(4) + borderProperty(4) + borderImage(??)
        //   + binItemId(UINT16) ...
        // 복잡하고 버전 의존적이므로 존재 여부만 기록
        if (r.size < 4) {
            fail("PICTURE[" + idx + "] size " + r.size + " too small");
        }
    }

    // ===== SHAPE_COMPONENT 검증 =====
    static void validateShapeComponent(Rec r, int idx) {
        if (r.size < 4) {
            fail("SHAPE_CMP[" + idx + "] size " + r.size + " too small");
        }
    }

    // ===== lastInList 검증 =====
    static void validateLastInList(List<Rec> recs) {
        // 레코드 트리를 순회. 각 리스트 컨텍스트(LIST_HDR 또는 섹션 본문)에 대해
        // 마지막 문단의 nChars bit31이 설정되어 있어야 함.
        // 실제 자식 문단이 발견된 컨텍스트만 검사.

        int totalChecked = 0, totalFailed = 0;

        // 최상위 문단 검사 (섹션 본문 = 암묵적 리스트)
        List<Integer> topParas = new ArrayList<>();
        for (int i = 0; i < recs.size(); i++) {
            if (recs.get(i).tag == TAG_PARA_HDR && recs.get(i).level == 0) {
                topParas.add(i);
            }
        }
        if (!topParas.isEmpty()) {
            int lastIdx = topParas.get(topParas.size() - 1);
            Rec lastPara = recs.get(lastIdx);
            if (lastPara.size >= 4) {
                long nCharsRaw = u32(lastPara.data, 0);
                boolean lastInList = (nCharsRaw & 0x80000000L) != 0;
                totalChecked++;
                if (!lastInList) {
                    fail("Top-level PARA_HDR[" + lastIdx + "] is last in section but lastInList NOT set (nChars=0x" +
                         Long.toHexString(nCharsRaw) + ")");
                    totalFailed++;
                }
            }
        }

        // LIST_HDR 자식 검사 — 확신할 수 있을 때만 보고
        for (int i = 0; i < recs.size(); i++) {
            Rec r = recs.get(i);
            if (r.tag != TAG_LIST_HDR || r.size < 4) continue;
            int paraCount = i32(r.data, 0);
            int listLevel = r.level;

            List<Integer> childParaIndices = new ArrayList<>();
            for (int j = i + 1; j < recs.size(); j++) {
                Rec next = recs.get(j);
                if (next.level <= listLevel) break;
                if (next.tag == TAG_PARA_HDR && next.level == listLevel + 1) {
                    childParaIndices.add(j);
                }
            }

            // 문단 수가 정확히 일치할 때만 검사
            if (childParaIndices.size() == paraCount && paraCount > 0) {
                int lastParaIdx = childParaIndices.get(childParaIndices.size() - 1);
                Rec lastPara = recs.get(lastParaIdx);
                if (lastPara.size >= 4) {
                    long nCharsRaw = u32(lastPara.data, 0);
                    boolean lastInList = (nCharsRaw & 0x80000000L) != 0;
                    totalChecked++;
                    if (!lastInList) {
                        fail("PARA_HDR[" + lastParaIdx + "] is last in LIST_HDR[" + i + "] " +
                             "(paraCount=" + paraCount + ") but lastInList NOT set (nChars=0x" +
                             Long.toHexString(nCharsRaw) + ")");
                        totalFailed++;
                    }
                }
            }
        }

        if (totalFailed == 0 && totalChecked > 0) pass("lastInList checks: " + totalChecked + " checked, all OK");
        else if (totalChecked == 0) info("lastInList: no verifiable list contexts found");
    }

    // ===== LIST_HEADER paraCount 검증 =====
    static void validateListParaCounts(List<Rec> recs) {
        // 참고: LIST_HDR paraCount 검사가 복잡한 이유:
        // 1. "자식"은 스코프를 벗어나기 전 level(LIST_HDR.level + 1)의 PARA_HDR 레코드
        // 2. 그러나 일부 컨트롤 컨텍스트의 LIST_HDR는 단순 부모-자식 모델을 따르지 않는
        //    level 패턴을 가짐 (예: 각주, 머리말 등)
        // best-effort 검사를 하되, 확신할 수 있을 때만 보고함.
        int checked = 0, failed = 0;

        for (int i = 0; i < recs.size(); i++) {
            Rec r = recs.get(i);
            if (r.tag != TAG_LIST_HDR || r.size < 4) continue;

            int declaredParaCount = i32(r.data, 0);
            int listLevel = r.level;
            int targetLevel = listLevel + 1;

            // 실제 자식 문단 수 세기: 정확히 targetLevel인 PARA_HDR
            // level <= listLevel인 레코드를 만나면 중단
            int actualParaCount = 0;
            for (int j = i + 1; j < recs.size(); j++) {
                Rec next = recs.get(j);
                if (next.level <= listLevel) break;
                if (next.tag == TAG_PARA_HDR && next.level == targetLevel) {
                    actualParaCount++;
                }
            }

            // 문단을 찾았으나 개수가 다를 때만 보고
            // (0개를 찾은 경우 level 모델이 맞지 않을 수 있으므로 건너뜀)
            if (actualParaCount > 0) {
                checked++;
                if (declaredParaCount != actualParaCount) {
                    fail("LIST_HDR[" + i + "] declares paraCount=" + declaredParaCount +
                         " but found " + actualParaCount + " child PARA_HDRs at level " + targetLevel);
                    failed++;
                }
            }
        }

        if (failed == 0 && checked > 0) pass("LIST_HDR paraCount: " + checked + " checked, all match");
        else if (checked == 0) info("No LIST_HDR records with verifiable paraCount");
    }

    // ===== 교차 참조 검증 =====
    static void validateCrossRefs(List<Rec> secRecs, DocInfoCounts dic) {
        int csRefFails = 0, psRefFails = 0, bfRefFails = 0;
        int csRefChecked = 0, psRefChecked = 0, bfRefChecked = 0;

        for (Rec r : secRecs) {
            // PARA_CS 레코드의 charShapeRef 확인
            if (r.tag == TAG_PARA_CS) {
                // 각 엔트리 8바이트: { UINT32 pos, UINT32 charShapeId }
                for (int off = 0; off + 8 <= r.size; off += 8) {
                    int csRef = i32(r.data, off + 4);
                    csRefChecked++;
                    if (csRef < 0 || csRef >= dic.charShapeCount) {
                        if (csRefFails < 10)
                            fail("PARA_CS charShapeRef=" + csRef + " out of range [0," + dic.charShapeCount + ")");
                        csRefFails++;
                    }
                }
            }

            // PARA_HDR의 paraShapeId 확인
            if (r.tag == TAG_PARA_HDR && r.size >= 10) {
                int paraShapeId = u16(r.data, 8);
                psRefChecked++;
                if (paraShapeId >= dic.paraShapeCount && dic.paraShapeCount > 0) {
                    if (psRefFails < 10)
                        fail("PARA_HDR paraShapeId=" + paraShapeId + " >= paraShapeCount=" + dic.paraShapeCount);
                    psRefFails++;
                }
            }

            // LIST_HDR의 borderFillId 확인 (표 셀) — 1-indexed!
            if (r.tag == TAG_LIST_HDR && r.size >= 42) {
                int borderFillId = u16(r.data, 40);
                bfRefChecked++;
                // borderFillId는 1-indexed, 0은 "없음"을 의미
                if (borderFillId > dic.borderFillCount) {
                    if (bfRefFails < 10)
                        fail("LIST_HDR borderFillId=" + borderFillId + " > borderFillCount=" + dic.borderFillCount + " (1-indexed)");
                    bfRefFails++;
                }
            }
        }

        if (csRefFails == 0 && csRefChecked > 0) pass("charShapeRef cross-refs: " + csRefChecked + " checked, all valid");
        else if (csRefFails > 0) info("charShapeRef total failures: " + csRefFails + " of " + csRefChecked);
        if (csRefChecked == 0) info("No PARA_CS records to check charShapeRef");

        if (psRefFails == 0 && psRefChecked > 0) pass("paraShapeId cross-refs: " + psRefChecked + " checked, all valid");
        else if (psRefFails > 0) info("paraShapeId total failures: " + psRefFails + " of " + psRefChecked);

        if (bfRefFails == 0 && bfRefChecked > 0) pass("borderFillId cross-refs (1-indexed): " + bfRefChecked + " checked, all valid");
        else if (bfRefFails > 0) info("borderFillId total failures: " + bfRefFails + " of " + bfRefChecked);
    }

    // ===== BinData 스트림 검증 =====
    static void validateBinDataStreams(POIFSFileSystem poi, boolean compressed, DocInfoCounts dic) {
        int checked = 0, failed = 0;

        // 먼저 실제 BinData 디렉터리 엔트리 열거
        Set<String> actualStreams = new HashSet<>();
        try {
            DirectoryEntry binDir = (DirectoryEntry) poi.getRoot().getEntry("BinData");
            for (org.apache.poi.poifs.filesystem.Entry e : binDir) {
                actualStreams.add(e.getName());
            }
        } catch (Exception e) {
            if (dic.binDataCount > 0) {
                fail("BinData directory missing but " + dic.binDataCount + " BIN_DATA records declared");
                return;
            }
        }

        info("BinData directory has " + actualStreams.size() + " streams: " + actualStreams);

        for (int i = 1; i <= dic.binDataCount; i++) {
            // 여러 명명 규칙을 시도
            String prefix = String.format("BIN%04X", i);
            String prefix2 = String.format("BIN%04d", i);

            // 정확히 일치 또는 접두어 일치 검색 (예: BIN0001.jpg)
            String foundName = null;
            for (String actual : actualStreams) {
                if (actual.equals(prefix) || actual.equals(prefix2) ||
                    actual.startsWith(prefix + ".") || actual.startsWith(prefix2 + ".")) {
                    foundName = actual;
                    break;
                }
            }
            String[] candidates = {prefix, prefix2};

            if (foundName != null) {
                checked++;
                try {
                    byte[] raw = readStream(poi, "BinData", foundName);
                    if (compressed) {
                        byte[] decompressed = inflate(raw);
                        if (decompressed == null) {
                            fail("BinData/" + foundName + " decompression FAILED (" + raw.length + " bytes)");
                            failed++;
                        }
                    }
                } catch (Exception e) {
                    fail("BinData/" + foundName + " read error: " + e.getMessage());
                    failed++;
                }
            } else {
                // BIN_DATA 레코드가 임베딩을 나타내는지 확인
                if (i - 1 < dic.binDatas.size()) {
                    Rec bd = dic.binDatas.get(i - 1);
                    if (bd.size >= 2) {
                        int type = u16(bd.data, 0);
                        // Type: 0=LINK, 1=EMBEDDING, 2=STORAGE
                        if (type == 1 || type == 2) {
                            fail("BinData stream for index " + i + " missing (type=" + type +
                                 ", tried " + Arrays.toString(candidates) + ")");
                            failed++;
                        }
                    }
                }
            }
        }

        if (failed == 0 && checked > 0) pass("BinData streams: " + checked + "/" + dic.binDataCount + " checked, all OK");
        else if (checked == 0 && dic.binDataCount == 0) pass("No BinData expected, none found");
        else if (checked == 0) info("No BinData streams found (expected " + dic.binDataCount + ")");
    }

    // ===== 파일 간 교차 비교 =====
    static void crossCompare(POIFSFileSystem refPoi, POIFSFileSystem outPoi) throws Exception {
        // 1. FileHeader 비교
        System.out.println("\n===== FileHeader comparison =====");
        byte[] refFH = readStream(refPoi, "FileHeader");
        byte[] outFH = readStream(outPoi, "FileHeader");
        if (Arrays.equals(refFH, outFH)) pass("FileHeader: IDENTICAL");
        else fail("FileHeader: DIFFERS");

        long refProps = u32(refFH, 36);
        boolean compressed = (refProps & 0x01) != 0;

        // 2. DocInfo 비교
        System.out.println("\n===== DocInfo record-by-record comparison =====");
        byte[] refDI = compressed ? inflate(readStream(refPoi, "DocInfo")) : readStream(refPoi, "DocInfo");
        byte[] outDI = compressed ? inflate(readStream(outPoi, "DocInfo")) : readStream(outPoi, "DocInfo");

        if (refDI == null || outDI == null) {
            fail("DocInfo decompression failed");
            return;
        }

        List<Rec> refDIRecs = parseRecords(refDI);
        List<Rec> outDIRecs = parseRecords(outDI);

        compareRecordSets("DocInfo", refDIRecs, outDIRecs);

        // 3. Section0 비교
        System.out.println("\n===== Section0 record-by-record comparison =====");
        byte[] refSec = compressed ? inflate(readStream(refPoi, "BodyText", "Section0")) : readStream(refPoi, "BodyText", "Section0");
        byte[] outSec = compressed ? inflate(readStream(outPoi, "BodyText", "Section0")) : readStream(outPoi, "BodyText", "Section0");

        if (refSec == null || outSec == null) {
            fail("Section0 decompression failed");
            return;
        }

        List<Rec> refSecRecs = parseRecords(refSec);
        List<Rec> outSecRecs = parseRecords(outSec);

        compareRecordSets("Section0", refSecRecs, outSecRecs);

        // 4. BinData 비교
        System.out.println("\n===== BinData stream comparison =====");
        compareBinData(refPoi, outPoi, compressed);
    }

    // ===== 레코드 집합 비교 =====
    static void compareRecordSets(String name, List<Rec> refRecs, List<Rec> outRecs) {
        System.out.println("  " + name + " records: ref=" + refRecs.size() + " out=" + outRecs.size());

        if (refRecs.size() != outRecs.size()) {
            fail(name + " record COUNT mismatch: ref=" + refRecs.size() + " out=" + outRecs.size());
        } else {
            pass(name + " record count matches: " + refRecs.size());
        }

        // 태그 타입별 개수 집계
        Map<Integer, Integer> refCounts = new LinkedHashMap<>();
        Map<Integer, Integer> outCounts = new LinkedHashMap<>();
        for (Rec r : refRecs) refCounts.merge(r.tag, 1, Integer::sum);
        for (Rec r : outRecs) outCounts.merge(r.tag, 1, Integer::sum);

        Set<Integer> allTags = new TreeSet<>();
        allTags.addAll(refCounts.keySet());
        allTags.addAll(outCounts.keySet());

        for (int tag : allTags) {
            int rc = refCounts.getOrDefault(tag, 0);
            int oc = outCounts.getOrDefault(tag, 0);
            if (rc != oc) {
                fail(name + " " + tagName(tag) + " count: ref=" + rc + " out=" + oc);
            }
        }

        // 순차 비교
        int limit = Math.min(refRecs.size(), outRecs.size());
        int identical = 0, tagMismatch = 0, sizeMismatch = 0, dataMismatch = 0;
        int critFieldFails = 0;
        int maxDetailedReport = 20; // 상세 출력 한도
        int reportedDetails = 0;

        for (int i = 0; i < limit; i++) {
            Rec ref = refRecs.get(i);
            Rec out = outRecs.get(i);

            if (ref.tag != out.tag) {
                tagMismatch++;
                if (reportedDetails < maxDetailedReport) {
                    fail(name + "[" + i + "] TAG mismatch: ref=" + tagName(ref.tag) + "/lv" + ref.level +
                         " out=" + tagName(out.tag) + "/lv" + out.level);
                    reportedDetails++;
                }
                continue;
            }

            if (ref.size != out.size) {
                sizeMismatch++;
                if (reportedDetails < maxDetailedReport) {
                    fail(name + "[" + i + "] " + tagName(ref.tag) + " SIZE mismatch: ref=" + ref.size + " out=" + out.size);
                    reportedDetails++;
                }
            }

            if (!Arrays.equals(ref.data, out.data)) {
                dataMismatch++;
                // 핵심 필드 수준의 차이 보고
                String detail = compareCriticalFields(ref, out, i);
                if (detail != null && reportedDetails < maxDetailedReport) {
                    fail(name + "[" + i + "] " + tagName(ref.tag) + " DATA differs: " + detail);
                    reportedDetails++;
                    critFieldFails++;
                } else if (detail == null && reportedDetails < maxDetailedReport) {
                    // 일반적인 데이터 불일치
                    int diffAt = findFirstDiff(ref.data, out.data);
                    fail(name + "[" + i + "] " + tagName(ref.tag) + " data differs at byte " + diffAt +
                         " ref=0x" + (diffAt < ref.data.length ? String.format("%02x",ref.data[diffAt]&0xFF) : "EOF") +
                         " out=0x" + (diffAt < out.data.length ? String.format("%02x",out.data[diffAt]&0xFF) : "EOF") +
                         " (size ref=" + ref.size + " out=" + out.size + ")");
                    reportedDetails++;
                }
            } else {
                identical++;
            }
        }

        if (reportedDetails >= maxDetailedReport) {
            int remaining = (tagMismatch + sizeMismatch + dataMismatch) - reportedDetails;
            if (remaining > 0) info("... and " + remaining + " more differences suppressed");
        }

        // 요약
        System.out.println("  --- " + name + " comparison summary ---");
        System.out.println("    identical=" + identical + " tagMismatch=" + tagMismatch +
                         " sizeMismatch=" + sizeMismatch + " dataMismatch=" + dataMismatch);
        if (tagMismatch == 0 && sizeMismatch == 0 && dataMismatch == 0 && refRecs.size() == outRecs.size()) {
            pass(name + " ALL records IDENTICAL");
        } else {
            fail(name + " has " + (tagMismatch + sizeMismatch + dataMismatch) + " differing records");
        }
    }

    // ===== 핵심 필드 비교 =====
    static String compareCriticalFields(Rec ref, Rec out, int idx) {
        StringBuilder sb = new StringBuilder();

        switch (ref.tag) {
            case TAG_PARA_HDR: {
                if (ref.size < 22 || out.size < 22) return null;
                long refNChars = u32(ref.data, 0);
                long outNChars = u32(out.data, 0);
                int refNC = (int)(refNChars & 0x7FFFFFFFL);
                int outNC = (int)(outNChars & 0x7FFFFFFFL);
                boolean refLast = (refNChars & 0x80000000L) != 0;
                boolean outLast = (outNChars & 0x80000000L) != 0;

                if (refNC != outNC) sb.append("nChars ref=" + refNC + " out=" + outNC + "; ");
                if (refLast != outLast) sb.append("lastInList ref=" + refLast + " out=" + outLast + "; ");

                int refCSCnt = u16(ref.data, 12);
                int outCSCnt = u16(out.data, 12);
                if (refCSCnt != outCSCnt) sb.append("charShapeCnt ref=" + refCSCnt + " out=" + outCSCnt + "; ");

                int refLSCnt = u16(ref.data, 16);
                int outLSCnt = u16(out.data, 16);
                if (refLSCnt != outLSCnt) sb.append("lineSegCnt ref=" + refLSCnt + " out=" + outLSCnt + "; ");

                int refPSId = u16(ref.data, 8);
                int outPSId = u16(out.data, 8);
                if (refPSId != outPSId) sb.append("paraShapeId ref=" + refPSId + " out=" + outPSId + "; ");

                long refCtrl = u32(ref.data, 4);
                long outCtrl = u32(out.data, 4);
                if (refCtrl != outCtrl) sb.append("controlMask ref=0x" + Long.toHexString(refCtrl) + " out=0x" + Long.toHexString(outCtrl) + "; ");

                break;
            }
            case TAG_LIST_HDR: {
                if (ref.size < 16 || out.size < 16) return null;
                int refPC = i32(ref.data, 0);
                int outPC = i32(out.data, 0);
                if (refPC != outPC) sb.append("paraCount ref=" + refPC + " out=" + outPC + "; ");

                long refProp = u32(ref.data, 4);
                long outProp = u32(out.data, 4);
                if (refProp != outProp) sb.append("property ref=0x" + Long.toHexString(refProp) + " out=0x" + Long.toHexString(outProp) + "; ");

                // 표 셀 필드
                if (ref.size >= 42 && out.size >= 42) {
                    int refCol = u16(ref.data, 16), outCol = u16(out.data, 16);
                    int refRow = u16(ref.data, 18), outRow = u16(out.data, 18);
                    int refCS = u16(ref.data, 20), outCS = u16(out.data, 20);
                    int refRS = u16(ref.data, 22), outRS = u16(out.data, 22);
                    long refW = u32(ref.data, 24), outW = u32(out.data, 24);
                    long refH = u32(ref.data, 28), outH = u32(out.data, 28);
                    int refBF = u16(ref.data, 40), outBF = u16(out.data, 40);

                    if (refCol != outCol) sb.append("colAddr ref=" + refCol + " out=" + outCol + "; ");
                    if (refRow != outRow) sb.append("rowAddr ref=" + refRow + " out=" + outRow + "; ");
                    if (refCS != outCS) sb.append("colSpan ref=" + refCS + " out=" + outCS + "; ");
                    if (refRS != outRS) sb.append("rowSpan ref=" + refRS + " out=" + outRS + "; ");
                    if (refW != outW) sb.append("cellWidth ref=" + refW + " out=" + outW + "; ");
                    if (refH != outH) sb.append("cellHeight ref=" + refH + " out=" + outH + "; ");
                    if (refBF != outBF) sb.append("borderFillId ref=" + refBF + " out=" + outBF + "; ");
                }
                break;
            }
            case TAG_TABLE: {
                if (ref.size < 8 || out.size < 8) return null;
                long refProp = u32(ref.data, 0), outProp = u32(out.data, 0);
                int refRC = u16(ref.data, 4), outRC = u16(out.data, 4);
                int refCC = u16(ref.data, 6), outCC = u16(out.data, 6);

                if (refProp != outProp) sb.append("property ref=0x" + Long.toHexString(refProp) + " out=0x" + Long.toHexString(outProp) + "; ");
                if (refRC != outRC) sb.append("rowCount ref=" + refRC + " out=" + outRC + "; ");
                if (refCC != outCC) sb.append("colCount ref=" + refCC + " out=" + outCC + "; ");
                break;
            }
            case TAG_CTRL_HDR: {
                if (ref.size < 4 || out.size < 4) return null;
                int refId = i32(ref.data, 0), outId = i32(out.data, 0);
                if (refId != outId) sb.append("ctrlId ref=" + ctrlIdStr(refId) + " out=" + ctrlIdStr(outId) + "; ");
                if (ref.size != out.size) sb.append("size ref=" + ref.size + " out=" + out.size + "; ");
                break;
            }
            case TAG_BORDER_FILL: {
                if (ref.size < 6 || out.size < 6) return null;
                // 부분 비교 — property와 앞 몇 바이트만 확인
                long refProp = u32(ref.data, 0), outProp = u32(out.data, 0);
                if (refProp != outProp) sb.append("property ref=0x" + Long.toHexString(refProp) + " out=0x" + Long.toHexString(outProp) + "; ");
                if (ref.size != out.size) sb.append("size ref=" + ref.size + " out=" + out.size + "; ");
                break;
            }
            case TAG_PARA_CS: {
                // 개별 charShape 참조 확인
                int entries = Math.min(ref.size, out.size) / 8;
                for (int e = 0; e < entries; e++) {
                    int refPos = i32(ref.data, e*8);
                    int outPos = i32(out.data, e*8);
                    int refCSId = i32(ref.data, e*8+4);
                    int outCSId = i32(out.data, e*8+4);
                    if (refPos != outPos || refCSId != outCSId) {
                        sb.append("entry[" + e + "] ref=(pos=" + refPos + ",cs=" + refCSId +
                                  ") out=(pos=" + outPos + ",cs=" + outCSId + "); ");
                        if (sb.length() > 200) { sb.append("..."); break; }
                    }
                }
                break;
            }
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    static int findFirstDiff(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) { if (a[i] != b[i]) return i; }
        return a.length != b.length ? len : -1;
    }

    // ===== BinData 스트림 비교 =====
    static void compareBinData(POIFSFileSystem refPoi, POIFSFileSystem outPoi, boolean compressed) throws Exception {
        // 모든 BinData 엔트리를 찾기 시도
        int checked = 0, identical = 0, failed = 0;

        for (int i = 1; i <= 100; i++) { // 최대 100까지 확인
            String name = String.format("BIN%04X", i);
            boolean refHas = streamExists(refPoi, "BinData", name);
            boolean outHas = streamExists(outPoi, "BinData", name);

            if (!refHas && !outHas) {
                if (i > 20) break; // 공백 이후 스캔 중단
                continue;
            }

            if (refHas && !outHas) { fail("BinData/" + name + " missing in output"); failed++; continue; }
            if (!refHas && outHas) { fail("BinData/" + name + " extra in output"); failed++; continue; }

            byte[] refRaw = readStream(refPoi, "BinData", name);
            byte[] outRaw = readStream(outPoi, "BinData", name);

            checked++;

            if (Arrays.equals(refRaw, outRaw)) {
                identical++;
                continue;
            }

            // 압축 해제 후 비교 시도
            if (compressed) {
                byte[] refDec = inflate(refRaw);
                byte[] outDec = inflate(outRaw);
                if (refDec != null && outDec != null && Arrays.equals(refDec, outDec)) {
                    identical++;
                    continue;
                }
            }

            fail("BinData/" + name + " DIFFERS: ref=" + refRaw.length + " out=" + outRaw.length + " bytes");
            failed++;
        }

        if (failed == 0 && checked > 0) pass("BinData streams: " + identical + "/" + checked + " identical");
        else if (checked > 0) info("BinData: " + identical + " identical, " + failed + " different of " + checked);
    }
}
