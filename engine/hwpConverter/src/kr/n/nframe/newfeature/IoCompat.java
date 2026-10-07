package kr.n.nframe.newfeature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Java 8 호환 IO 유틸. InputStream.readAllBytes() 는 Java 9+ 이므로
 * --release 8 빌드에서 쓸 수 없어, ByteArrayOutputStream + read(buf) 반복으로
 * 동일 동작(스트림을 끝까지 읽어 전체 바이트 반환)을 제공한다.
 */
public final class IoCompat {

    private IoCompat() {}

    /** 스트림을 끝까지 읽어 전체 바이트를 반환한다 (InputStream.readAllBytes 대체). */
    public static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
