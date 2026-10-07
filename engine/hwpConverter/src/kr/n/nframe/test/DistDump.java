package kr.n.nframe.test;

import org.apache.poi.poifs.filesystem.*;
import java.io.*;

/** 배포용 HWP 분석을 위해 ViewText/Section0 원시 바이트를 덤프한다 */
public class DistDump {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            System.out.println("\n=== " + path + " ===");
            POIFSFileSystem fs = new POIFSFileSystem(new FileInputStream(path));
            DirectoryEntry root = fs.getRoot();

            // ViewText 확인
            if (root.hasEntry("ViewText")) {
                DirectoryEntry vt = (DirectoryEntry) root.getEntry("ViewText");
                byte[] sec0 = StreamExtractor.readStream(vt, "Section0");
                System.out.println("ViewText/Section0: " + sec0.length + " bytes");
                System.out.print("  first 64 bytes: ");
                for (int i = 0; i < Math.min(64, sec0.length); i++)
                    System.out.printf("%02X ", sec0[i] & 0xFF);
                System.out.println();

                // 레코드 헤더로 시작하는지 확인
                if (sec0.length >= 4) {
                    int header = (sec0[0]&0xFF)|((sec0[1]&0xFF)<<8)|((sec0[2]&0xFF)<<16)|((sec0[3]&0xFF)<<24);
                    int tagId = header & 0x3FF;
                    int level = (header >> 10) & 0x3FF;
                    int size = (header >> 20) & 0xFFF;
                    System.out.printf("  Record header: tag=0x%03X level=%d size=%d%n", tagId, level, size);
                    if (tagId == 0x01C) {
                        System.out.println("  -> HWPTAG_DISTRIBUTE_DOC_DATA found!");
                        // Seed는 페이로드의 처음 4바이트 (offset 4)
                        int seed = (sec0[4]&0xFF)|((sec0[5]&0xFF)<<8)|((sec0[6]&0xFF)<<16)|((sec0[7]&0xFF)<<24);
                        System.out.printf("  Seed = 0x%08X (%d)%n", seed, seed);
                        System.out.println("  Remaining after dist record: " + (sec0.length - 4 - 256) + " bytes");
                    }
                }
            }

            // BodyText 확인
            if (root.hasEntry("BodyText")) {
                DirectoryEntry bt = (DirectoryEntry) root.getEntry("BodyText");
                byte[] sec0 = StreamExtractor.readStream(bt, "Section0");
                System.out.println("BodyText/Section0: " + sec0.length + " bytes");
                System.out.print("  first 32 bytes: ");
                for (int i = 0; i < Math.min(32, sec0.length); i++)
                    System.out.printf("%02X ", sec0[i] & 0xFF);
                System.out.println();
            }

            // Scripts 확인
            if (root.hasEntry("Scripts")) {
                DirectoryEntry sc = (DirectoryEntry) root.getEntry("Scripts");
                for (Entry e : sc) {
                    if (e instanceof DocumentEntry) {
                        byte[] data = StreamExtractor.readStream(sc, e.getName());
                        System.out.println("Scripts/" + e.getName() + ": " + data.length + " bytes");
                    }
                }
            }

            fs.close();
        }
    }
}
