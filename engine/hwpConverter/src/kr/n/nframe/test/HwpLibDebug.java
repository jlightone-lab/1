package kr.n.nframe.test;

import java.lang.reflect.Method;

/**
 * hwplib을 감싸서 파싱이 어디서 실패하는지 상세히 확인한다.
 * 예외를 잡아서 해당 table/cell 컨텍스트를 리포트한다.
 */
public class HwpLibDebug {
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "output/test_hwpx2_conv.hwp";

        try {
            Class<?> readerClass = Class.forName("kr.dogfoot.hwplib.reader.HWPReader");
            Method readMethod = readerClass.getMethod("fromFile", String.class);
            Object hwpFile = readMethod.invoke(null, path);
            System.out.println("SUCCESS: File parsed without errors.");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.out.println("FAILED: " + cause.getMessage());
            // 호출 체인 파악을 위해 전체 스택 트레이스 출력
            StackTraceElement[] st = cause.getStackTrace();
            for (StackTraceElement el : st) {
                if (el.getClassName().startsWith("kr.dogfoot")) {
                    System.out.println("  at " + el);
                }
            }

            // 이제 수동으로 레코드 단위 읽기 시도
            System.out.println("\n=== Manual record-level diagnosis ===");
            manualDiagnosis(path);
        }
    }

    static void manualDiagnosis(String path) throws Exception {
        // 문제 있는 table을 찾기 위해 자체 레코드 파서 사용
        org.apache.poi.poifs.filesystem.POIFSFileSystem poi =
            new org.apache.poi.poifs.filesystem.POIFSFileSystem(new java.io.FileInputStream(path));
        byte[] secRaw = BinaryIsolator.readStream(poi, "BodyText", "Section0");
        byte[] sec = FixedSectionTest.decompress(secRaw);

        int pos = 0;
        int recIdx = 0;
        int tableCount = 0;

        while (pos + 4 <= sec.length) {
            int h = readInt(sec, pos); pos += 4;
            int tag = h & 0x3FF; int lv = (h >> 10) & 0x3FF; int sz = (h >> 20) & 0xFFF;
            if (sz == 0xFFF) { sz = readInt(sec, pos); pos += 4; }

            if (tag == 0x047 && sz >= 4) { // CTRL_HDR
                int ctrlId = readInt(sec, pos);
                if (ctrlId == 0x74626C20) { // 'tbl '
                    tableCount++;
                    // TABLE 레코드를 찾아서 cell 읽기 시뮬레이션
                    int tblLevel = lv;
                    int tblRecIdx = -1;
                    int searchPos = pos + sz;
                    int searchIdx = recIdx + 1;

                    // TABLE 레코드 찾기
                    while (searchPos + 4 <= sec.length) {
                        int sh = readInt(sec, searchPos); searchPos += 4;
                        int stag = sh & 0x3FF; int slv = (sh >> 10) & 0x3FF; int ssz = (sh >> 20) & 0xFFF;
                        if (ssz == 0xFFF) { ssz = readInt(sec, searchPos); searchPos += 4; }

                        if (stag == 0x04D && slv == tblLevel + 1) {
                            // TABLE 레코드 발견
                            int rows = readShort(sec, searchPos + 4);
                            int cols = readShort(sec, searchPos + 6);
                            int expectedCells = rows * cols;

                            // tblLevel+1의 LIST_HDR 개수 세기
                            int listHdrs = 0;
                            int cellSearchPos = searchPos + ssz;
                            int cellSearchIdx = searchIdx + 1;
                            while (cellSearchPos + 4 <= sec.length) {
                                int ch = readInt(sec, cellSearchPos);
                                int ctag = ch & 0x3FF; int clv = (ch >> 10) & 0x3FF; int csz = (ch >> 20) & 0xFFF;
                                cellSearchPos += 4;
                                if (csz == 0xFFF) { csz = readInt(sec, cellSearchPos); cellSearchPos += 4; }

                                if (ctag == 0x042 && clv <= tblLevel) break; // 다음 최상위 paragraph
                                if (ctag == 0x047 && clv <= tblLevel) break;
                                if (ctag == 0x048 && clv == tblLevel + 1) {
                                    listHdrs++;
                                    // paraCount 확인
                                    int paraCount = readShort(sec, cellSearchPos);
                                    // paraCount개 paragraph 읽기 시뮬레이션
                                    int pPos = cellSearchPos + csz;
                                    for (int p = 0; p < paraCount; p++) {
                                        if (pPos + 4 > sec.length) {
                                            System.out.println("  TABLE #" + tableCount + " cell #" + listHdrs +
                                                ": TRUNCATED at paragraph " + p + "/" + paraCount);
                                            break;
                                        }
                                        int ph = readInt(sec, pPos);
                                        int ptag = ph & 0x3FF;
                                        if (ptag != 0x042) {
                                            String nm = ptag == 0x043 ? "PARA_TXT" : ptag == 0x044 ? "PARA_CS" :
                                                ptag == 0x045 ? "PARA_LS" : ptag == 0x047 ? "CTRL_HDR" :
                                                ptag == 0x048 ? "LIST_HDR" : String.format("0x%03X", ptag);
                                            System.out.println("  TABLE #" + tableCount + " (rec " + recIdx +
                                                ") rows=" + rows + "x" + cols +
                                                " cell #" + listHdrs + " paraCount=" + paraCount +
                                                ": at para " + p + " found " + nm + " instead of PARA_HDR");
                                            poi.close();
                                            return;
                                        }
                                        // 이 paragraph와 자식 레코드 모두 건너뛰기
                                        int plv = (ph >> 10) & 0x3FF;
                                        int psz = (ph >> 20) & 0xFFF;
                                        pPos += 4;
                                        if (psz == 0xFFF) { psz = readInt(sec, pPos); pPos += 4; }
                                        pPos += psz;
                                        // 자식 레코드 건너뛰기
                                        while (pPos + 4 <= sec.length) {
                                            int childH = readInt(sec, pPos);
                                            int childLv = (childH >> 10) & 0x3FF;
                                            if (childLv <= plv) break;
                                            int childSz = (childH >> 20) & 0xFFF;
                                            pPos += 4;
                                            if (childSz == 0xFFF) { childSz = readInt(sec, pPos); pPos += 4; }
                                            pPos += childSz;
                                        }
                                    }
                                }
                                cellSearchPos += csz;
                                cellSearchIdx++;
                            }
                            break;
                        }
                        searchPos += ssz;
                        searchIdx++;
                    }
                }
            }

            pos += sz;
            recIdx++;
        }

        System.out.println("Scanned " + tableCount + " tables without finding the error.");
        System.out.println("The error may be in nested table or different parsing logic.");
        poi.close();
    }

    static int readInt(byte[] d, int o) {
        return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24);
    }
    static int readShort(byte[] d, int o) {
        return (d[o]&0xFF)|((d[o+1]&0xFF)<<8);
    }
}
