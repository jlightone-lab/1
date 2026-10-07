package kr.n.nframe.test;
import org.apache.poi.poifs.filesystem.*;
import java.io.*;
public class StreamFirstBytes {
    public static void main(String[] args) throws Exception {
        String path = args[0];
        String streamPath = args[1];  // "BinData/BIN0007.png"
        try (POIFSFileSystem poi = new POIFSFileSystem(new FileInputStream(path))) {
            String[] parts = streamPath.split("/");
            DirectoryEntry cur = poi.getRoot();
            for (int i = 0; i < parts.length - 1; i++) {
                cur = (DirectoryEntry) cur.getEntry(parts[i]);
            }
            DocumentEntry de = (DocumentEntry) cur.getEntry(parts[parts.length - 1]);
            byte[] buf = new byte[Math.min(64, de.getSize())];
            try (DocumentInputStream dis = new DocumentInputStream(de)) {
                dis.read(buf);
            }
            StringBuilder hex = new StringBuilder();
            StringBuilder asc = new StringBuilder();
            for (byte b : buf) {
                hex.append(String.format("%02X ", b & 0xFF));
                asc.append((b & 0xFF) >= 32 && (b & 0xFF) < 127 ? (char)b : '.');
            }
            System.out.println("Stream: " + streamPath + " (size=" + de.getSize() + ")");
            System.out.println("First " + buf.length + " bytes hex: " + hex.toString().trim());
            System.out.println("First " + buf.length + " bytes ascii: " + asc.toString());
        }
    }
}
