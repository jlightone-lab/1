package kr.n.nframe.test;

import org.apache.poi.poifs.filesystem.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public class DistDecryptTest2 {
    public static void main(String[] args) throws Exception {
        String distPath = args[0];
        String password = args[1];

        // AES 키 계산: SHA1(password ASCII) → 대문자 hex → UTF-16LE → 처음 16바이트
        byte[] sha1Bin = MessageDigest.getInstance("SHA-1").digest(
                password.getBytes(StandardCharsets.US_ASCII));
        StringBuilder hexSb = new StringBuilder();
        for (byte b : sha1Bin) hexSb.append(String.format("%02X", b & 0xFF));
        byte[] sha1Utf16le = hexSb.toString().getBytes(StandardCharsets.UTF_16LE);
        byte[] aesKey = new byte[16];
        System.arraycopy(sha1Utf16le, 0, aesKey, 0, 16);
        System.out.print("AES key: ");
        for (byte b : aesKey) System.out.printf("%02X ", b & 0xFF);
        System.out.println();

        POIFSFileSystem fs = new POIFSFileSystem(new FileInputStream(distPath));
        DirectoryEntry vt = (DirectoryEntry) fs.getRoot().getEntry("ViewText");
        byte[] viewData = StreamExtractor.readStream(vt, "Section0");

        // DISTRIBUTE_DOC_DATA 레코드 건너뛰기 (헤더 4 + 페이로드 256 = 260바이트)
        byte[] encrypted = new byte[viewData.length - 260];
        System.arraycopy(viewData, 260, encrypted, 0, encrypted.length);
        System.out.println("Encrypted: " + encrypted.length + " bytes");

        // 복호화
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
        byte[] decrypted = cipher.doFinal(encrypted);

        System.out.print("Decrypted first 32: ");
        for (int i = 0; i < 32; i++) System.out.printf("%02X ", decrypted[i] & 0xFF);
        System.out.println();

        // 압축 해제 시도
        try {
            byte[] decompressed = StreamExtractor.decompress(decrypted);
            System.out.println("Decompressed: " + decompressed.length + " bytes → SUCCESS!");
            // 첫 레코드 파싱
            int h = le32(decompressed, 0);
            System.out.printf("First record: tag=0x%03X level=%d size=%d%n",
                    h & 0x3FF, (h>>10)&0x3FF, (h>>20)&0xFFF);
        } catch (Exception e) {
            System.out.println("Decompress failed: " + e.getMessage());
        }
        fs.close();
    }
    static int le32(byte[] d, int o) { return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24); }
}
