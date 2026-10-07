package kr.n.nframe.test;

import kr.n.nframe.hwplib.binary.ZlibCompressor;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;

import java.io.IOException;

/**
 * 테스트·디버그 유틸리티.
 *
 * <p>여러 debug 도구(BodyTextDump / DistDump / NcharsValidator /
 * DistDecryptTest 등)가 OLE2 스트림을 읽고 zlib 해제하는 공통 루틴을 호출한다.
 * 이전 리팩터링에서 해당 통합 유틸 클래스가 삭제되어 빌드가 깨졌는데,
 * 이 파일은 그 최소 대체품이다.
 *
 * <p>프로덕션 경로에서는 사용하지 않으므로 build/sources.txt 에 포함되지 않는다.
 */
public final class StreamExtractor {

    private StreamExtractor() {}

    /** OLE2 디렉터리 엔트리에서 지정 스트림의 전체 byte 를 읽어 반환한다. */
    public static byte[] readStream(DirectoryEntry dir, String name) throws IOException {
        DocumentEntry entry = (DocumentEntry) dir.getEntry(name);
        int size = entry.getSize();
        byte[] data = new byte[size];
        try (DocumentInputStream dis = new DocumentInputStream(entry)) {
            dis.readFully(data);
        }
        return data;
    }

    /** raw deflate 로 압축된 byte 를 해제한다 ({@link ZlibCompressor#decompress} 위임). */
    public static byte[] decompress(byte[] data) throws IOException {
        return ZlibCompressor.decompress(data);
    }
}
