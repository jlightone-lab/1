package kr.n.nframe.test;

import org.apache.poi.poifs.filesystem.*;
import java.io.*;
import java.util.zip.Inflater;

/**
 * Section0 바이트가 zlib(raw deflate) 로 압축되어 있는지 검사.
 */
public class Section0Probe {
    public static void main(String[] args) throws Exception {
        for (String p : args) {
            System.out.println("===== " + p + " =====");
            try (POIFSFileSystem poi = new POIFSFileSystem(new FileInputStream(p))) {
                DirectoryEntry root = poi.getRoot();
                DirectoryEntry bt = (DirectoryEntry) root.getEntry("BodyText");
                DocumentEntry s0 = (DocumentEntry) bt.getEntry("Section0");
                byte[] data = new byte[s0.getSize()];
                try (DocumentInputStream is = new DocumentInputStream(s0)) {
                    int t = 0;
                    while (t < data.length) {
                        int r = is.read(data, t, data.length - t);
                        if (r < 0) break;
                        t += r;
                    }
                }
                System.out.println("Section0 stream size: " + data.length);
                System.out.print("first 16 bytes: ");
                for (int i = 0; i < Math.min(16, data.length); i++) System.out.printf("%02X ", data[i]);
                System.out.println();
                // raw deflate try
                try {
                    Inflater inf = new Inflater(true); // nowrap = raw deflate
                    inf.setInput(data);
                    byte[] buf = new byte[1024 * 64];
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    while (!inf.finished()) {
                        int n = inf.inflate(buf);
                        if (n == 0) {
                            if (inf.needsInput() || inf.finished()) break;
                            else throw new IOException("stuck");
                        }
                        baos.write(buf, 0, n);
                    }
                    inf.end();
                    System.out.println("inflated (raw deflate): OK, " + baos.size() + " bytes");
                } catch (Exception e) {
                    System.out.println("raw deflate inflate FAILED: " + e.getMessage());
                }
                // also try zlib (with header)
                try {
                    Inflater inf = new Inflater(false);
                    inf.setInput(data);
                    byte[] buf = new byte[1024 * 64];
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    while (!inf.finished()) {
                        int n = inf.inflate(buf);
                        if (n == 0) {
                            if (inf.needsInput() || inf.finished()) break;
                            else throw new IOException("stuck");
                        }
                        baos.write(buf, 0, n);
                    }
                    inf.end();
                    System.out.println("inflated (zlib w/header): OK, " + baos.size() + " bytes");
                } catch (Exception e) {
                    System.out.println("zlib w/header inflate FAILED: " + e.getMessage());
                }
            }
        }
    }
}
