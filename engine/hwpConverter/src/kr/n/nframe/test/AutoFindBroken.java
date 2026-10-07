package kr.n.nframe.test;

import kr.dogfoot.hwplib.reader.HWPReader;
import org.apache.poi.poifs.filesystem.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * hwplib을 oracle로 사용하는 자동 이진 탐색.
 * Section0에서 "This is not paragraph" 오류를 일으키는 정확한 레코드를 찾는다.
 *
 * 전략: ref[0..N] + out[N..end]. N에 대해 이진 탐색.
 * N = 전체 ref 레코드일 때 -> OK. N = 0일 때 -> FAIL.
 */
public class AutoFindBroken {

    public static void main(String[] args) throws Exception {
        String refPath = args.length > 1 ? args[1] : "hwp/test_hwpx2_conv.hwp";
        String outPath = args.length > 0 ? args[0] : "output/test_hwpx2_conv.hwp";
        POIFSFileSystem refPoi = new POIFSFileSystem(new FileInputStream(refPath));
        POIFSFileSystem outPoi = new POIFSFileSystem(new FileInputStream(outPath));

        byte[] refFH = readStream(refPoi, "FileHeader");
        byte[] refDI = readStream(refPoi, "DocInfo");
        byte[] refSec = decomp(readStream(refPoi, "BodyText", "Section0"));
        byte[] outSec = decomp(readStream(outPoi, "BodyText", "Section0"));

        List<byte[]> refRecs = parseRaw(refSec);
        List<byte[]> outRecs = parseRaw(outSec);

        System.out.println("ref=" + refRecs.size() + " out=" + outRecs.size());

        // 이진 탐색
        int lo = 0, hi = Math.min(refRecs.size(), outRecs.size());

        // 경계 검증
        if (!testSplice(refPoi, refFH, refDI, refRecs, outRecs, hi)) {
            System.out.println("ERROR: even all-ref records fail! Checking...");
            // 순수 reference 데이터로 시도
            byte[] comp = comp(refSec);
            buildAndTest(refPoi, refFH, refDI, comp, "all-ref");
            return;
        }
        if (testSplice(refPoi, refFH, refDI, refRecs, outRecs, 0)) {
            System.out.println("All-out records pass! No error found.");
            return;
        }

        // 이진 탐색: ref[0..N-1]+out[N..]가 통과하는 가장 작은 N을 찾는다
        while (hi - lo > 1) {
            int mid = (lo + hi) / 2;
            boolean ok = testSplice(refPoi, refFH, refDI, refRecs, outRecs, mid);
            System.out.println("  N=" + mid + ": " + (ok ? "OK" : "FAIL"));
            if (ok) hi = mid;
            else lo = mid;
        }

        System.out.println("\nBroken record: " + lo);
        System.out.println("ref[0.." + (lo-1) + "] + out[" + lo + "..] = FAIL");
        System.out.println("ref[0.." + lo + "] + out[" + (lo+1) + "..] = OK");

        // lo 위치의 레코드가 무엇인지 출력
        byte[] rec = outRecs.get(lo);
        int h = readInt(rec, 0);
        int tag = h & 0x3FF; int lv = (h >> 10) & 0x3FF;
        int sz = (h >> 20) & 0xFFF;
        String nm = tagName(tag);
        System.out.println("Record " + lo + ": " + nm + " lv=" + lv + " sz=" + (sz == 0xFFF ? "extended" : sz));

        // 주변 레코드도 함께 출력
        for (int i = Math.max(0, lo-3); i <= Math.min(outRecs.size()-1, lo+3); i++) {
            byte[] r = outRecs.get(i);
            int rh = readInt(r, 0);
            int rt = rh & 0x3FF; int rl = (rh >> 10) & 0x3FF;
            String rn = tagName(rt);
            String marker = (i == lo) ? " <<< BROKEN" : "";
            System.out.println("  [" + i + "] " + rn + " lv=" + rl + " sz=" + r.length + marker);
        }

        // lo 위치에서 ref와 우리 레코드의 바이트 차이 출력
        if (lo < refRecs.size()) {
            byte[] refRec = refRecs.get(lo);
            byte[] outRec = outRecs.get(lo);
            System.out.println("\nRef record " + lo + ": " + refRec.length + " bytes");
            System.out.println("Out record " + lo + ": " + outRec.length + " bytes");
            if (refRec.length == outRec.length) {
                for (int b = 0; b < refRec.length; b++) {
                    if (refRec[b] != outRec[b]) {
                        System.out.println("First diff at byte " + b + ": ref=0x" +
                            String.format("%02x", refRec[b] & 0xFF) + " out=0x" +
                            String.format("%02x", outRec[b] & 0xFF));
                        break;
                    }
                }
            }
        }

        refPoi.close();
        outPoi.close();
    }

    static boolean testSplice(POIFSFileSystem refPoi, byte[] fh, byte[] di,
                              List<byte[]> refRecs, List<byte[]> outRecs, int n) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (int i = 0; i < n && i < refRecs.size(); i++) bos.write(refRecs.get(i));
        for (int i = n; i < outRecs.size(); i++) bos.write(outRecs.get(i));
        byte[] combined = comp(bos.toByteArray());

        String tmpFile = "output/_bisect_tmp.hwp";
        buildFile(refPoi, fh, di, combined, tmpFile);
        return testWithHwpLib(tmpFile);
    }

    static boolean testWithHwpLib(String path) {
        try {
            HWPReader.fromFile(path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void buildAndTest(POIFSFileSystem refPoi, byte[] fh, byte[] di, byte[] sec, String label) throws Exception {
        String tmpFile = "output/_test_" + label + ".hwp";
        buildFile(refPoi, fh, di, sec, tmpFile);
        boolean ok = testWithHwpLib(tmpFile);
        System.out.println("  " + label + ": " + (ok ? "OK" : "FAIL"));
    }

    static void buildFile(POIFSFileSystem refPoi, byte[] fh, byte[] di, byte[] sec, String path) throws Exception {
        POIFSFileSystem newPoi = new POIFSFileSystem();
        DirectoryEntry root = newPoi.getRoot();
        root.createDocument("FileHeader", new ByteArrayInputStream(fh));
        root.createDocument("DocInfo", new ByteArrayInputStream(di));
        DirectoryEntry bt = root.createDirectory("BodyText");
        bt.createDocument("Section0", new ByteArrayInputStream(sec));
        // BinData, Scripts 등 복사
        copyDir(refPoi.getRoot(), root, "BinData");
        copyDir(refPoi.getRoot(), root, "Scripts");
        copyDir(refPoi.getRoot(), root, "DocOptions");
        copyStream(refPoi.getRoot(), root, "PrvText");
        copyStream(refPoi.getRoot(), root, "PrvImage");
        for (Entry entry : refPoi.getRoot()) {
            if (entry.getName().contains("Summary") && entry.isDocumentEntry()) {
                root.createDocument(entry.getName(), new ByteArrayInputStream(readStream(refPoi, entry.getName())));
            }
        }
        try (OutputStream fos = new FileOutputStream(path)) { newPoi.writeFilesystem(fos); }
        newPoi.close();
    }

    static List<byte[]> parseRaw(byte[] data) {
        List<byte[]> recs = new ArrayList<>();
        int pos = 0;
        while (pos + 4 <= data.length) {
            int rstart = pos;
            int h = readInt(data, pos); pos += 4;
            int sz = (h >> 20) & 0xFFF;
            if (sz == 0xFFF) { sz = readInt(data, pos); pos += 4; }
            pos += sz;
            recs.add(Arrays.copyOfRange(data, rstart, pos));
        }
        return recs;
    }

    static int readInt(byte[] d, int o) {
        return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24);
    }

    static String tagName(int tag) {
        switch(tag) {
            case 0x042: return "PARA_HDR"; case 0x043: return "PARA_TXT";
            case 0x044: return "PARA_CS"; case 0x045: return "PARA_LS";
            case 0x047: return "CTRL_HDR"; case 0x048: return "LIST_HDR";
            case 0x049: return "PAGE_DEF"; case 0x04A: return "FN_SHAPE";
            case 0x04B: return "PG_BF"; case 0x04C: return "SHAPE_CMP";
            case 0x04D: return "TABLE"; case 0x055: return "PICTURE";
            default: return String.format("0x%03X", tag);
        }
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

    static byte[] decomp(byte[] raw) throws Exception {
        Inflater i = new Inflater(true); i.setInput(raw);
        ByteArrayOutputStream o = new ByteArrayOutputStream(raw.length * 4);
        byte[] b = new byte[8192]; while (!i.finished()) { int n = i.inflate(b); if(n==0&&i.needsInput()) break; o.write(b,0,n); }
        i.end(); return o.toByteArray();
    }

    static byte[] comp(byte[] data) throws Exception {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true); d.setInput(data); d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream(data.length);
        byte[] b = new byte[8192]; while (!d.finished()) { int n = d.deflate(b); o.write(b,0,n); }
        d.end(); return o.toByteArray();
    }

    static void copyDir(DirectoryEntry src, DirectoryEntry dst, String name) {
        try {
            DirectoryEntry s = (DirectoryEntry) src.getEntry(name);
            DirectoryEntry d = dst.createDirectory(name);
            for (Entry e : s) {
                if (e.isDocumentEntry()) {
                    DocumentEntry doc = (DocumentEntry) e;
                    byte[] data = new byte[doc.getSize()];
                    try (InputStream is = new DocumentInputStream(doc)) {
                        int t=0; while(t<data.length){int r=is.read(data,t,data.length-t);if(r<0)break;t+=r;}
                    }
                    d.createDocument(e.getName(), new ByteArrayInputStream(data));
                }
            }
        } catch (Exception e) {}
    }

    static void copyStream(DirectoryEntry src, DirectoryEntry dst, String name) {
        try {
            DocumentEntry doc = (DocumentEntry) src.getEntry(name);
            byte[] data = new byte[doc.getSize()];
            try (InputStream is = new DocumentInputStream(doc)) {
                int t=0; while(t<data.length){int r=is.read(data,t,data.length-t);if(r<0)break;t+=r;}
            }
            dst.createDocument(name, new ByteArrayInputStream(data));
        } catch (Exception e) {}
    }
}
