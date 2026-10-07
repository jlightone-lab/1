package kr.n.nframe.test;

import org.apache.poi.poifs.filesystem.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * reference 스트림을 우리가 생성한 스트림으로 점진적으로 교체하면서
 * "corrupted file"의 정확한 원인을 분리한다.
 *
 * Step 1: reference를 그대로 복사 (열려야 함 - 이미 검증됨)
 * Step 2: DocInfo 압축 바이트만 교체 -> 테스트
 * Step 3: Section0 압축 바이트만 교체 -> 테스트
 * Step 4: DocInfo + Section0 교체 -> 테스트
 * Step 5: 모든 스트림 교체 -> 테스트
 *
 * 각 단계마다 압축 데이터가 올바르게 해제될 수 있는지도 함께 검증한다.
 */
public class BinaryIsolator {

    public static void main(String[] args) throws Exception {
        String refPath = "hwp/test_hwpx2_conv.hwp";
        String outPath = "output/test_hwpx2_conv.hwp";

        // reference 스트림 읽기
        POIFSFileSystem refPoi = new POIFSFileSystem(new FileInputStream(refPath));
        byte[] refFH = readStream(refPoi, "FileHeader");
        byte[] refDI = readStream(refPoi, "DocInfo");
        byte[] refSec = readStream(refPoi, "BodyText", "Section0");

        // 우리 output 스트림 읽기
        POIFSFileSystem outPoi = new POIFSFileSystem(new FileInputStream(outPath));
        byte[] outFH = readStream(outPoi, "FileHeader");
        byte[] outDI = readStream(outPoi, "DocInfo");
        byte[] outSec = readStream(outPoi, "BodyText", "Section0");

        System.out.println("=== Stream sizes ===");
        System.out.println("FileHeader: ref=" + refFH.length + " out=" + outFH.length);
        System.out.println("DocInfo:    ref=" + refDI.length + " out=" + outDI.length);
        System.out.println("Section0:   ref=" + refSec.length + " out=" + outSec.length);

        // 압축 해제 검증
        System.out.println("\n=== Decompression check ===");
        verifyDecompress("ref DocInfo", refDI);
        verifyDecompress("out DocInfo", outDI);
        verifyDecompress("ref Section0", refSec);
        verifyDecompress("out Section0", outSec);

        // 우리 압축 데이터가 유효한 레코드를 생성하는지 확인
        System.out.println("\n=== Record parsing check ===");
        byte[] outDIDecomp = decompress(outDI);
        byte[] outSecDecomp = decompress(outSec);
        int diRecs = countRecords(outDIDecomp);
        int secRecs = countRecords(outSecDecomp);
        System.out.println("out DocInfo records: " + diRecs);
        System.out.println("out Section0 records: " + secRecs);

        // 레코드 무결성 검증 - 모든 바이트가 소비되었는지 확인
        verifyRecordIntegrity("out DocInfo", outDIDecomp);
        verifyRecordIntegrity("out Section0", outSecDecomp);

        // 테스트 파일 생성
        // Test A: 전부 ref (대조군 - 열려야 함)
        buildFile("output/isolate_A_allRef.hwp", refPoi, refFH, refDI, refSec);

        // Test B: 우리 FileHeader만
        buildFile("output/isolate_B_ourFH.hwp", refPoi, outFH, refDI, refSec);

        // Test C: 우리 DocInfo만
        buildFile("output/isolate_C_ourDI.hwp", refPoi, refFH, outDI, refSec);

        // Test D: 우리 Section0만
        buildFile("output/isolate_D_ourSec.hwp", refPoi, refFH, refDI, outSec);

        // Test E: 우리 DocInfo + Section0
        buildFile("output/isolate_E_ourDI_Sec.hwp", refPoi, refFH, outDI, outSec);

        // Test F: 전부 우리 것
        buildFile("output/isolate_F_allOurs.hwp", refPoi, outFH, outDI, outSec);

        System.out.println("\nGenerated 6 test files in output/");
        System.out.println("Open each in Hangul to find which stream causes corruption:");
        System.out.println("  A: All reference (control)");
        System.out.println("  B: Our FileHeader + ref others");
        System.out.println("  C: Our DocInfo + ref others");
        System.out.println("  D: Our Section0 + ref others");
        System.out.println("  E: Our DocInfo+Section0 + ref others");
        System.out.println("  F: All ours");

        refPoi.close();
        outPoi.close();
    }

    static void buildFile(String path, POIFSFileSystem refPoi,
                          byte[] fh, byte[] di, byte[] sec) throws Exception {
        POIFSFileSystem newPoi = new POIFSFileSystem();
        DirectoryEntry root = newPoi.getRoot();

        root.createDocument("FileHeader", new ByteArrayInputStream(fh));
        root.createDocument("DocInfo", new ByteArrayInputStream(di));

        DirectoryEntry bt = root.createDirectory("BodyText");
        bt.createDocument("Section0", new ByteArrayInputStream(sec));

        // reference에서 BinData, Scripts 등 복사
        copyDir(refPoi.getRoot(), root, "BinData");
        copyDir(refPoi.getRoot(), root, "Scripts");
        copyDir(refPoi.getRoot(), root, "DocOptions");
        copyStream(refPoi.getRoot(), root, "PrvText");
        copyStream(refPoi.getRoot(), root, "PrvImage");
        // SummaryInformation 복사
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
        System.out.println("  Created: " + path);
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
                        int t=0; while(t<data.length){int r=is.read(data,t,data.length-t);if(r<0)break;t+=r;}
                    }
                    dstDir.createDocument(entry.getName(), new ByteArrayInputStream(data));
                }
            }
        } catch (Exception e) { /* 디렉터리 없음, 건너뜀 */ }
    }

    static void copyStream(DirectoryEntry src, DirectoryEntry dst, String name) {
        try {
            DocumentEntry doc = (DocumentEntry) src.getEntry(name);
            byte[] data = new byte[doc.getSize()];
            try (InputStream is = new DocumentInputStream(doc)) {
                int t=0; while(t<data.length){int r=is.read(data,t,data.length-t);if(r<0)break;t+=r;}
            }
            dst.createDocument(name, new ByteArrayInputStream(data));
        } catch (Exception e) { /* 스트림 없음, 건너뜀 */ }
    }

    static byte[] readStream(POIFSFileSystem poi, String... path) throws IOException {
        DirectoryEntry dir = poi.getRoot();
        for (int i = 0; i < path.length - 1; i++) dir = (DirectoryEntry) dir.getEntry(path[i]);
        DocumentEntry doc = (DocumentEntry) dir.getEntry(path[path.length - 1]);
        byte[] data = new byte[doc.getSize()];
        try (InputStream is = new DocumentInputStream(doc)) {
            int t=0; while(t<data.length){int r=is.read(data,t,data.length-t);if(r<0)break;t+=r;}
        }
        return data;
    }

    static byte[] decompress(byte[] raw) {
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
            return null;
        }
    }

    static void verifyDecompress(String label, byte[] compressed) {
        byte[] decompressed = decompress(compressed);
        if (decompressed == null) {
            System.out.println("  " + label + ": DECOMPRESS FAILED!");
        } else {
            System.out.println("  " + label + ": " + compressed.length + " -> " + decompressed.length + " bytes OK");
        }
    }

    static int countRecords(byte[] data) {
        int count = 0, pos = 0;
        while (pos + 4 <= data.length) {
            int h = (data[pos]&0xFF)|((data[pos+1]&0xFF)<<8)|((data[pos+2]&0xFF)<<16)|((data[pos+3]&0xFF)<<24);
            pos += 4;
            int size = (h >> 20) & 0xFFF;
            if (size == 0xFFF) {
                if (pos + 4 > data.length) break;
                size = (data[pos]&0xFF)|((data[pos+1]&0xFF)<<8)|((data[pos+2]&0xFF)<<16)|((data[pos+3]&0xFF)<<24);
                pos += 4;
            }
            pos += size;
            count++;
        }
        return count;
    }

    static void verifyRecordIntegrity(String label, byte[] data) {
        int pos = 0;
        int count = 0;
        while (pos + 4 <= data.length) {
            int h = (data[pos]&0xFF)|((data[pos+1]&0xFF)<<8)|((data[pos+2]&0xFF)<<16)|((data[pos+3]&0xFF)<<24);
            int recStart = pos;
            pos += 4;
            int tag = h & 0x3FF;
            int level = (h >> 10) & 0x3FF;
            int size = (h >> 20) & 0xFFF;
            if (size == 0xFFF) {
                if (pos + 4 > data.length) {
                    System.out.println("  " + label + ": TRUNCATED extended size at record " + count);
                    return;
                }
                size = (data[pos]&0xFF)|((data[pos+1]&0xFF)<<8)|((data[pos+2]&0xFF)<<16)|((data[pos+3]&0xFF)<<24);
                pos += 4;
            }
            if (pos + size > data.length) {
                System.out.println("  " + label + ": Record " + count + " (tag=0x" +
                    Integer.toHexString(tag) + " level=" + level + " size=" + size +
                    ") extends beyond data at offset " + recStart +
                    " (need " + (pos+size) + " have " + data.length + ")");
                return;
            }
            pos += size;
            count++;
        }
        int leftover = data.length - pos;
        if (leftover == 0) {
            System.out.println("  " + label + ": " + count + " records, all bytes consumed OK");
        } else {
            System.out.println("  " + label + ": " + count + " records, " + leftover + " LEFTOVER bytes!");
        }
    }
}
