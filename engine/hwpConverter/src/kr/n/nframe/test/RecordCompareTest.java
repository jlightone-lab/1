package kr.n.nframe.test;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.poifs.filesystem.*;

/**
 * 레퍼런스 파일과 출력 파일 간 HWP Record의 byte 수준 비교.
 * 어떤 Record가 어떻게 다른지 정확히 식별한다.
 */
public class RecordCompareTest {

    static class Record {
        int tag, level, size, offset;
        byte[] data;
        Record(int tag, int level, int size, byte[] data, int offset) {
            this.tag = tag; this.level = level; this.size = size;
            this.data = data; this.offset = offset;
        }
    }

    static List<Record> parseRecords(byte[] raw) {
        List<Record> recs = new ArrayList<>();
        int pos = 0;
        while (pos + 4 <= raw.length) {
            int hdr = (raw[pos]&0xFF) | ((raw[pos+1]&0xFF)<<8) | ((raw[pos+2]&0xFF)<<16) | ((raw[pos+3]&0xFF)<<24);
            int off = pos; pos += 4;
            int tag = hdr & 0x3FF, level = (hdr>>10) & 0x3FF, size = (hdr>>20) & 0xFFF;
            if (size == 0xFFF) {
                size = (raw[pos]&0xFF)|((raw[pos+1]&0xFF)<<8)|((raw[pos+2]&0xFF)<<16)|((raw[pos+3]&0xFF)<<24);
                pos += 4;
            }
            byte[] data = new byte[Math.min(size, raw.length - pos)];
            System.arraycopy(raw, pos, data, 0, data.length);
            recs.add(new Record(tag, level, size, data, off));
            pos += size;
        }
        return recs;
    }

    static byte[] readStream(POIFSFileSystem poi, String... path) throws IOException {
        DirectoryEntry dir = poi.getRoot();
        for (int i = 0; i < path.length - 1; i++) dir = (DirectoryEntry) dir.getEntry(path[i]);
        DocumentEntry doc = (DocumentEntry) dir.getEntry(path[path.length - 1]);
        byte[] data = new byte[doc.getSize()];
        try (InputStream is = new DocumentInputStream(doc)) {
            int t = 0; while (t < data.length) { int r = is.read(data, t, data.length-t); if (r<0) break; t+=r; }
        }
        return data;
    }

    static byte[] decompress(byte[] raw) {
        try {
            Inflater inf = new Inflater(true);
            inf.setInput(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length * 4);
            byte[] buf = new byte[8192];
            while (!inf.finished()) { int n = inf.inflate(buf); out.write(buf, 0, n); }
            inf.end();
            return out.toByteArray();
        } catch (Exception e) { return raw; }
    }

    static final Map<Integer,String> TAG_NAMES = new HashMap<>();
    static {
        TAG_NAMES.put(0x010,"DOC_PROPS"); TAG_NAMES.put(0x011,"ID_MAPS");
        TAG_NAMES.put(0x012,"BIN_DATA"); TAG_NAMES.put(0x013,"FACE_NAME");
        TAG_NAMES.put(0x014,"BORDER_FILL"); TAG_NAMES.put(0x015,"CHAR_SHAPE");
        TAG_NAMES.put(0x016,"TAB_DEF"); TAG_NAMES.put(0x017,"NUMBERING");
        TAG_NAMES.put(0x018,"BULLET"); TAG_NAMES.put(0x019,"PARA_SHAPE");
        TAG_NAMES.put(0x01A,"STYLE"); TAG_NAMES.put(0x01B,"DOC_DATA");
        TAG_NAMES.put(0x01E,"COMPAT_DOC"); TAG_NAMES.put(0x01F,"LAYOUT_COMPAT");
        TAG_NAMES.put(0x020,"TRACK_CHG"); TAG_NAMES.put(0x05E,"FORBIDDEN");
        TAG_NAMES.put(0x042,"PARA_HDR"); TAG_NAMES.put(0x043,"PARA_TXT");
        TAG_NAMES.put(0x044,"PARA_CS"); TAG_NAMES.put(0x045,"PARA_LS");
        TAG_NAMES.put(0x046,"RANGE_TAG"); TAG_NAMES.put(0x047,"CTRL_HDR");
        TAG_NAMES.put(0x048,"LIST_HDR"); TAG_NAMES.put(0x049,"PAGE_DEF");
        TAG_NAMES.put(0x04A,"FN_SHAPE"); TAG_NAMES.put(0x04B,"PG_BF");
        TAG_NAMES.put(0x04C,"SHAPE_CMP"); TAG_NAMES.put(0x04D,"TABLE");
        TAG_NAMES.put(0x055,"PICTURE"); TAG_NAMES.put(0x057,"CTRL_DATA");
    }

    static String tagName(int tag) {
        String n = TAG_NAMES.get(tag);
        return n != null ? n : String.format("0x%03X", tag);
    }

    static String hex(byte[] d, int maxLen) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(d.length, maxLen); i++) sb.append(String.format("%02x", d[i]&0xFF));
        if (d.length > maxLen) sb.append("...");
        return sb.toString();
    }

    static int findFirstDiff(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) { if (a[i] != b[i]) return i; }
        return a.length != b.length ? len : -1;
    }

    public static void main(String[] args) throws Exception {
        String refPath = args.length > 0 ? args[0] : "hwp/test_hwpx2_conv.hwp";
        String outPath = args.length > 1 ? args[1] : "output/test_hwpx2_conv.hwp";

        POIFSFileSystem refPoi = new POIFSFileSystem(new FileInputStream(refPath));
        POIFSFileSystem outPoi = new POIFSFileSystem(new FileInputStream(outPath));

        System.out.println("========== FileHeader Compare ==========");
        byte[] refFH = readStream(refPoi, "FileHeader");
        byte[] outFH = readStream(outPoi, "FileHeader");
        int fhDiff = findFirstDiff(refFH, outFH);
        if (fhDiff < 0) System.out.println("  FileHeader: IDENTICAL");
        else System.out.println("  FileHeader: DIFFER at byte " + fhDiff +
                " ref=0x" + String.format("%02x", refFH[fhDiff]&0xFF) +
                " out=0x" + String.format("%02x", outFH[fhDiff]&0xFF));

        System.out.println("\n========== DocInfo Compare ==========");
        compareStream(refPoi, outPoi, "DocInfo");

        System.out.println("\n========== Section0 Compare ==========");
        compareSection(refPoi, outPoi);

        refPoi.close();
        outPoi.close();
    }

    static void compareStream(POIFSFileSystem refPoi, POIFSFileSystem outPoi, String name) throws Exception {
        byte[] refRaw = readStream(refPoi, name);
        byte[] outRaw = readStream(outPoi, name);
        byte[] refData = decompress(refRaw);
        byte[] outData = decompress(outRaw);
        System.out.println("  Raw: ref=" + refRaw.length + " out=" + outRaw.length);
        System.out.println("  Decompressed: ref=" + refData.length + " out=" + outData.length);

        List<Record> refRecs = parseRecords(refData);
        List<Record> outRecs = parseRecords(outData);
        System.out.println("  Records: ref=" + refRecs.size() + " out=" + outRecs.size());

        // tag 타입 그룹별 비교
        Map<Integer, List<Record>> refByTag = groupByTag(refRecs);
        Map<Integer, List<Record>> outByTag = groupByTag(outRecs);
        Set<Integer> allTags = new TreeSet<>(refByTag.keySet());
        allTags.addAll(outByTag.keySet());

        int totalDiff = 0;
        for (int tag : allTags) {
            List<Record> refList = refByTag.getOrDefault(tag, Collections.emptyList());
            List<Record> outList = outByTag.getOrDefault(tag, Collections.emptyList());
            if (refList.size() != outList.size()) {
                System.out.println("  [COUNT MISMATCH] " + tagName(tag) +
                        ": ref=" + refList.size() + " out=" + outList.size());
                totalDiff++;
                continue;
            }
            int diffCount = 0;
            int firstDiffIdx = -1;
            for (int i = 0; i < refList.size(); i++) {
                Record r = refList.get(i), o = outList.get(i);
                if (r.size != o.size || !Arrays.equals(r.data, o.data)) {
                    diffCount++;
                    if (firstDiffIdx < 0) firstDiffIdx = i;
                }
            }
            if (diffCount > 0) {
                System.out.println("  [CONTENT DIFF] " + tagName(tag) +
                        ": " + diffCount + "/" + refList.size() + " records differ");
                // 첫 번째 diff 상세 표시
                Record r = refList.get(firstDiffIdx), o = outList.get(firstDiffIdx);
                System.out.println("    First diff at index " + firstDiffIdx +
                        " refSize=" + r.size + " outSize=" + o.size);
                int byteOff = findFirstDiff(r.data, o.data);
                if (byteOff >= 0) {
                    int ctx = Math.max(0, byteOff - 2);
                    System.out.println("    Byte offset " + byteOff +
                            " ref[" + ctx + ":]=" + hex(r.data, Math.min(r.size, byteOff + 20)) +
                            " out[" + ctx + ":]=" + hex(o.data, Math.min(o.size, byteOff + 20)));
                }
                totalDiff++;
            } else {
                System.out.println("  [OK] " + tagName(tag) + ": " + refList.size() + " records match");
            }
        }
        System.out.println("  TOTAL: " + totalDiff + " tag groups with differences");
    }

    static void compareSection(POIFSFileSystem refPoi, POIFSFileSystem outPoi) throws Exception {
        byte[] refRaw = readStream(refPoi, "BodyText", "Section0");
        byte[] outRaw = readStream(outPoi, "BodyText", "Section0");
        byte[] refData = decompress(refRaw);
        byte[] outData = decompress(outRaw);
        System.out.println("  Raw: ref=" + refRaw.length + " out=" + outRaw.length);
        System.out.println("  Decompressed: ref=" + refData.length + " out=" + outData.length);

        List<Record> refRecs = parseRecords(refData);
        List<Record> outRecs = parseRecords(outData);
        System.out.println("  Records: ref=" + refRecs.size() + " out=" + outRecs.size());

        // tag별 개수
        Map<Integer, List<Record>> refByTag = groupByTag(refRecs);
        Map<Integer, List<Record>> outByTag = groupByTag(outRecs);
        Set<Integer> allTags = new TreeSet<>(refByTag.keySet());
        allTags.addAll(outByTag.keySet());
        for (int tag : allTags) {
            int rc = refByTag.getOrDefault(tag, Collections.emptyList()).size();
            int oc = outByTag.getOrDefault(tag, Collections.emptyList()).size();
            String status = rc == oc ? "OK" : "MISMATCH";
            System.out.println("  " + tagName(tag) + ": ref=" + rc + " out=" + oc + " " + status);
        }

        // 처음 N개 Record의 순차 비교
        System.out.println("\n  --- Sequential record comparison (first 50 records) ---");
        int maxComp = Math.min(50, Math.min(refRecs.size(), outRecs.size()));
        int seqDiffs = 0;
        for (int i = 0; i < maxComp; i++) {
            Record r = refRecs.get(i), o = outRecs.get(i);
            boolean tagMatch = r.tag == o.tag && r.level == o.level;
            boolean sizeMatch = r.size == o.size;
            boolean dataMatch = Arrays.equals(r.data, o.data);

            if (!tagMatch || !sizeMatch || !dataMatch) {
                seqDiffs++;
                System.out.println("  [" + i + "] " +
                        (tagMatch ? "" : "TAG_MISMATCH ") +
                        "ref=" + tagName(r.tag) + "/lv" + r.level + "/sz" + r.size +
                        " out=" + tagName(o.tag) + "/lv" + o.level + "/sz" + o.size);
                if (tagMatch && !dataMatch) {
                    int byteOff = findFirstDiff(r.data, o.data);
                    System.out.println("        FirstDiff@byte" + byteOff +
                            " ref=" + hex(Arrays.copyOfRange(r.data, Math.max(0,byteOff-2), Math.min(r.data.length, byteOff+16)), 40) +
                            " out=" + hex(Arrays.copyOfRange(o.data, Math.max(0,byteOff-2), Math.min(o.data.length, byteOff+16)), 40));
                }
                if (!tagMatch) {
                    // 각각이 가진 내용 표시
                    System.out.println("        ref_data=" + hex(r.data, 30) +
                            " out_data=" + hex(o.data, 30));
                }
            }
        }
        System.out.println("  Sequential diffs in first " + maxComp + " records: " + seqDiffs);

        // table 컨트롤이 있는 첫 번째 문단을 찾아 비교
        System.out.println("\n  --- First TABLE CTRL_HEADER comparison ---");
        Record refTbl = null, outTbl = null;
        for (Record r : refRecs) { if (r.tag == 0x047 && r.data.length >= 4) {
            int id = (r.data[0]&0xFF)|((r.data[1]&0xFF)<<8)|((r.data[2]&0xFF)<<16)|((r.data[3]&0xFF)<<24);
            if (id == 0x74626C20) { refTbl = r; break; }
        }}
        for (Record r : outRecs) { if (r.tag == 0x047 && r.data.length >= 4) {
            int id = (r.data[0]&0xFF)|((r.data[1]&0xFF)<<8)|((r.data[2]&0xFF)<<16)|((r.data[3]&0xFF)<<24);
            if (id == 0x74626C20) { outTbl = r; break; }
        }}
        if (refTbl != null && outTbl != null) {
            System.out.println("  ref: sz=" + refTbl.size + " data=" + hex(refTbl.data, 60));
            System.out.println("  out: sz=" + outTbl.size + " data=" + hex(outTbl.data, 60));
            int d = findFirstDiff(refTbl.data, outTbl.data);
            if (d >= 0) System.out.println("  FirstDiff@byte" + d);
            else System.out.println("  IDENTICAL");
        }
    }

    static Map<Integer, List<Record>> groupByTag(List<Record> recs) {
        Map<Integer, List<Record>> map = new LinkedHashMap<>();
        for (Record r : recs) map.computeIfAbsent(r.tag, k -> new ArrayList<>()).add(r);
        return map;
    }
}
