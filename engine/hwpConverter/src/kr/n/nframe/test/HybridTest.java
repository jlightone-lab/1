package kr.n.nframe.test;

import org.apache.poi.poifs.filesystem.*;
import java.io.*;

/**
 * 하이브리드 테스트: reference와 우리 output 사이에서 개별 스트림을 교체하여
 * 어느 스트림이 "corrupted file" 오류를 일으키는지 분리한다.
 *
 * Test 1: 우리 FileHeader + 나머지 전부 Reference
 * Test 2: Reference FileHeader + 우리 DocInfo + Reference Section0 + Reference BinData
 * Test 3: Reference FileHeader + Reference DocInfo + 우리 Section0 + Reference BinData
 * Test 4: Reference FileHeader + 우리 DocInfo + 우리 Section0 + Reference BinData
 */
public class HybridTest {
    public static void main(String[] args) throws Exception {
        String refPath = "hwp/test_hwpx2_conv.hwp";
        String outPath = "output/test_hwpx2_conv.hwp";

        POIFSFileSystem refPoi = new POIFSFileSystem(new FileInputStream(refPath));
        POIFSFileSystem outPoi = new POIFSFileSystem(new FileInputStream(outPath));

        byte[] refFileHeader = readStream(refPoi, "FileHeader");
        byte[] outFileHeader = readStream(outPoi, "FileHeader");
        byte[] refDocInfo = readStream(refPoi, "DocInfo");
        byte[] outDocInfo = readStream(outPoi, "DocInfo");
        byte[] refSection0 = readStream(refPoi, "BodyText", "Section0");
        byte[] outSection0 = readStream(outPoi, "BodyText", "Section0");

        // Test 1: 우리 FileHeader + 나머지 전부 reference
        {
            POIFSFileSystem test = new POIFSFileSystem();
            DirectoryEntry root = test.getRoot();
            root.createDocument("FileHeader", stream(outFileHeader));
            root.createDocument("DocInfo", stream(refDocInfo));
            DirectoryEntry bt = root.createDirectory("BodyText");
            bt.createDocument("Section0", stream(refSection0));
            copyBinData(refPoi, root);
            copySummary(refPoi, root);
            copyScripts(refPoi, root);
            copyOptional(refPoi, root);
            writeFile(test, "output/hybrid_test1_ourFH.hwp");
            System.out.println("Test1: Our FileHeader + Ref others -> output/hybrid_test1_ourFH.hwp");
        }

        // Test 2: Ref FileHeader + 우리 DocInfo + Ref Section0
        {
            POIFSFileSystem test = new POIFSFileSystem();
            DirectoryEntry root = test.getRoot();
            root.createDocument("FileHeader", stream(refFileHeader));
            root.createDocument("DocInfo", stream(outDocInfo));
            DirectoryEntry bt = root.createDirectory("BodyText");
            bt.createDocument("Section0", stream(refSection0));
            copyBinData(refPoi, root);
            copySummary(refPoi, root);
            copyScripts(refPoi, root);
            copyOptional(refPoi, root);
            writeFile(test, "output/hybrid_test2_ourDI.hwp");
            System.out.println("Test2: Our DocInfo + Ref others -> output/hybrid_test2_ourDI.hwp");
        }

        // Test 3: Ref FileHeader + Ref DocInfo + 우리 Section0
        {
            POIFSFileSystem test = new POIFSFileSystem();
            DirectoryEntry root = test.getRoot();
            root.createDocument("FileHeader", stream(refFileHeader));
            root.createDocument("DocInfo", stream(refDocInfo));
            DirectoryEntry bt = root.createDirectory("BodyText");
            bt.createDocument("Section0", stream(outSection0));
            copyBinData(refPoi, root);
            copySummary(refPoi, root);
            copyScripts(refPoi, root);
            copyOptional(refPoi, root);
            writeFile(test, "output/hybrid_test3_ourSec.hwp");
            System.out.println("Test3: Our Section0 + Ref others -> output/hybrid_test3_ourSec.hwp");
        }

        // Test 4: 우리 DocInfo + 우리 Section0 + Ref BinData (ref는 BinData뿐)
        {
            POIFSFileSystem test = new POIFSFileSystem();
            DirectoryEntry root = test.getRoot();
            root.createDocument("FileHeader", stream(refFileHeader));
            root.createDocument("DocInfo", stream(outDocInfo));
            DirectoryEntry bt = root.createDirectory("BodyText");
            bt.createDocument("Section0", stream(outSection0));
            copyBinData(refPoi, root);
            copySummary(refPoi, root);
            copyScripts(refPoi, root);
            copyOptional(refPoi, root);
            writeFile(test, "output/hybrid_test4_ourDI_ourSec.hwp");
            System.out.println("Test4: Our DocInfo+Section + Ref BinData -> output/hybrid_test4_ourDI_ourSec.hwp");
        }

        refPoi.close();
        outPoi.close();
    }

    static byte[] readStream(POIFSFileSystem poi, String... path) throws IOException {
        DirectoryEntry dir = poi.getRoot();
        for (int i = 0; i < path.length - 1; i++) {
            dir = (DirectoryEntry) dir.getEntry(path[i]);
        }
        DocumentEntry doc = (DocumentEntry) dir.getEntry(path[path.length - 1]);
        byte[] data = new byte[doc.getSize()];
        try (InputStream is = new DocumentInputStream(doc)) {
            int total = 0;
            while (total < data.length) {
                int read = is.read(data, total, data.length - total);
                if (read < 0) break;
                total += read;
            }
        }
        return data;
    }

    static ByteArrayInputStream stream(byte[] data) { return new ByteArrayInputStream(data); }

    static void copyBinData(POIFSFileSystem src, DirectoryEntry dstRoot) throws IOException {
        try {
            DirectoryEntry srcBin = (DirectoryEntry) src.getRoot().getEntry("BinData");
            DirectoryEntry dstBin = dstRoot.createDirectory("BinData");
            for (Entry entry : srcBin) {
                if (entry.isDocumentEntry()) {
                    byte[] data = readStream(src, "BinData", entry.getName());
                    dstBin.createDocument(entry.getName(), stream(data));
                }
            }
        } catch (FileNotFoundException e) { /* BinData 없음 */ }
    }

    static void copySummary(POIFSFileSystem src, DirectoryEntry dstRoot) throws IOException {
        for (Entry entry : src.getRoot()) {
            if (entry.getName().contains("Summary")) {
                byte[] data = readStream(src, entry.getName());
                dstRoot.createDocument(entry.getName(), stream(data));
            }
        }
    }

    static void copyScripts(POIFSFileSystem src, DirectoryEntry dstRoot) throws IOException {
        try {
            DirectoryEntry srcScr = (DirectoryEntry) src.getRoot().getEntry("Scripts");
            DirectoryEntry dstScr = dstRoot.createDirectory("Scripts");
            for (Entry entry : srcScr) {
                if (entry.isDocumentEntry()) {
                    byte[] data = readStream(src, "Scripts", entry.getName());
                    dstScr.createDocument(entry.getName(), stream(data));
                }
            }
        } catch (FileNotFoundException e) {}
    }

    static void copyOptional(POIFSFileSystem src, DirectoryEntry dstRoot) throws IOException {
        for (String name : new String[]{"PrvText", "PrvImage"}) {
            try {
                byte[] data = readStream(src, name);
                dstRoot.createDocument(name, stream(data));
            } catch (FileNotFoundException e) {}
        }
        try {
            DirectoryEntry srcOpt = (DirectoryEntry) src.getRoot().getEntry("DocOptions");
            DirectoryEntry dstOpt = dstRoot.createDirectory("DocOptions");
            for (Entry entry : srcOpt) {
                if (entry.isDocumentEntry()) {
                    byte[] data = readStream(src, "DocOptions", entry.getName());
                    dstOpt.createDocument(entry.getName(), stream(data));
                }
            }
        } catch (FileNotFoundException e) {}
    }

    static void writeFile(POIFSFileSystem poi, String path) throws IOException {
        try (OutputStream fos = new FileOutputStream(path)) {
            poi.writeFilesystem(fos);
        }
        poi.close();
    }
}
