package kr.n.nframe.test;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.docinfo.BinData;
import kr.dogfoot.hwplib.reader.HWPReader;

import java.util.ArrayList;

public class BinDataInspect {
    public static void main(String[] a) throws Exception {
        HWPFile h = HWPReader.fromFile(a[0]);
        ArrayList<BinData> list = h.getDocInfo().getBinDataList();
        System.out.println("=== BinData (count=" + list.size() + ") in " + a[0] + " ===");
        for (int i = 0; i < list.size(); i++) {
            BinData b = list.get(i);
            System.out.printf("  binData[%d] id=%d ext=%s relPath=%s absPath=%s%n",
                    i, b.getBinDataID(),
                    b.getExtensionForEmbedding(),
                    b.getRelativePathForLink(),
                    b.getAbsolutePathForLink());
        }
        // Also dump embedded streams
        if (h.getBinData() != null && h.getBinData().getEmbeddedBinaryDataList() != null) {
            System.out.println("=== Embedded BinData streams (count="
                    + h.getBinData().getEmbeddedBinaryDataList().size() + ") ===");
            for (int i = 0; i < h.getBinData().getEmbeddedBinaryDataList().size(); i++) {
                kr.dogfoot.hwplib.object.bindata.EmbeddedBinaryData e =
                        h.getBinData().getEmbeddedBinaryDataList().get(i);
                System.out.printf("  embed[%d] name=%s size=%d%n",
                        i, e.getName(), e.getData() == null ? 0 : e.getData().length);
            }
        }
    }
}
