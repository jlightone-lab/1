package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.docinfo.BinData;
import kr.dogfoot.hwplib.object.docinfo.DocInfo;
import kr.dogfoot.hwplib.object.bindata.EmbeddedBinaryData;
import kr.dogfoot.hwplib.reader.HWPReader;
import org.apache.poi.poifs.filesystem.*;
import java.io.*;
public class InspectBinDataDetail {
    public static void main(String[] args) throws Exception {
        String path = args[0];
        System.out.println("=== File: " + path + " ===");
        HWPFile h = HWPReader.fromFile(path);
        DocInfo di = h.getDocInfo();
        System.out.println("DocInfo BinData records: " + di.getBinDataList().size());
        for (BinData bd : di.getBinDataList()) {
            System.out.println("  ID=" + bd.getBinDataID()
                + " ext=" + bd.getExtensionForEmbedding()
                + " type=" + bd.getProperty().getType()
                + " compress=" + bd.getProperty().getCompress()
                + " state=" + bd.getProperty().getState()
                + " propValue=0x" + Integer.toHexString(bd.getProperty().getValue() & 0xFFFF));
        }
        System.out.println("\nHWPFile EmbeddedBinaryData: " + h.getBinData().getEmbeddedBinaryDataList().size());
        for (EmbeddedBinaryData ebd : h.getBinData().getEmbeddedBinaryDataList()) {
            System.out.println("  name='" + ebd.getName() + "' compress=" + ebd.getCompressMethod()
                + " size=" + (ebd.getData() == null ? -1 : ebd.getData().length));
        }
        System.out.println("\n=== OLE2 BinData stream names ===");
        try (POIFSFileSystem poi = new POIFSFileSystem(new FileInputStream(path))) {
            if (poi.getRoot().hasEntry("BinData")) {
                DirectoryEntry bin = (DirectoryEntry) poi.getRoot().getEntry("BinData");
                for (Entry e : bin) {
                    if (e instanceof DocumentEntry) {
                        System.out.println("  stream='" + e.getName() + "' size=" + ((DocumentEntry)e).getSize());
                    }
                }
            } else {
                System.out.println("  (no BinData directory)");
            }
        }
    }
}
