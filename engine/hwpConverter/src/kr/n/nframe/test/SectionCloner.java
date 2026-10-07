package kr.n.nframe.test;

import kr.n.nframe.hwplib.binary.ZlibCompressor;
import org.apache.poi.poifs.filesystem.*;

import java.io.*;
import java.util.*;

/**
 * Section0 복제 및 비교 도구.
 *
 * Phase 1: 레퍼런스 HWP를 읽어 Section0을 압축 해제하고, 모든 Record를 raw byte로 파싱한 뒤
 *          다시 연결하여 byte 단위로 완전히 동일한지 검증한다. 이후 레퍼런스의
 *          FileHeader/DocInfo/BinData를 사용하여 압축 후 복제된 HWP 파일로 기록한다.
 *
 * Phase 2: HWPX 변환 파이프라인을 실행하여 자체 생성한 Section0 byte를 얻는다. 모든 Record를
 *          레퍼런스와 비교하여 byte 수준의 차이를 보고한다. 자체 Record가 다른 위치에는
 *          레퍼런스 Record를, 일치하는 위치에는 자체 Record를 사용하여 하이브리드 파일을
 *          생성한다.
 */
public class SectionCloner {

    // -----------------------------------------------------------------------
    //  Tag 이름 조회 (RecordCompareTest와 동일)
    // -----------------------------------------------------------------------
    static final Map<Integer, String> TAG_NAMES = new LinkedHashMap<>();
    static {
        TAG_NAMES.put(0x042, "PARA_HDR");    TAG_NAMES.put(0x043, "PARA_TXT");
        TAG_NAMES.put(0x044, "PARA_CS");     TAG_NAMES.put(0x045, "PARA_LS");
        TAG_NAMES.put(0x046, "RANGE_TAG");   TAG_NAMES.put(0x047, "CTRL_HDR");
        TAG_NAMES.put(0x048, "LIST_HDR");    TAG_NAMES.put(0x049, "PAGE_DEF");
        TAG_NAMES.put(0x04A, "FN_SHAPE");    TAG_NAMES.put(0x04B, "PG_BF");
        TAG_NAMES.put(0x04C, "SHAPE_CMP");   TAG_NAMES.put(0x04D, "TABLE");
        TAG_NAMES.put(0x055, "PICTURE");     TAG_NAMES.put(0x057, "CTRL_DATA");
    }

    static String tagName(int tag) {
        String n = TAG_NAMES.get(tag);
        return n != null ? n : String.format("0x%03X", tag);
    }

    // -----------------------------------------------------------------------
    //  Record: 파싱된 헤더 + raw byte (헤더 + 선택적 확장 size + payload)
    // -----------------------------------------------------------------------
    static class Rec {
        final int index;      // 스트림 내 순차 인덱스
        final int tag;        // 10-bit tag ID
        final int level;      // 10-bit 중첩 레벨
        final int dataSize;   // payload 크기 (헤더 제외)
        final byte[] raw;     // 전체 record byte: 헤더 [+ 확장 size] + payload
        final byte[] payload; // payload만

        Rec(int index, int tag, int level, int dataSize, byte[] raw, byte[] payload) {
            this.index = index; this.tag = tag; this.level = level;
            this.dataSize = dataSize; this.raw = raw; this.payload = payload;
        }
    }

    // -----------------------------------------------------------------------
    //  Record 파싱: raw byte가 정확히 보존된 Rec 리스트를 반환
    // -----------------------------------------------------------------------
    static List<Rec> parseRecords(byte[] data) {
        List<Rec> recs = new ArrayList<>();
        int pos = 0;
        int idx = 0;
        while (pos + 4 <= data.length) {
            int recStart = pos;
            int h = readInt32LE(data, pos); pos += 4;
            int tag   = h & 0x3FF;
            int level = (h >> 10) & 0x3FF;
            int size  = (h >> 20) & 0xFFF;
            if (size == 0xFFF) {
                if (pos + 4 > data.length) break;
                size = readInt32LE(data, pos); pos += 4;
            }
            if (pos + size > data.length) {
                System.err.println("WARNING: record " + idx + " truncated at offset " + recStart);
                break;
            }
            byte[] payload = Arrays.copyOfRange(data, pos, pos + size);
            pos += size;
            byte[] raw = Arrays.copyOfRange(data, recStart, pos);
            recs.add(new Rec(idx, tag, level, size, raw, payload));
            idx++;
        }
        return recs;
    }

    // -----------------------------------------------------------------------
    //  raw record byte로부터 Section0 재구성
    // -----------------------------------------------------------------------
    static byte[] rebuildFromRaw(List<Rec> recs) {
        int total = 0;
        for (Rec r : recs) total += r.raw.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (Rec r : recs) {
            System.arraycopy(r.raw, 0, out, pos, r.raw.length);
            pos += r.raw.length;
        }
        return out;
    }

    // -----------------------------------------------------------------------
    //  OLE2 스트림 읽기 헬퍼 (BinaryIsolator 패턴 재사용)
    // -----------------------------------------------------------------------
    static byte[] readStream(POIFSFileSystem poi, String... path) throws IOException {
        DirectoryEntry dir = poi.getRoot();
        for (int i = 0; i < path.length - 1; i++) {
            dir = (DirectoryEntry) dir.getEntry(path[i]);
        }
        DocumentEntry doc = (DocumentEntry) dir.getEntry(path[path.length - 1]);
        byte[] data = new byte[doc.getSize()];
        try (InputStream is = new DocumentInputStream(doc)) {
            int t = 0;
            while (t < data.length) {
                int r = is.read(data, t, data.length - t);
                if (r < 0) break;
                t += r;
            }
        }
        return data;
    }

    // -----------------------------------------------------------------------
    //  레퍼런스 OLE2 구조를 사용하여 출력 HWP 생성
    // -----------------------------------------------------------------------
    static void buildHwp(String path, POIFSFileSystem refPoi,
                         byte[] fileHeader, byte[] docInfo, byte[] compressedSection)
            throws Exception {
        POIFSFileSystem newPoi = new POIFSFileSystem();
        DirectoryEntry root = newPoi.getRoot();

        root.createDocument("FileHeader", new ByteArrayInputStream(fileHeader));
        root.createDocument("DocInfo", new ByteArrayInputStream(docInfo));

        DirectoryEntry bt = root.createDirectory("BodyText");
        bt.createDocument("Section0", new ByteArrayInputStream(compressedSection));

        // 레퍼런스에서 보조 디렉터리/스트림 복사
        copyDir(refPoi.getRoot(), root, "BinData");
        copyDir(refPoi.getRoot(), root, "Scripts");
        copyDir(refPoi.getRoot(), root, "DocOptions");
        copyStream(refPoi.getRoot(), root, "PrvText");
        copyStream(refPoi.getRoot(), root, "PrvImage");
        for (Entry entry : refPoi.getRoot()) {
            if (entry.getName().contains("Summary") && entry.isDocumentEntry()) {
                byte[] data = readStream(refPoi, entry.getName());
                root.createDocument(entry.getName(), new ByteArrayInputStream(data));
            }
        }

        try (OutputStream fos = new FileOutputStream(path)) {
            newPoi.writeFilesystem(fos);
        }
        newPoi.close();
    }

    static void copyDir(DirectoryEntry src, DirectoryEntry dst, String name) {
        try {
            DirectoryEntry srcDir = (DirectoryEntry) src.getEntry(name);
            DirectoryEntry dstDir = dst.createDirectory(name);
            for (Entry entry : srcDir) {
                if (entry.isDocumentEntry()) {
                    DocumentEntry doc = (DocumentEntry) entry;
                    byte[] data = new byte[doc.getSize()];
                    try (InputStream is = new DocumentInputStream(doc)) {
                        int t = 0;
                        while (t < data.length) {
                            int r = is.read(data, t, data.length - t);
                            if (r < 0) break;
                            t += r;
                        }
                    }
                    dstDir.createDocument(entry.getName(), new ByteArrayInputStream(data));
                }
            }
        } catch (Exception e) { /* 디렉터리가 없으면 건너뜀 */ }
    }

    static void copyStream(DirectoryEntry src, DirectoryEntry dst, String name) {
        try {
            DocumentEntry doc = (DocumentEntry) src.getEntry(name);
            byte[] data = new byte[doc.getSize()];
            try (InputStream is = new DocumentInputStream(doc)) {
                int t = 0;
                while (t < data.length) {
                    int r = is.read(data, t, data.length - t);
                    if (r < 0) break;
                    t += r;
                }
            }
            dst.createDocument(name, new ByteArrayInputStream(data));
        } catch (Exception e) { /* 스트림이 없으면 건너뜀 */ }
    }

    // -----------------------------------------------------------------------
    //  유틸리티
    // -----------------------------------------------------------------------
    static int readInt32LE(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8)
                | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }

    static String hex(byte[] d, int off, int len) {
        StringBuilder sb = new StringBuilder();
        int end = Math.min(d.length, off + len);
        for (int i = off; i < end; i++) {
            sb.append(String.format("%02x", d[i] & 0xFF));
        }
        return sb.toString();
    }

    static int findFirstDiff(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            if (a[i] != b[i]) return i;
        }
        return a.length != b.length ? len : -1;
    }

    /**
     * 일반적인 Record 타입에 대해 payload 내 byte offset을 설명한다.
     * 알려진 경우 사람이 읽기 쉬운 필드 이름을 반환한다.
     */
    static String describeField(int tag, int byteOffset) {
        switch (tag) {
            case 0x042: // PARA_HDR (22 byte)
                if (byteOffset < 4)  return "nChars";
                if (byteOffset < 8)  return "controlMask";
                if (byteOffset < 10) return "paraShapeId";
                if (byteOffset < 11) return "styleId";
                if (byteOffset < 12) return "columnBreakType";
                if (byteOffset < 14) return "charShapeCount";
                if (byteOffset < 16) return "rangeTagCount";
                if (byteOffset < 18) return "lineSegCount";
                if (byteOffset < 22) return "instanceId";
                return "extra[" + byteOffset + "]";

            case 0x043: // PARA_TXT (raw UTF-16LE 텍스트)
                return "text@" + byteOffset;

            case 0x044: // PARA_CS (8-byte 쌍: position + charShapeId)
                int csIdx = byteOffset / 8;
                int csOff = byteOffset % 8;
                return "entry[" + csIdx + "]." + (csOff < 4 ? "position" : "charShapeId");

            case 0x045: // PARA_LS (36-byte 엔트리)
                int lsIdx = byteOffset / 36;
                int lsOff = byteOffset % 36;
                String[] lsFields = {"textStartPos", "lineVertPos", "lineHeight", "textHeight",
                        "baselineDist", "lineSpacing", "colStartPos", "segWidth", "tag"};
                int fi = lsOff / 4;
                return "entry[" + lsIdx + "]." + (fi < lsFields.length ? lsFields[fi] : "?");

            case 0x047: // CTRL_HDR
                if (byteOffset < 4) return "ctrlId";
                // ctrlId 이후 필드는 컨트롤 타입에 따라 달라짐
                return "field@" + byteOffset;

            case 0x048: // LIST_HDR
                if (byteOffset < 2) return "paraCount";
                if (byteOffset < 6) return "listProperty";
                if (byteOffset < 8) return "padding";
                return "cellField@" + (byteOffset - 8);

            case 0x049: // PAGE_DEF (40 byte)
                String[] pdFields = {"paperWidth", "paperHeight", "leftMargin", "rightMargin",
                        "topMargin", "bottomMargin", "headerMargin", "footerMargin",
                        "gutterMargin", "property"};
                int pdIdx = byteOffset / 4;
                return pdIdx < pdFields.length ? pdFields[pdIdx] : "extra@" + byteOffset;

            case 0x04A: // FOOTNOTE_SHAPE (28 byte)
                if (byteOffset < 4)  return "property";
                if (byteOffset < 6)  return "userSymbol";
                if (byteOffset < 8)  return "prefixChar";
                if (byteOffset < 10) return "suffixChar";
                if (byteOffset < 12) return "startNumber";
                if (byteOffset < 16) return "dividerLength";
                if (byteOffset < 18) return "divAboveMargin";
                if (byteOffset < 20) return "divBelowMargin";
                if (byteOffset < 22) return "noteBetweenMargin";
                if (byteOffset < 23) return "divLineType";
                if (byteOffset < 24) return "divLineWidth";
                return "divLineColor";

            case 0x04B: // PAGE_BORDER_FILL (14 byte)
                if (byteOffset < 4) return "property";
                if (byteOffset < 6) return "padLeft";
                if (byteOffset < 8) return "padRight";
                if (byteOffset < 10) return "padTop";
                if (byteOffset < 12) return "padBottom";
                return "borderFillId";

            case 0x04D: // TABLE
                if (byteOffset < 4)  return "property";
                if (byteOffset < 6)  return "rowCount";
                if (byteOffset < 8)  return "colCount";
                if (byteOffset < 10) return "cellSpacing";
                if (byteOffset < 12) return "padLeft";
                if (byteOffset < 14) return "padRight";
                if (byteOffset < 16) return "padTop";
                if (byteOffset < 18) return "padBottom";
                return "rowHeights/borderFillId/zoneCount@" + byteOffset;

            case 0x04C: // SHAPE_COMPONENT
                if (byteOffset < 4)  return "ctrlId1";
                if (byteOffset < 8)  return "ctrlId2";
                if (byteOffset < 12) return "xOffset";
                if (byteOffset < 16) return "yOffset";
                if (byteOffset < 18) return "groupLevel";
                if (byteOffset < 20) return "localVersion";
                if (byteOffset < 24) return "initialWidth";
                if (byteOffset < 28) return "initialHeight";
                if (byteOffset < 32) return "currentWidth";
                if (byteOffset < 36) return "currentHeight";
                return "transform@" + byteOffset;

            case 0x055: // SHAPE_COMPONENT_PICTURE
                if (byteOffset < 4)  return "borderColor";
                if (byteOffset < 8)  return "borderThickness";
                if (byteOffset < 12) return "borderProperty";
                if (byteOffset < 28) return "imageRectX";
                if (byteOffset < 44) return "imageRectY";
                if (byteOffset < 48) return "cropLeft";
                if (byteOffset < 52) return "cropTop";
                if (byteOffset < 56) return "cropRight";
                if (byteOffset < 60) return "cropBottom";
                return "imageData@" + byteOffset;

            default:
                return "byte@" + byteOffset;
        }
    }

    // =======================================================================
    //  MAIN
    // =======================================================================
    public static void main(String[] args) throws Exception {
        String refPath = "hwp/test_hwpx2_conv.hwp";
        String clonedPath = "output/cloned_section.hwp";
        String ourPath = "output/test_hwpx2_conv.hwp";
        String hybridPath = "output/hybrid_section.hwp";

        // 출력 디렉터리 존재 확인
        new File("output").mkdirs();

        // ==================================================================
        //  PHASE 1: Section0을 byte 단위로 복제
        // ==================================================================
        System.out.println("============================================================");
        System.out.println("  PHASE 1: Clone reference Section0 and verify identity");
        System.out.println("============================================================");

        POIFSFileSystem refPoi = new POIFSFileSystem(new FileInputStream(refPath));
        byte[] refFH  = readStream(refPoi, "FileHeader");
        byte[] refDI  = readStream(refPoi, "DocInfo");
        byte[] refSecCompressed = readStream(refPoi, "BodyText", "Section0");

        byte[] refSecRaw = ZlibCompressor.decompress(refSecCompressed);
        System.out.println("Reference Section0: " + refSecCompressed.length
                + " compressed -> " + refSecRaw.length + " decompressed");

        List<Rec> refRecs = parseRecords(refSecRaw);
        System.out.println("Parsed " + refRecs.size() + " records from reference Section0");

        // Record 요약 출력
        Map<Integer, Integer> tagCounts = new LinkedHashMap<>();
        for (Rec r : refRecs) {
            tagCounts.merge(r.tag, 1, Integer::sum);
        }
        System.out.println("\nRecord type summary:");
        for (Map.Entry<Integer, Integer> e : tagCounts.entrySet()) {
            System.out.printf("  %-12s (0x%03X): %d records%n",
                    tagName(e.getKey()), e.getKey(), e.getValue());
        }

        // raw byte로부터 재구성
        byte[] rebuilt = rebuildFromRaw(refRecs);
        System.out.println("\nRebuilt section: " + rebuilt.length + " bytes");

        // byte 단위 동일성 검증
        int diff = findFirstDiff(refSecRaw, rebuilt);
        if (diff < 0) {
            System.out.println("CLONE VERIFICATION: PASS - rebuilt bytes are IDENTICAL to original");
        } else {
            System.out.println("CLONE VERIFICATION: FAIL - first difference at byte " + diff);
            System.out.println("  ref: " + hex(refSecRaw, Math.max(0, diff - 4), 16));
            System.out.println("  reb: " + hex(rebuilt, Math.max(0, diff - 4), 16));
            // 어떻든 파일 생성은 계속 진행
        }

        // 압축하여 복제 HWP 기록
        byte[] clonedCompressed = ZlibCompressor.compress(rebuilt);
        buildHwp(clonedPath, refPoi, refFH, refDI, clonedCompressed);
        System.out.println("\nWrote cloned file: " + clonedPath);

        // 복제된 압축 데이터의 왕복 변환이 정확한지 검증
        byte[] clonedDecomp = ZlibCompressor.decompress(clonedCompressed);
        int rtDiff = findFirstDiff(rebuilt, clonedDecomp);
        if (rtDiff < 0) {
            System.out.println("Compress/decompress round-trip: PASS");
        } else {
            System.out.println("Compress/decompress round-trip: FAIL at byte " + rtDiff);
        }

        // ==================================================================
        //  PHASE 2: 자체 Section0과 레퍼런스를 비교하여 하이브리드 생성
        // ==================================================================
        System.out.println("\n============================================================");
        System.out.println("  PHASE 2: Compare our Section0 records vs reference");
        System.out.println("============================================================");

        File ourFile = new File(ourPath);
        if (!ourFile.exists()) {
            System.out.println("Our output file not found at: " + ourPath);
            System.out.println("Run HwpConverter first to generate it, then re-run this tool.");
            System.out.println("Skipping Phase 2.");
            refPoi.close();
            return;
        }

        POIFSFileSystem ourPoi = new POIFSFileSystem(new FileInputStream(ourPath));
        byte[] ourSecCompressed = readStream(ourPoi, "BodyText", "Section0");
        byte[] ourSecRaw = ZlibCompressor.decompress(ourSecCompressed);
        System.out.println("Our Section0: " + ourSecCompressed.length
                + " compressed -> " + ourSecRaw.length + " decompressed");

        List<Rec> ourRecs = parseRecords(ourSecRaw);
        System.out.println("Parsed " + ourRecs.size() + " records from our Section0");

        // Record 개수 비교
        System.out.println("\nRecord count comparison:");
        Map<Integer, Integer> ourTagCounts = new LinkedHashMap<>();
        for (Rec r : ourRecs) {
            ourTagCounts.merge(r.tag, 1, Integer::sum);
        }
        Set<Integer> allTags = new TreeSet<>(tagCounts.keySet());
        allTags.addAll(ourTagCounts.keySet());
        for (int tag : allTags) {
            int rc = tagCounts.getOrDefault(tag, 0);
            int oc = ourTagCounts.getOrDefault(tag, 0);
            String status = rc == oc ? "OK" : "MISMATCH";
            System.out.printf("  %-12s: ref=%d  ours=%d  %s%n", tagName(tag), rc, oc, status);
        }

        // ==================================================================
        //  Record별 순차 비교
        // ==================================================================
        System.out.println("\n--- Sequential record-by-record comparison ---");
        int maxComp = Math.min(refRecs.size(), ourRecs.size());
        int identicalCount = 0;
        int differentCount = 0;
        List<Integer> identicalIndices = new ArrayList<>();
        List<Integer> differentIndices = new ArrayList<>();

        for (int i = 0; i < maxComp; i++) {
            Rec ref = refRecs.get(i);
            Rec our = ourRecs.get(i);

            boolean tagMatch = ref.tag == our.tag && ref.level == our.level;
            boolean dataMatch = Arrays.equals(ref.raw, our.raw);

            if (dataMatch) {
                identicalCount++;
                identicalIndices.add(i);
            } else {
                differentCount++;
                differentIndices.add(i);

                // 상세 보고
                System.out.printf("[%3d] DIFF  %-12s lv%d", i, tagName(ref.tag), ref.level);
                if (!tagMatch) {
                    System.out.printf("  TAG_MISMATCH: ref=%s/lv%d  ours=%s/lv%d",
                            tagName(ref.tag), ref.level, tagName(our.tag), our.level);
                }
                System.out.println();

                if (tagMatch) {
                    // tag는 같지만 데이터가 다름 - byte 수준 diff 표시
                    if (ref.dataSize != our.dataSize) {
                        System.out.printf("       Size: ref=%d  ours=%d%n", ref.dataSize, our.dataSize);
                    }
                    int bd = findFirstDiff(ref.payload, our.payload);
                    if (bd >= 0) {
                        String field = describeField(ref.tag, bd);
                        int refVal = 0, ourVal = 0;
                        // 값 표시용으로 diff offset에서 최대 4 byte 읽기
                        if (bd < ref.payload.length) {
                            refVal = ref.payload[bd] & 0xFF;
                            if (bd + 1 < ref.payload.length) refVal |= (ref.payload[bd + 1] & 0xFF) << 8;
                            if (bd + 2 < ref.payload.length) refVal |= (ref.payload[bd + 2] & 0xFF) << 16;
                            if (bd + 3 < ref.payload.length) refVal |= (ref.payload[bd + 3] & 0xFF) << 24;
                        }
                        if (bd < our.payload.length) {
                            ourVal = our.payload[bd] & 0xFF;
                            if (bd + 1 < our.payload.length) ourVal |= (our.payload[bd + 1] & 0xFF) << 8;
                            if (bd + 2 < our.payload.length) ourVal |= (our.payload[bd + 2] & 0xFF) << 16;
                            if (bd + 3 < our.payload.length) ourVal |= (our.payload[bd + 3] & 0xFF) << 24;
                        }
                        System.out.printf("       FirstDiff@byte%d field=%s ref=0x%08X ours=0x%08X%n",
                                bd, field, refVal, ourVal);

                        // 차이 부분 주변의 hex 컨텍스트 표시
                        int ctxStart = Math.max(0, bd - 4);
                        int ctxLen = 20;
                        System.out.printf("       ref[%d..]: %s%n", ctxStart,
                                hex(ref.payload, ctxStart, ctxLen));
                        System.out.printf("       our[%d..]: %s%n", ctxStart,
                                hex(our.payload, ctxStart, ctxLen));
                    }
                } else {
                    // tag가 다름 - 둘 다 raw hex 표시
                    System.out.printf("       ref_raw: %s%n", hex(ref.raw, 0, 30));
                    System.out.printf("       our_raw: %s%n", hex(our.raw, 0, 30));
                }
            }
        }

        // 최소 개수를 초과하는 Record 보고
        if (refRecs.size() > maxComp) {
            System.out.println("\nReference has " + (refRecs.size() - maxComp)
                    + " extra records beyond our count:");
            for (int i = maxComp; i < Math.min(refRecs.size(), maxComp + 10); i++) {
                Rec r = refRecs.get(i);
                System.out.printf("  [%3d] %s lv%d sz%d%n", i, tagName(r.tag), r.level, r.dataSize);
            }
        }
        if (ourRecs.size() > maxComp) {
            System.out.println("\nOurs has " + (ourRecs.size() - maxComp)
                    + " extra records beyond reference count:");
            for (int i = maxComp; i < Math.min(ourRecs.size(), maxComp + 10); i++) {
                Rec r = ourRecs.get(i);
                System.out.printf("  [%3d] %s lv%d sz%d%n", i, tagName(r.tag), r.level, r.dataSize);
            }
        }

        System.out.printf("%nSummary: %d identical, %d different (of %d compared)%n",
                identicalCount, differentCount, maxComp);
        if (refRecs.size() != ourRecs.size()) {
            System.out.printf("Record count: ref=%d  ours=%d (count mismatch!)%n",
                    refRecs.size(), ourRecs.size());
        }

        // ==================================================================
        //  PHASE 3: 하이브리드 파일 생성
        //  레퍼런스를 기준으로 사용한다. 각 Record 위치에서 자체 Record가
        //  레퍼런스와 IDENTICAL한 경우 자체 Record를 사용하고, 다른 경우에는
        //  레퍼런스 Record를 사용한다. 이를 통해 유효한 출력을 보장한다.
        // ==================================================================
        System.out.println("\n============================================================");
        System.out.println("  PHASE 3: Build hybrid file");
        System.out.println("============================================================");

        // 모든 레퍼런스 Record를 기준으로 사용한다 (Record 개수가 다를 수 있음).
        // 자체 Record가 일치하는 위치에서는 자체 것으로 교체한다 (IDENTICAL이므로 실질적 변경 없음).
        // 하이브리드: 기본적으로 레퍼런스 Record를 사용하고, IDENTICAL한 위치에만 자체 것을 사용.
        // Record 개수가 다르면 초과분은 레퍼런스에서 가져온다.

        ByteArrayOutputStream hybridOut = new ByteArrayOutputStream(refSecRaw.length);
        int usedOurs = 0;
        int usedRef = 0;

        for (int i = 0; i < refRecs.size(); i++) {
            if (i < ourRecs.size() && identicalIndices.contains(i)) {
                // 자체 Record가 IDENTICAL - 사용 (자체 Writer가 이를 생성할 수 있음을 증명)
                hybridOut.write(ourRecs.get(i).raw);
                usedOurs++;
            } else {
                // 다르거나 대응 Record가 없음 - 레퍼런스 사용
                hybridOut.write(refRecs.get(i).raw);
                usedRef++;
            }
        }

        byte[] hybridSecRaw = hybridOut.toByteArray();

        // 하이브리드가 레퍼런스와 IDENTICAL한지 검증 (IDENTICAL Record는 byte-equal이고
        // 다른 Record는 레퍼런스에서 오므로 반드시 IDENTICAL이어야 함)
        int hybridDiff = findFirstDiff(refSecRaw, hybridSecRaw);
        if (hybridDiff < 0) {
            System.out.println("Hybrid section is IDENTICAL to reference (expected)");
        } else {
            System.out.println("WARNING: Hybrid differs from reference at byte " + hybridDiff);
        }

        byte[] hybridCompressed = ZlibCompressor.compress(hybridSecRaw);
        buildHwp(hybridPath, refPoi, refFH, refDI, hybridCompressed);
        System.out.printf("Wrote hybrid file: %s%n", hybridPath);
        System.out.printf("  Records from ours (identical): %d%n", usedOurs);
        System.out.printf("  Records from reference (different): %d%n", usedRef);

        // ==================================================================
        //  요약: 자체 출력에서 어떤 Record 타입이 완전히 정확한가?
        // ==================================================================
        System.out.println("\n============================================================");
        System.out.println("  SUMMARY: Per-type correctness");
        System.out.println("============================================================");

        Map<Integer, int[]> perType = new LinkedHashMap<>(); // tag -> [IDENTICAL 개수, 다른 개수]
        for (int i = 0; i < maxComp; i++) {
            Rec ref = refRecs.get(i);
            int tag = ref.tag;
            int[] counts = perType.computeIfAbsent(tag, k -> new int[2]);
            if (identicalIndices.contains(i)) {
                counts[0]++;
            } else {
                counts[1]++;
            }
        }

        for (Map.Entry<Integer, int[]> e : perType.entrySet()) {
            int tag = e.getKey();
            int ok = e.getValue()[0];
            int bad = e.getValue()[1];
            String status = bad == 0 ? "ALL CORRECT" : bad + " DIFFER";
            System.out.printf("  %-12s: %d/%d identical  [%s]%n",
                    tagName(tag), ok, ok + bad, status);
        }

        ourPoi.close();
        refPoi.close();

        System.out.println("\nDone. Open " + clonedPath + " in Hangul to verify clone works.");
        System.out.println("Open " + hybridPath + " in Hangul to verify hybrid works.");
    }
}
