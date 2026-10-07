package kr.n.nframe.test;

import org.apache.poi.poifs.filesystem.*;
import java.io.*;
import java.util.zip.*;

/**
 * Section0에서 손상을 일으키는 정확한 레코드를 이진 탐색으로 찾는다.
 *
 * 전략: reference Section0의 레코드 [0..N-1] + 우리 레코드 [N..end]를 합쳐서
 * 재압축하고 HWP 파일을 만든 뒤 유효한지 확인한다.
 *
 * 여기서 "유효"란: 파일의 Section0이 압축 해제되고 모든 레코드가 파싱 가능한 상태를 뜻한다.
 * N = 0, 중간값 등으로 파일을 생성하여 손상 지점을 이진 탐색한다.
 */
public class SectionBisect {

    public static void main(String[] args) throws Exception {
        String refPath = "hwp/test_hwpx2_conv.hwp";
        String outPath = "output/test_hwpx2_conv.hwp";

        POIFSFileSystem refPoi = new POIFSFileSystem(new FileInputStream(refPath));
        POIFSFileSystem outPoi = new POIFSFileSystem(new FileInputStream(outPath));

        byte[] refFH = BinaryIsolator.readStream(refPoi, "FileHeader");
        byte[] refDI = BinaryIsolator.readStream(refPoi, "DocInfo");
        byte[] refSecRaw = BinaryIsolator.readStream(refPoi, "BodyText", "Section0");
        byte[] outSecRaw = BinaryIsolator.readStream(outPoi, "BodyText", "Section0");

        byte[] refSec = decompress(refSecRaw);
        byte[] outSec = decompress(outSecRaw);

        // 레코드로 파싱 (헤더 포함 원시 바이트)
        int[] refOffsets = getRecordOffsets(refSec);
        int[] outOffsets = getRecordOffsets(outSec);

        System.out.println("ref records: " + (refOffsets.length - 1) + " out records: " + (outOffsets.length - 1));

        // 테스트 파일 생성: ref 레코드 [0..N-1]를 쓰고 이후부터는 out 레코드 사용
        // 주의: 레코드 개수가 다르므로 레코드 경계에서 원시 바이트를 이어 붙인다
        int[] testPoints = {0, 10, 20, 30, 50, 62, 63, 64, 65, 100, 500, 1000};

        for (int n : testPoints) {
            if (n > refOffsets.length - 1 || n > outOffsets.length - 1) continue;

            // 처음 N개 레코드는 ref에서, 나머지는 out에서 가져온다
            ByteArrayOutputStream combined = new ByteArrayOutputStream();
            combined.write(refSec, 0, refOffsets[n]); // [0..N-1] 레코드에 해당하는 ref 바이트
            combined.write(outSec, outOffsets[n], outSec.length - outOffsets[n]); // 레코드 N부터의 out 바이트

            byte[] compressedSec = compress(combined.toByteArray());
            String testFile = "output/bisect_" + String.format("%05d", n) + ".hwp";
            BinaryIsolator.buildFile(testFile, refPoi, refFH, refDI, compressedSec);
        }

        System.out.println("Generated bisect files. Open each in Hangul:");
        for (int n : testPoints) {
            if (n > refOffsets.length - 1) continue;
            System.out.println("  bisect_" + String.format("%05d", n) + ".hwp = ref[0.." + (n-1) + "] + out[" + n + "..]");
        }

        refPoi.close();
        outPoi.close();
    }

    static int[] getRecordOffsets(byte[] data) {
        java.util.List<Integer> offsets = new java.util.ArrayList<>();
        int pos = 0;
        while (pos + 4 <= data.length) {
            offsets.add(pos);
            int h = (data[pos]&0xFF)|((data[pos+1]&0xFF)<<8)|((data[pos+2]&0xFF)<<16)|((data[pos+3]&0xFF)<<24);
            pos += 4;
            int sz = (h >> 20) & 0xFFF;
            if (sz == 0xFFF) {
                sz = (data[pos]&0xFF)|((data[pos+1]&0xFF)<<8)|((data[pos+2]&0xFF)<<16)|((data[pos+3]&0xFF)<<24);
                pos += 4;
            }
            pos += sz;
        }
        offsets.add(pos); // 끝 표시 sentinel
        return offsets.stream().mapToInt(Integer::intValue).toArray();
    }

    static byte[] compress(byte[] data) throws IOException {
        Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        def.setInput(data);
        def.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        byte[] buf = new byte[8192];
        while (!def.finished()) {
            int n = def.deflate(buf);
            out.write(buf, 0, n);
        }
        def.end();
        return out.toByteArray();
    }

    static byte[] decompress(byte[] raw) throws Exception {
        Inflater inf = new Inflater(true);
        inf.setInput(raw);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length * 4);
        byte[] buf = new byte[8192];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n == 0 && inf.needsInput()) break;
            out.write(buf, 0, n);
        }
        inf.end();
        return out.toByteArray();
    }
}
