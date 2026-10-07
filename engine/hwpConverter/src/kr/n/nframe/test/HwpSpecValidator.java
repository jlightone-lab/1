package kr.n.nframe.test;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.poifs.filesystem.*;

/**
 * HWP 5.0 규격 기반 유효성 검사기.
 *
 * 규격 문서 "한글문서파일형식 5.0 revision 1.3"에 따라
 * 각 레코드의 바이트 구조를 검증합니다.
 *
 * 참조 파일과의 비교가 아닌, **규격 자체에 대한 적합성**을 검사합니다.
 */
public class HwpSpecValidator {

    static int errors = 0;
    static int warnings = 0;

    static void fail(String msg) { System.out.println("  FAIL: " + msg); errors++; }
    static void warn(String msg) { System.out.println("  WARN: " + msg); warnings++; }
    static void pass(String msg) { System.out.println("  PASS: " + msg); }

    // ---- 바이트 읽기 유틸 ----
    static int u8(byte[] d, int o) { return d[o] & 0xFF; }
    static int u16(byte[] d, int o) { return (d[o]&0xFF) | ((d[o+1]&0xFF)<<8); }
    static long u32(byte[] d, int o) { return ((long)(d[o]&0xFF)) | ((long)(d[o+1]&0xFF)<<8) | ((long)(d[o+2]&0xFF)<<16) | ((long)(d[o+3]&0xFF)<<24); }
    static int i32(byte[] d, int o) { return (d[o]&0xFF) | ((d[o+1]&0xFF)<<8) | ((d[o+2]&0xFF)<<16) | ((d[o+3]&0xFF)<<24); }

    static class Rec {
        int tag, level, size;
        byte[] data;
        Rec(int t, int l, int s, byte[] d) { tag=t; level=l; size=s; data=d; }
    }

    static List<Rec> parseRecords(byte[] raw) {
        List<Rec> recs = new ArrayList<>();
        int pos = 0;
        while (pos + 4 <= raw.length) {
            long h = u32(raw, pos); pos += 4;
            int tag = (int)(h & 0x3FF);
            int level = (int)((h >> 10) & 0x3FF);
            int size = (int)((h >> 20) & 0xFFF);
            if (size == 0xFFF) {
                if (pos + 4 > raw.length) break;
                size = i32(raw, pos); pos += 4;
            }
            if (pos + size > raw.length) {
                fail("Record at offset " + (pos-4) + " extends beyond data (tag=0x" +
                     Integer.toHexString(tag) + " size=" + size + " remaining=" + (raw.length-pos) + ")");
                break;
            }
            byte[] d = Arrays.copyOfRange(raw, pos, pos + size);
            recs.add(new Rec(tag, level, size, d));
            pos += size;
        }
        return recs;
    }

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
            fail("zlib 해제 실패: " + e.getMessage());
            return raw;
        }
    }

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "output/test_hwpx2_conv.hwp";
        System.out.println("HWP 5.0 규격 검증: " + path);

        POIFSFileSystem poi = new POIFSFileSystem(new FileInputStream(path));

        // ========== 1. FileHeader 검증 (규격 3.2.1) ==========
        System.out.println("\n===== FileHeader (규격 3.2.1) =====");
        validateFileHeader(readStream(poi, "FileHeader"));

        // ========== 2. DocInfo 검증 (규격 4.2) ==========
        System.out.println("\n===== DocInfo (규격 4.2) =====");
        byte[] docInfoRaw = readStream(poi, "DocInfo");
        byte[] docInfo = inflate(docInfoRaw);
        validateDocInfo(docInfo);

        // ========== 3. BodyText/Section0 검증 (규격 4.3) ==========
        System.out.println("\n===== BodyText/Section0 (규격 4.3) =====");
        byte[] secRaw = readStream(poi, "BodyText", "Section0");
        byte[] sec = inflate(secRaw);
        validateSection(sec);

        // ========== 4. 스트림 존재 여부 ==========
        System.out.println("\n===== 필수 스트림 =====");
        checkStreamExists(poi, "FileHeader");
        checkStreamExists(poi, "DocInfo");
        checkStreamExists(poi, "BodyText", "Section0");

        poi.close();

        System.out.println("\n===== 결과 =====");
        System.out.println("  Errors: " + errors + "  Warnings: " + warnings);
        if (errors == 0) System.out.println("  >> 유효한 HWP 파일입니다.");
        else System.out.println("  >> " + errors + "개의 규격 위반이 발견되었습니다.");
    }

    // ========== FileHeader 검증 ==========
    static void validateFileHeader(byte[] fh) {
        // 규격: 256바이트 고정
        if (fh.length != 256) {
            fail("FileHeader 크기 " + fh.length + " (규격: 256)");
            return;
        }

        // 시그니처: "HWP Document File" (18바이트) + null 패딩
        String sig = new String(fh, 0, 18);
        if (!"HWP Document File".equals(sig)) {
            fail("시그니처 불일치: '" + sig + "'");
        } else {
            pass("시그니처 OK");
        }

        // 버전 (offset 32-35)
        long ver = u32(fh, 32);
        int major = (int)((ver >> 24) & 0xFF);
        int minor = (int)((ver >> 16) & 0xFF);
        int build = (int)((ver >> 8) & 0xFF);
        int rev = (int)(ver & 0xFF);
        if (major != 5) {
            fail("메이저 버전 " + major + " (규격: 5)");
        } else {
            pass("버전 " + major + "." + minor + "." + build + "." + rev);
        }

        // 속성 (offset 36-39)
        long props = u32(fh, 36);
        boolean compressed = (props & 0x01) != 0;
        boolean encrypted = (props & 0x02) != 0;
        pass("속성: compressed=" + compressed + " encrypted=" + encrypted);
    }

    // ========== DocInfo 검증 ==========
    static void validateDocInfo(byte[] data) {
        List<Rec> recs = parseRecords(data);
        pass("레코드 수: " + recs.size());

        if (recs.isEmpty()) { fail("DocInfo 레코드 없음"); return; }

        // 규격 4.2: 첫 레코드는 반드시 DOCUMENT_PROPERTIES (0x010)
        Rec first = recs.get(0);
        if (first.tag != 0x010) {
            fail("첫 레코드가 DOCUMENT_PROPERTIES(0x010)가 아님: 0x" + Integer.toHexString(first.tag));
        } else if (first.size != 26) {
            fail("DOCUMENT_PROPERTIES 크기 " + first.size + " (규격: 26)");
        } else {
            int secCnt = u16(first.data, 0);
            pass("DOCUMENT_PROPERTIES: sectionCount=" + secCnt);
        }

        // 두번째 레코드: ID_MAPPINGS (0x011)
        if (recs.size() < 2 || recs.get(1).tag != 0x011) {
            fail("두번째 레코드가 ID_MAPPINGS(0x011)가 아님");
        } else {
            Rec im = recs.get(1);
            if (im.size != 72) {
                fail("ID_MAPPINGS 크기 " + im.size + " (규격: 72)");
            } else {
                // 규격 4.2.2: 18개 INT32 카운트
                int binCnt = i32(im.data, 0);
                int[] fontCnts = new int[7];
                for (int i = 0; i < 7; i++) fontCnts[i] = i32(im.data, 4 + i*4);
                int bfCnt = i32(im.data, 32);
                int csCnt = i32(im.data, 36);
                int tdCnt = i32(im.data, 40);
                int nbCnt = i32(im.data, 44);
                int blCnt = i32(im.data, 48);
                int psCnt = i32(im.data, 52);
                int stCnt = i32(im.data, 56);
                pass("ID_MAPPINGS: bin=" + binCnt + " fonts=" + Arrays.toString(fontCnts) +
                     " bf=" + bfCnt + " cs=" + csCnt + " td=" + tdCnt +
                     " nb=" + nbCnt + " bl=" + blCnt + " ps=" + psCnt + " st=" + stCnt);

                // 이후 레코드 수 검증
                int expectedIdx = 2; // 다음 레코드 인덱스
                int totalFonts = 0;
                for (int c : fontCnts) totalFonts += c;

                // BIN_DATA 개수 검증
                int actualBin = 0;
                for (int i = expectedIdx; i < recs.size() && recs.get(i).tag == 0x012; i++) actualBin++;
                if (actualBin != binCnt) fail("BIN_DATA 개수 " + actualBin + " (ID_MAPPINGS: " + binCnt + ")");
                else pass("BIN_DATA 개수 " + actualBin + " OK");
                expectedIdx += actualBin;

                // FACE_NAME 개수 검증
                int actualFN = 0;
                for (int i = expectedIdx; i < recs.size() && recs.get(i).tag == 0x013; i++) actualFN++;
                if (actualFN != totalFonts) fail("FACE_NAME 개수 " + actualFN + " (ID_MAPPINGS: " + totalFonts + ")");
                else pass("FACE_NAME 개수 " + actualFN + " OK");
                expectedIdx += actualFN;

                // BORDER_FILL 개수
                int actualBF = 0;
                for (int i = expectedIdx; i < recs.size() && recs.get(i).tag == 0x014; i++) actualBF++;
                if (actualBF != bfCnt) fail("BORDER_FILL 개수 " + actualBF + " (ID_MAPPINGS: " + bfCnt + ")");
                else pass("BORDER_FILL 개수 " + actualBF + " OK");
                expectedIdx += actualBF;

                // CHAR_SHAPE 개수
                int actualCS = 0;
                for (int i = expectedIdx; i < recs.size() && recs.get(i).tag == 0x015; i++) actualCS++;
                if (actualCS != csCnt) fail("CHAR_SHAPE 개수 " + actualCS + " (ID_MAPPINGS: " + csCnt + ")");
                else pass("CHAR_SHAPE 개수 " + actualCS + " OK");
                expectedIdx += actualCS;

                // TAB_DEF 개수
                int actualTD = 0;
                for (int i = expectedIdx; i < recs.size() && recs.get(i).tag == 0x016; i++) actualTD++;
                if (actualTD != tdCnt) fail("TAB_DEF 개수 " + actualTD + " (ID_MAPPINGS: " + tdCnt + ")");
                else pass("TAB_DEF 개수 " + actualTD + " OK");
                expectedIdx += actualTD;

                // NUMBERING 개수
                int actualNB = 0;
                for (int i = expectedIdx; i < recs.size() && recs.get(i).tag == 0x017; i++) actualNB++;
                if (actualNB != nbCnt) fail("NUMBERING 개수 " + actualNB + " (ID_MAPPINGS: " + nbCnt + ")");
                else pass("NUMBERING 개수 " + actualNB + " OK");
                expectedIdx += actualNB;

                // BULLET 개수
                int actualBL = 0;
                for (int i = expectedIdx; i < recs.size() && recs.get(i).tag == 0x018; i++) actualBL++;
                if (actualBL != blCnt) fail("BULLET 개수 " + actualBL + " (ID_MAPPINGS: " + blCnt + ")");
                else pass("BULLET 개수 " + actualBL + " OK");
                expectedIdx += actualBL;

                // PARA_SHAPE 개수
                int actualPS = 0;
                for (int i = expectedIdx; i < recs.size() && recs.get(i).tag == 0x019; i++) actualPS++;
                if (actualPS != psCnt) fail("PARA_SHAPE 개수 " + actualPS + " (ID_MAPPINGS: " + psCnt + ")");
                else pass("PARA_SHAPE 개수 " + actualPS + " OK");
                expectedIdx += actualPS;

                // STYLE 개수
                int actualST = 0;
                for (int i = expectedIdx; i < recs.size() && recs.get(i).tag == 0x01A; i++) actualST++;
                if (actualST != stCnt) fail("STYLE 개수 " + actualST + " (ID_MAPPINGS: " + stCnt + ")");
                else pass("STYLE 개수 " + actualST + " OK");

                // 각 레코드 크기 검증
                validateDocInfoRecordSizes(recs, expectedIdx, csCnt, psCnt);
            }
        }
    }

    static void validateDocInfoRecordSizes(List<Rec> recs, int afterStyle, int csCnt, int psCnt) {
        // 규격 4.2.6: CHAR_SHAPE는 최소 68바이트, 보통 74바이트
        for (Rec r : recs) {
            if (r.tag == 0x015) {
                if (r.size < 68) {
                    fail("CHAR_SHAPE 크기 " + r.size + " < 최소 68바이트");
                    break;
                }
            }
        }

        // 규격 4.2.10: PARA_SHAPE는 최소 42바이트 (기본) ~ 58바이트 (5.0.2.5+)
        for (Rec r : recs) {
            if (r.tag == 0x019) {
                if (r.size < 42) {
                    fail("PARA_SHAPE 크기 " + r.size + " < 최소 42바이트");
                    break;
                }
            }
        }

        // 규격 4.2.5: BORDER_FILL 최소 크기 = 32(border) + 4(fillType) + 4(additionalSize) = 40
        for (Rec r : recs) {
            if (r.tag == 0x014) {
                if (r.size < 40) {
                    fail("BORDER_FILL 크기 " + r.size + " < 최소 40바이트");
                    break;
                }
                // fillType 검증
                if (r.data.length >= 36) {
                    long ft = u32(r.data, 32);
                    if (ft == 0 && r.size != 40) {
                        warn("BORDER_FILL fillType=0인데 크기=" + r.size + " (기대: 40)");
                    }
                }
            }
        }

        // COMPATIBLE_DOCUMENT (0x01E) 존재 확인
        boolean hasCompat = recs.stream().anyMatch(r -> r.tag == 0x01E);
        if (!hasCompat) warn("COMPATIBLE_DOCUMENT(0x01E) 레코드 없음");
        else pass("COMPATIBLE_DOCUMENT 존재");

        // LAYOUT_COMPATIBILITY (0x01F) 존재 확인
        boolean hasLayout = recs.stream().anyMatch(r -> r.tag == 0x01F);
        if (!hasLayout) warn("LAYOUT_COMPATIBILITY(0x01F) 레코드 없음");
        else pass("LAYOUT_COMPATIBILITY 존재");
    }

    // ========== Section0 검증 ==========
    static void validateSection(byte[] data) {
        List<Rec> recs = parseRecords(data);
        pass("레코드 수: " + recs.size());

        if (recs.isEmpty()) { fail("Section 레코드 없음"); return; }

        // 규격 4.3: 첫 레코드는 반드시 PARA_HEADER (0x042) level=0
        Rec first = recs.get(0);
        if (first.tag != 0x042) {
            fail("첫 레코드가 PARA_HEADER(0x042)가 아님: 0x" + Integer.toHexString(first.tag));
        } else if (first.level != 0) {
            fail("첫 PARA_HEADER level=" + first.level + " (규격: 0)");
        } else if (first.size != 24) {
            fail("PARA_HEADER 크기 " + first.size + " (규격: 24)");
        } else {
            pass("첫 PARA_HEADER OK (24바이트, level=0)");
        }

        // 각 문단 구조 검증
        int paraCount = 0;
        int i = 0;
        while (i < recs.size()) {
            Rec r = recs.get(i);
            if (r.tag == 0x042) { // PARA_HEADER
                paraCount++;
                String err = validateParagraph(recs, i);
                if (err != null && paraCount <= 5) {
                    fail("문단 #" + paraCount + " (record " + i + "): " + err);
                }
                // 다음 PARA_HEADER 또는 끝까지 건너뛰기
                i++;
                while (i < recs.size() && recs.get(i).tag != 0x042) i++;
            } else {
                i++;
            }
        }
        pass("총 문단 수: " + paraCount);

        // 첫 문단에 secd CTRL_HEADER 존재 확인
        boolean hasSecd = false;
        for (int j = 1; j < recs.size() && recs.get(j).tag != 0x042; j++) {
            if (recs.get(j).tag == 0x047 && recs.get(j).data.length >= 4) {
                long cid = u32(recs.get(j).data, 0);
                if (cid == 0x73656364L) { // 'secd'
                    hasSecd = true;
                    validateSecdCtrlHeader(recs.get(j));
                    // 하위 레코드 검증
                    for (int k = j+1; k < recs.size() && recs.get(k).level > recs.get(j).level; k++) {
                        Rec sub = recs.get(k);
                        if (sub.tag == 0x049) validatePageDef(sub);
                        if (sub.tag == 0x04A) validateFootnoteShape(sub);
                        if (sub.tag == 0x04B) validatePageBorderFill(sub);
                    }
                    break;
                }
            }
        }
        if (!hasSecd) fail("첫 문단에 secd CTRL_HEADER 없음");

        // TABLE 구조 검증 (첫 테이블만)
        for (int j = 0; j < recs.size(); j++) {
            if (recs.get(j).tag == 0x047 && recs.get(j).data.length >= 4) {
                long cid = u32(recs.get(j).data, 0);
                if (cid == 0x74626C20L) { // 'tbl '
                    validateTableCtrlHeader(recs.get(j));
                    // TABLE 레코드 찾기
                    for (int k = j+1; k < recs.size() && recs.get(k).level > recs.get(j).level; k++) {
                        if (recs.get(k).tag == 0x04D) {
                            validateTableRecord(recs.get(k));
                            break;
                        }
                    }
                    break;
                }
            }
        }

        // LIST_HEADER 크기 검증
        for (Rec r : recs) {
            if (r.tag == 0x048) {
                if (r.size < 6) {
                    fail("LIST_HEADER 크기 " + r.size + " < 최소 6바이트");
                    break;
                }
            }
        }
    }

    // ========== 문단 구조 검증 ==========
    static String validateParagraph(List<Rec> recs, int paraIdx) {
        Rec hdr = recs.get(paraIdx);
        if (hdr.size != 24) return "PARA_HEADER 크기 " + hdr.size + " (규격: 24)";

        long nChars = u32(hdr.data, 0);
        long mask = u32(hdr.data, 4);
        int csCount = u16(hdr.data, 12);
        int rtCount = u16(hdr.data, 14);
        int lsCount = u16(hdr.data, 16);

        // nChars bit 31 마스크
        long realNChars = nChars & 0x7FFFFFFFL;

        // 하위 레코드 찾기
        int level = hdr.level;
        boolean hasText = false, hasCS = false, hasLS = false;
        int textSize = 0;
        for (int j = paraIdx + 1; j < recs.size() && recs.get(j).level > level; j++) {
            Rec sub = recs.get(j);
            if (sub.level == level + 1) {
                if (sub.tag == 0x043) { hasText = true; textSize = sub.size; }
                if (sub.tag == 0x044) hasCS = true;
                if (sub.tag == 0x045) hasLS = true;
            }
        }

        // 규격: nChars > 0이면 PARA_TEXT가 있어야 함
        // 단, nChars=1이고 텍스트가 0x000D만일 때는 PARA_TEXT 생략 가능
        if (realNChars > 1 && !hasText) {
            return "nChars=" + realNChars + "인데 PARA_TEXT 없음";
        }

        // PARA_TEXT가 있으면 크기 = 2 * nChars
        if (hasText && textSize != realNChars * 2) {
            return "PARA_TEXT 크기 " + textSize + " != nChars*2 (" + (realNChars*2) + ")";
        }

        // PARA_CHAR_SHAPE가 있어야 함
        if (!hasCS) {
            return "PARA_CHAR_SHAPE 없음 (csCount=" + csCount + ")";
        }

        return null; // OK
    }

    // ========== secd CTRL_HEADER 검증 ==========
    static void validateSecdCtrlHeader(Rec r) {
        if (r.size < 30) {
            fail("secd CTRL_HEADER 크기 " + r.size + " < 최소 30바이트");
            return;
        }
        // 규격 4.3.10.1: ctrlId(4)+property(4)+colGap(2)+vertGrid(2)+horizGrid(2)+
        // defaultTab(4)+numParaShapeId(2)+pageNum(2)+fig/tbl/eqn(6)+defaultLang(2) = 30
        pass("secd CTRL_HEADER 크기 " + r.size + " OK (>= 30)");
    }

    // ========== PAGE_DEF 검증 (규격 4.3.10.1.1) ==========
    static void validatePageDef(Rec r) {
        if (r.size != 40) {
            fail("PAGE_DEF 크기 " + r.size + " (규격: 40)");
            return;
        }
        int w = i32(r.data, 0);
        int h = i32(r.data, 4);
        if (w <= 0 || h <= 0) {
            fail("PAGE_DEF 용지 크기 비정상: " + w + "x" + h);
        } else {
            pass("PAGE_DEF 용지 " + w + "x" + h + " (" +
                 String.format("%.1f", w/283.46) + "mm x " +
                 String.format("%.1f", h/283.46) + "mm)");
        }
    }

    // ========== FOOTNOTE_SHAPE 검증 (규격 4.3.10.1.2) ==========
    static void validateFootnoteShape(Rec r) {
        // 규격: property(4)+userChar(2)+prefix(2)+suffix(2)+startNum(2)+
        // dividerLength(?) + aboveM(2)+belowM(2)+betweenM(2)+lineType(1)+lineWidth(1)+lineColor(4)
        // 실제 참조에서 28바이트
        if (r.size < 26) {
            fail("FOOTNOTE_SHAPE 크기 " + r.size + " < 최소 26바이트");
        } else {
            pass("FOOTNOTE_SHAPE 크기 " + r.size + " OK");
        }
    }

    // ========== PAGE_BORDER_FILL 검증 (규격 4.3.10.1.3) ==========
    static void validatePageBorderFill(Rec r) {
        if (r.size < 14) {
            fail("PAGE_BORDER_FILL 크기 " + r.size + " < 최소 14바이트");
        } else {
            pass("PAGE_BORDER_FILL 크기 " + r.size + " OK");
        }
    }

    // ========== TABLE CTRL_HEADER 검증 ==========
    static void validateTableCtrlHeader(Rec r) {
        // 규격 4.3.9: ctrlId(4) + 공통 개체 속성 (최소 46바이트)
        if (r.size < 46) {
            fail("TABLE CTRL_HEADER 크기 " + r.size + " < 최소 46바이트");
        } else {
            long cid = u32(r.data, 0);
            long prop = u32(r.data, 4);
            int w = i32(r.data, 16);
            int h = i32(r.data, 20);
            pass("TABLE CTRL_HEADER: prop=0x" + Long.toHexString(prop) +
                 " size=" + w + "x" + h);
        }
    }

    // ========== TABLE 레코드 검증 (규격 4.3.9.1) ==========
    static void validateTableRecord(Rec r) {
        if (r.size < 20) {
            fail("TABLE 레코드 크기 " + r.size + " < 최소 20바이트");
            return;
        }
        long prop = u32(r.data, 0);
        int rows = u16(r.data, 4);
        int cols = u16(r.data, 6);
        int cellSpacing = u16(r.data, 8);
        // 패딩: 8바이트 (offset 10-17)
        // rowHeights: 2*rows 바이트 (offset 18)
        int expectedMinSize = 18 + 2*rows + 4; // +borderFillId(2)+zoneCount(2)
        if (r.size < expectedMinSize) {
            fail("TABLE 레코드 크기 " + r.size + " < 기대 최소 " + expectedMinSize +
                 " (rows=" + rows + " cols=" + cols + ")");
        } else {
            pass("TABLE: " + rows + "x" + cols + " cellSpacing=" + cellSpacing);
        }
    }

    static void checkStreamExists(POIFSFileSystem poi, String... path) {
        try {
            readStream(poi, path);
            pass("스트림 존재: " + String.join("/", path));
        } catch (Exception e) {
            fail("스트림 없음: " + String.join("/", path));
        }
    }
}
