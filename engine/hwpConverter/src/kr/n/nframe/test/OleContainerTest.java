package kr.n.nframe.test;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.*;

/**
 * 테스트: reference HWP 파일의 모든 스트림을 새 OLE2 컨테이너로 복사한다.
 * 결과 파일이 한글에서 열리면 OLE2 컨테이너 생성 방식은 문제없는 것이다.
 * 열리지 않으면 POI의 OLE2 출력이 HWP와 호환되지 않는다는 의미다.
 */
public class OleContainerTest {
    public static void main(String[] args) throws Exception {
        String refPath = "hwp/test_hwpx2_conv.hwp";
        String outPath = "output/ole_copy_test.hwp";

        // reference의 모든 스트림 읽기
        POIFSFileSystem refPoi = new POIFSFileSystem(new FileInputStream(refPath));

        // 새 OLE2를 만들고 모든 스트림을 그대로 복사
        POIFSFileSystem newPoi = new POIFSFileSystem();
        DirectoryEntry newRoot = newPoi.getRoot();

        copyDirectory(refPoi.getRoot(), newRoot);

        try (OutputStream fos = new FileOutputStream(outPath)) {
            newPoi.writeFilesystem(fos);
        }
        newPoi.close();
        refPoi.close();

        System.out.println("Created OLE copy: " + outPath);
        System.out.println("If this opens in Hangul, our OLE2 container is fine.");
    }

    private static void copyDirectory(DirectoryEntry src, DirectoryEntry dst) throws IOException {
        for (Entry entry : src) {
            String name = entry.getName();
            if (entry.isDirectoryEntry()) {
                DirectoryEntry srcDir = (DirectoryEntry) entry;
                DirectoryEntry dstDir = dst.createDirectory(name);
                copyDirectory(srcDir, dstDir);
            } else if (entry.isDocumentEntry()) {
                DocumentEntry doc = (DocumentEntry) entry;
                byte[] data = new byte[doc.getSize()];
                try (InputStream is = new org.apache.poi.poifs.filesystem.DocumentInputStream(doc)) {
                    int total = 0;
                    while (total < data.length) {
                        int read = is.read(data, total, data.length - total);
                        if (read < 0) break;
                        total += read;
                    }
                }
                dst.createDocument(name, new ByteArrayInputStream(data));
            }
        }
    }
}
