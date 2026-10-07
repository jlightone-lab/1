package kr.n.nframe.test;
import org.apache.poi.poifs.filesystem.*;
import java.io.*;

public class BodyTextDump {
    public static void main(String[] args) throws Exception {
        POIFSFileSystem fs = new POIFSFileSystem(new FileInputStream(args[0]));
        DirectoryEntry root = fs.getRoot();

        // 최상위 엔트리 전체 나열
        System.out.println("=== OLE2 entries ===");
        for (Entry e : root) {
            System.out.println(e.getName() + " " + (e instanceof DirectoryEntry ? "DIR" : "size=" + ((DocumentEntry)e).getSize()));
        }

        if (root.hasEntry("BodyText")) {
            DirectoryEntry bt = (DirectoryEntry) root.getEntry("BodyText");
            byte[] sec0 = StreamExtractor.readStream(bt, "Section0");
            System.out.println("\nBodyText/Section0: " + sec0.length + " bytes (raw/compressed)");
            System.out.print("  hex: ");
            for (int i = 0; i < Math.min(64, sec0.length); i++) System.out.printf("%02X ", sec0[i]&0xFF);
            System.out.println();

            try {
                byte[] dec = StreamExtractor.decompress(sec0);
                System.out.println("  decompressed: " + dec.length + " bytes");
                // 레코드 파싱
                int pos = 0, idx = 0;
                while (pos + 4 <= dec.length) {
                    int h = (dec[pos]&0xFF)|((dec[pos+1]&0xFF)<<8)|((dec[pos+2]&0xFF)<<16)|((dec[pos+3]&0xFF)<<24);
                    int tag = h & 0x3FF;
                    int level = (h>>10)&0x3FF;
                    int size = (h>>20)&0xFFF;
                    pos += 4;
                    if (size == 0xFFF) { size = (dec[pos]&0xFF)|((dec[pos+1]&0xFF)<<8)|((dec[pos+2]&0xFF)<<16)|((dec[pos+3]&0xFF)<<24); pos += 4; }
                    System.out.printf("  [%d] tag=0x%03X level=%d size=%d%n", idx, tag, level, size);
                    pos += size;
                    idx++;
                }
            } catch (Exception e) {
                System.out.println("  NOT compressed, trying raw parse...");
            }
        }
        fs.close();
    }
}
