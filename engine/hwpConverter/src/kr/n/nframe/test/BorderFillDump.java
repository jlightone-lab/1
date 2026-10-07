package kr.n.nframe.test;

import kr.n.nframe.HwpConverter;
import kr.n.nframe.hwplib.model.BorderFill;
import kr.n.nframe.hwplib.model.HwpDocument;
import kr.n.nframe.hwplib.reader.HwpxReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class BorderFillDump {
    public static void main(String[] args) throws Exception {
        String hwpPath = args[0];
        Path tmp = Files.createTempFile("bfdump_", ".hwpx");
        new HwpConverter().convertHwpToHwpx(hwpPath, tmp.toString());
        HwpDocument doc = HwpxReader.read(tmp.toString());
        Files.deleteIfExists(tmp);

        System.out.println("=== BorderFill list (count=" + doc.borderFills.size() + ") ===");
        for (int i = 0; i < doc.borderFills.size(); i++) {
            BorderFill bf = doc.borderFills.get(i);
            String fillStr = "";
            if ((bf.fillType & 1) != 0) fillStr += "color ";
            if ((bf.fillType & 2) != 0) fillStr += "image(binItemId=" + bf.imgBinItemId + ") ";
            if ((bf.fillType & 4) != 0) fillStr += "gradient ";
            if (fillStr.isEmpty()) fillStr = "none";
            System.out.printf("  bf[%d] fillType=%d %s%n", i, bf.fillType, fillStr);
        }

        System.out.println("=== BinData list (count=" + doc.binDataItems.size() + ") ===");
        for (int i = 0; i < doc.binDataItems.size(); i++) {
            kr.n.nframe.hwplib.model.BinDataItem b = doc.binDataItems.get(i);
            System.out.printf("  bin[%d] id=%d ext=%s size=%d%n",
                    i, b.binDataId, b.extension, b.data == null ? 0 : b.data.length);
        }
    }
}
