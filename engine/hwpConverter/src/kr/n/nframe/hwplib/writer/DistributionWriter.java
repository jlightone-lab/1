package kr.n.nframe.hwplib.writer;

import kr.n.nframe.hwplib.binary.HwpBinaryWriter;
import kr.n.nframe.hwplib.binary.RecordWriter;
import kr.n.nframe.hwplib.constants.HwpTagId;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.Inflater;

/**
 * 일반 HWP 파일을 배포용(Distribution) HWP 파일로 변환한다.
 *
 * <p>배포용 문서 구조:
 * <ul>
 *   <li>FileHeader properties bit 2 설정</li>
 *   <li>BodyText/SectionN 스트림이 ViewText/SectionN 로 이동</li>
 *   <li>각 ViewText 스트림 앞에 HWPTAG_DISTRIBUTE_DOC_DATA 레코드(256 byte)</li>
 *   <li>스트림 데이터는 AES-128 ECB 로 암호화</li>
 *   <li>암호화 키는 SHA1 + MSVC srand/rand XOR 스킴으로 비밀번호에서 유도</li>
 * </ul>
 *
 * <p>참고: 한글문서파일형식_배포용문서_revision1.2.pdf
 */
public class DistributionWriter {

    private DistributionWriter() {}

    /**
     * 일반 HWP 파일을 배포용 HWP 파일로 변환한다.
     *
     * @param inputPath  일반 HWP 파일 경로
     * @param outputPath 배포용 HWP 출력 경로
     * @param password   암호화에 사용할 비밀번호 (AES 키 유도 입력)
     * @param noCopy     복사 방지 활성화 여부
     * @param noPrint    인쇄 방지 활성화 여부
     */
    public static void makeDistribution(String inputPath, String outputPath,
                                         String password, boolean noCopy, boolean noPrint)
            throws Exception {
        if (password == null) {
            throw new IllegalArgumentException("password must not be null");
        }
        // 입력==출력 자기 덮어쓰기 차단: 변환 중 POIFS 가 원본을 읽는 도중
        // 같은 파일을 쓰면 원본을 영구 파괴하고 일부 OS 에서는 파일 핸들 충돌.
        java.nio.file.Path inPath = java.nio.file.Paths.get(inputPath);
        java.nio.file.Path outPath = java.nio.file.Paths.get(outputPath);
        try {
            if (java.nio.file.Files.exists(inPath) && java.nio.file.Files.exists(outPath)
                    && java.nio.file.Files.isSameFile(inPath, outPath)) {
                throw new IllegalArgumentException(
                        "input and output are the same file: " + inputPath);
            }
        } catch (java.nio.file.NoSuchFileException ignored) {
            // 출력 파일이 아직 없으면 OK
        }
        // 경로 비교 폴백 (isSameFile 이 예외를 던진 경우)
        if (inPath.toAbsolutePath().normalize().equals(outPath.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(
                    "input and output resolve to the same path: " + inputPath);
        }

        // 입력 HWP 파일 읽기 — try-with-resources 로 자원 누수 방지
        try (FileInputStream fis = new FileInputStream(inputPath);
             POIFSFileSystem inputFs = new POIFSFileSystem(fis);
             POIFSFileSystem outputFs = new POIFSFileSystem()) {
            makeDistributionInternal(inputFs, outputFs, outputPath, password, noCopy, noPrint);
        }
    }

    private static void makeDistributionInternal(POIFSFileSystem inputFs, POIFSFileSystem outputFs,
                                                 String outputPath, String password,
                                                 boolean noCopy, boolean noPrint) throws Exception {
        DirectoryEntry inputRoot = inputFs.getRoot();
        DirectoryEntry outputRoot = outputFs.getRoot();

        // 1. Derive encryption key from password
        byte[] sha1Hash = computeSHA1(password);
        byte[] aesKey = new byte[16];
        System.arraycopy(sha1Hash, 0, aesKey, 0, 16);

        // 2. Build option flags (bit 15 = distribution active, bit 0 = noCopy, bit 1 = noPrint)
        int optionFlags = 0x8000; // 배포용 활성 플래그 (항상 설정)
        if (noCopy) optionFlags |= 0x01;
        if (noPrint) optionFlags |= 0x02;

        // 3. Build DISTRIBUTE_DOC_DATA record (prepended to each ViewText stream)
        byte[] distPayload = buildDistributeDocData(sha1Hash, optionFlags);
        byte[] distRecord = RecordWriter.buildRecord(HwpTagId.DISTRIBUTE_DOC_DATA, 0, distPayload);

        // 4. Copy FileHeader with bit 2 set
        byte[] fileHeader = readStream(inputRoot, "FileHeader");
        int props = readLE32(fileHeader, 36);
        props |= 0x04; // bit 2 = distribution document
        writeLE32(fileHeader, 36, props);
        outputRoot.createDocument("FileHeader", new ByteArrayInputStream(fileHeader));

        // 5. Copy DocInfo as-is (no DISTRIBUTE_DOC_DATA here)
        byte[] docInfo = readStream(inputRoot, "DocInfo");
        outputRoot.createDocument("DocInfo", new ByteArrayInputStream(docInfo));

        // 6. + 7. BodyText 와 ViewText 생성
        //
        // 이전 버전(v13.3 이하)은 원본 HWP의 평문 BodyText 를 그대로 출력에
        // 복사했다. FileHeader bit 2(배포용 플래그)는 켜지지만 BodyText 스트림
        // 은 암호화되지 않은 채로 남아서, hwplib 같은 외부 라이브러리가 암호
        // 없이 본문을 복원할 수 있었다(DRM 우회). v13.4 에서는 BodyText 에
        // 원본 Section 이 아닌 "빈 Section(최소 더미 바이트)" 만 넣어
        // 구조 호환성은 유지하면서 평문 유출은 차단한다. 실제 본문은 ViewText
        // 에만 암호화되어 존재한다.
        if (inputRoot.hasEntry("BodyText")) {
            DirectoryEntry bodyText = (DirectoryEntry) inputRoot.getEntry("BodyText");
            DirectoryEntry viewText = outputRoot.createDirectory("ViewText");
            DirectoryEntry dummyBody = outputRoot.createDirectory("BodyText");

            // 빈 섹션 더미 : 압축된 빈 raw-deflate 스트림 2 byte.
            // FileHeader.properties bit 0 (compressed) 이 켜진 배포용 HWP 에서는
            // 모든 Section 스트림이 raw-deflate 으로 inflate 가능해야 한다.
            // v13.4~v15.2 의 4-byte 영(0) 더미는 raw-deflate 으로 해석되지 않아
            // BinData 가 없는 파일(예: 입찰공고문.hwpx)에서 한/글이 BodyText 를
            // 먼저 inflate 하다가 실패해 "파일이 손상되었습니다." 로 거부했다.
            // {0x03,0x00} = BFINAL=1 + BTYPE=01 fixed-Huffman + 즉시 end-of-block
            //  → inflate 결과 0 byte (한/글은 본문이 ViewText 에 있다고 인식하고
            //    fallback). hwplib 등 외부 라이브러리는 "No content / no paragraphs"
            //    수준의 깔끔한 실패를 받는다 (v13.4 의 DRM 평문 차단 의도 유지).
            byte[] emptySectionDummy = new byte[]{0x03, 0x00};

            for (Entry entry : bodyText) {
                if (entry instanceof DocumentEntry) {
                    String name = entry.getName();
                    byte[] sectionData = readStream(bodyText, name);

                    // ViewText : AES-128 ECB 암호화 + DISTRIBUTE_DOC_DATA 프리픽스
                    byte[] encrypted = encryptAES128ECB(sectionData, aesKey);
                    byte[] viewData = new byte[distRecord.length + encrypted.length];
                    System.arraycopy(distRecord, 0, viewData, 0, distRecord.length);
                    System.arraycopy(encrypted, 0, viewData, distRecord.length, encrypted.length);
                    viewText.createDocument(name, new ByteArrayInputStream(viewData));

                    // BodyText : 원본 평문 대신 빈 더미만 기록 (DRM 유출 방지)
                    dummyBody.createDocument(name, new ByteArrayInputStream(emptySectionDummy));
                }
            }
        }

        // 8. Encrypt Scripts streams too
        if (inputRoot.hasEntry("Scripts")) {
            DirectoryEntry srcScripts = (DirectoryEntry) inputRoot.getEntry("Scripts");
            DirectoryEntry dstScripts = outputRoot.createDirectory("Scripts");
            for (Entry entry : srcScripts) {
                if (entry instanceof DocumentEntry) {
                    byte[] data = readStream(srcScripts, entry.getName());
                    byte[] encrypted = encryptAES128ECB(data, aesKey);
                    // Scripts 에도 DISTRIBUTE_DOC_DATA 프리픽스 부여
                    byte[] scriptData = new byte[distRecord.length + encrypted.length];
                    System.arraycopy(distRecord, 0, scriptData, 0, distRecord.length);
                    System.arraycopy(encrypted, 0, scriptData, distRecord.length, encrypted.length);
                    dstScripts.createDocument(entry.getName(), new ByteArrayInputStream(scriptData));
                }
            }
        }

        // 9. Copy other streams as-is
        copyEntryIfExists(inputRoot, outputRoot, "DocOptions");
        copyEntryIfExists(inputRoot, outputRoot, "BinData");

        // 요약 정보와 미리보기 복사
        for (Entry entry : inputRoot) {
            String name = entry.getName();
            if (name.startsWith("\u0005") || name.equals("PrvText") || name.equals("PrvImage")) {
                if (entry instanceof DocumentEntry) {
                    byte[] data = readStream(inputRoot, name);
                    outputRoot.createDocument(name, new ByteArrayInputStream(data));
                }
            }
        }

        // 10. Write output (try-with-resources 로 예외 경로에서도 fos 닫힘 보장)
        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            outputFs.writeFilesystem(fos);
        }
        // inputFs / outputFs close 는 호출자(try-with-resources)가 담당.
    }

    /**
     * Build the 256-byte DISTRIBUTE_DOC_DATA payload.
     * 구조:
     * - Bytes 0-3: Seed (cleartext UINT32)
     * - Remaining 252 bytes: XOR-encoded hash + flags
     */
    static byte[] buildDistributeDocData(byte[] sha1Hash, int optionFlags) {
        // 난수 seed 생성
        int seed = (int) (System.nanoTime() & 0xFFFFFFFFL);

        // Build 256-byte random array using MSVC srand/rand
        byte[] randomArray = buildRandomArray(seed);

        // 해시가 배치될 offset 계산
        int offset = (seed & 0x0F) + 4;

        // payload 구성: 해시·플래그를 정확한 위치에 배치
        byte[] payload = new byte[256];
        // 우선 난수로 채움
        for (int i = 0; i < 256; i++) {
            payload[i] = (byte) ((i * 37 + seed) & 0xFF);
        }

        // SHA1 해시 배치 (80 byte = hex 문자열을 UTF-16LE 로 인코딩)
        // sha1Hash 는 computeSHA1() 에서 이미 80 byte
        System.arraycopy(sha1Hash, 0, payload, offset, Math.min(sha1Hash.length, 256 - offset));

        // 옵션 플래그를 해시 뒤 2 byte 에 배치
        if (offset + 80 < 256) {
            payload[offset + 80] = (byte) (optionFlags & 0xFF);
            payload[offset + 81] = (byte) ((optionFlags >> 8) & 0xFF);
        }

        // 난수 배열과 XOR
        for (int i = 0; i < 256; i++) {
            payload[i] ^= randomArray[i];
        }

        // seed 를 첫 4 byte 에 평문으로 기록 (XOR 결과를 덮어씀)
        writeLE32(payload, 0, seed);

        return payload;
    }

    /**
     * Build 256-byte random array using MSVC srand/rand algorithm.
     */
    public static byte[] buildRandomArray(int seed) {
        byte[] array = new byte[256];
        int[] state = {seed}; // MSVC rand 용 가변 state

        int pos = 0;
        while (pos < 256) {
            int a = msvcRand(state) & 0xFF;       // value (odd call)
            int b = (msvcRand(state) & 0x0F) + 1; // count (even call), 1-16
            for (int i = 0; i < b && pos < 256; i++) {
                array[pos++] = (byte) a;
            }
        }
        return array;
    }

    /**
     * MSVC Visual C++ rand() implementation.
     * state = state * 214013 + 2531011
     * return (state >>> 16) & 0x7FFF
     */
    static int msvcRand(int[] state) {
        state[0] = state[0] * 214013 + 2531011;
        return (state[0] >>> 16) & 0x7FFF;
    }

    /**
     * Compute SHA1 hash of password and return as UTF-16LE encoded hex string (80 bytes).
     * Process: SHA1(password ASCII bytes) → hex uppercase string (40 chars) → UTF-16LE (80 bytes)
     * The first 16 bytes of this 80-byte result are used as the AES-128 key.
     */
    static byte[] computeSHA1(String password) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] hash = md.digest(password.getBytes(StandardCharsets.US_ASCII));
        // 대문자 hex 문자열로 변환
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02X", b & 0xFF));
        // hex 문자열을 UTF-16LE 로 인코딩 (80 byte)
        return hex.toString().getBytes(StandardCharsets.UTF_16LE);
    }

    /**
     * AES-128 ECB encryption. Data padded to 16-byte boundary with zeros.
     */
    static byte[] encryptAES128ECB(byte[] data, byte[] key) throws Exception {
        // Pad to 16-byte boundary
        int padded = ((data.length + 15) / 16) * 16;
        byte[] paddedData = new byte[padded];
        System.arraycopy(data, 0, paddedData, 0, data.length);

        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        return cipher.doFinal(paddedData);
    }

    // --- Utility methods ---

    /** 단일 OLE2 스트림 최대 크기 (512 MB). 이를 초과하면 악성 입력으로 간주하고 거부. */
    private static final int MAX_OLE_STREAM_BYTES = 512 * 1024 * 1024;

    static byte[] readStream(DirectoryEntry dir, String name) throws Exception {
        DocumentEntry entry = (DocumentEntry) dir.getEntry(name);
        int size = entry.getSize();
        if (size < 0 || size > MAX_OLE_STREAM_BYTES) {
            throw new IOException("OLE stream size out of range: " + name + " (" + size + ")");
        }
        byte[] data = new byte[size];
        try (DocumentInputStream dis = new DocumentInputStream(entry)) {
            dis.readFully(data);
        }
        return data;
    }

    static void copyEntryIfExists(DirectoryEntry src, DirectoryEntry dst, String name) throws Exception {
        if (!src.hasEntry(name)) return;
        Entry entry = src.getEntry(name);
        if (entry instanceof DirectoryEntry) {
            DirectoryEntry srcDir = (DirectoryEntry) entry;
            DirectoryEntry dstDir = dst.createDirectory(name);
            for (Entry child : srcDir) {
                if (child instanceof DocumentEntry) {
                    byte[] data = readStream(srcDir, child.getName());
                    dstDir.createDocument(child.getName(), new ByteArrayInputStream(data));
                }
            }
        } else if (entry instanceof DocumentEntry) {
            byte[] data = readStream(src, name);
            dst.createDocument(name, new ByteArrayInputStream(data));
        }
    }

    static int readLE32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    static void writeLE32(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
