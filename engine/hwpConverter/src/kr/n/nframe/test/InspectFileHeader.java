package kr.n.nframe.test;

import org.apache.poi.poifs.filesystem.*;
import java.io.*;

public class InspectFileHeader {
    public static void main(String[] args) throws Exception {
        for (String p : args) {
            System.out.println("===== " + p + " =====");
            POIFSFileSystem poi = new POIFSFileSystem(new FileInputStream(p));
            DocumentEntry doc = (DocumentEntry) poi.getRoot().getEntry("FileHeader");
            byte[] fh = new byte[doc.getSize()];
            try (InputStream is = new DocumentInputStream(doc)) {
                int t = 0;
                while (t < fh.length) { int r = is.read(fh, t, fh.length - t); if (r < 0) break; t += r; }
            }
            System.out.println("FileHeader size: " + fh.length);
            System.out.print("first 36 bytes: ");
            for (int i = 0; i < 36; i++) System.out.printf("%02X ", fh[i]);
            System.out.println();
            System.out.print("ascii(0..32):   ");
            for (int i = 0; i < 32; i++) {
                char c = (char)(fh[i] & 0xFF);
                System.out.print((c >= 0x20 && c < 0x7F ? c : '.'));
            }
            System.out.println();
            System.out.printf("version (32-35): %02X %02X %02X %02X%n",
                    fh[32], fh[33], fh[34], fh[35]);
            System.out.printf("properties (36-39): %02X %02X %02X %02X%n",
                    fh[36], fh[37], fh[38], fh[39]);
            poi.close();
        }
    }
}
