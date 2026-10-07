package kr.n.nframe.test;

import org.apache.poi.poifs.filesystem.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * 불필요한 PARA_LINE_SEG 3개를 제거한 우리 Section0으로 테스트 파일을 생성한다.
 * reference에서는 paragraph 2243, 2244, 2245의 lineSegCount=0이다.
 */
public class FixedSectionTest {

    public static void main(String[] args) throws Exception {
        POIFSFileSystem refPoi = new POIFSFileSystem(new FileInputStream("hwp/test_hwpx2_conv.hwp"));
        POIFSFileSystem outPoi = new POIFSFileSystem(new FileInputStream("output/test_hwpx2_conv.hwp"));

        byte[] refFH = BinaryIsolator.readStream(refPoi, "FileHeader");
        byte[] refDI = BinaryIsolator.readStream(refPoi, "DocInfo");
        byte[] outSecRaw = BinaryIsolator.readStream(outPoi, "BodyText", "Section0");

        // 우리 Section0 압축 해제
        byte[] outSec = decompress(outSecRaw);

        // 레코드로 파싱
        List<int[]> recordBounds = new ArrayList<>(); // [start, end] 쌍
        int pos = 0;
        int paraIdx = 0;
        Set<Integer> skipRecords = new HashSet<>(); // 건너뛸 레코드 인덱스
        List<Integer> paraHeaderIndices = new ArrayList<>();

        // 1차 순회: 레코드 경계 찾기
        List<byte[]> records = new ArrayList<>();
        int recIdx = 0;
        pos = 0;
        while (pos + 4 <= outSec.length) {
            int hStart = pos;
            int h = readInt(outSec, pos); pos += 4;
            int tag = h & 0x3FF;
            int lv = (h >> 10) & 0x3FF;
            int sz = (h >> 20) & 0xFFF;
            if (sz == 0xFFF) { sz = readInt(outSec, pos); pos += 4; }

            records.add(Arrays.copyOfRange(outSec, hStart, pos + sz));

            if (tag == 0x042) { // PARA_HEADER
                paraHeaderIndices.add(recIdx);
            }

            pos += sz;
            recIdx++;
        }

        // paragraph 2243-2245와 해당 PARA_LINE_SEG 레코드 찾기
        Set<Integer> targetParas = new HashSet<>(Arrays.asList(2243, 2244, 2245));
        for (int pidx : targetParas) {
            if (pidx >= paraHeaderIndices.size()) continue;
            int phRecIdx = paraHeaderIndices.get(pidx);
            byte[] phRec = records.get(phRecIdx);

            // PARA_HEADER를 파싱하여 level 추출
            int hdr = readInt(phRec, 0);
            int phLv = (hdr >> 10) & 0x3FF;

            // 이 paragraph에 속하는 level+1의 PARA_LINE_SEG 찾기
            for (int j = phRecIdx + 1; j < records.size(); j++) {
                int jh = readInt(records.get(j), 0);
                int jTag = jh & 0x3FF;
                int jLv = (jh >> 10) & 0x3FF;
                if (jTag == 0x042 && jLv <= phLv) break; // 다음 paragraph
                if (jTag == 0x045 && jLv == phLv + 1) { // PARA_LINE_SEG
                    skipRecords.add(j);
                    System.out.println("Skip record " + j + " (PARA_LINE_SEG for para " + pidx + ")");

                    // PARA_HEADER의 lineSegCount도 0으로 수정
                    byte[] fixed = records.get(phRecIdx).clone();
                    // lineSegCount는 데이터의 byte 4+16=20 위치 (4바이트 헤더 이후)
                    // 헤더 4바이트, 그 다음부터 데이터 시작
                    int dataStart = 4;
                    if (((readInt(fixed, 0) >> 20) & 0xFFF) == 0xFFF) dataStart = 8;
                    fixed[dataStart + 16] = 0;
                    fixed[dataStart + 17] = 0;
                    records.set(phRecIdx, fixed);
                    break;
                }
            }
        }

        // 건너뛴 레코드를 제외하고 Section0 재구성
        ByteArrayOutputStream newSec = new ByteArrayOutputStream();
        for (int i = 0; i < records.size(); i++) {
            if (skipRecords.contains(i)) continue;
            newSec.write(records.get(i));
        }

        System.out.println("Original records: " + records.size() +
                           " Fixed: " + (records.size() - skipRecords.size()));

        // 압축
        byte[] compressed = compress(newSec.toByteArray());

        // ref DocInfo + 수정된 Section0으로 테스트 파일 생성
        BinaryIsolator.buildFile("output/isolate_D_fixed.hwp", refPoi, refFH, refDI, compressed);
        System.out.println("Created output/isolate_D_fixed.hwp");

        // 우리 DocInfo + 수정된 Section0으로도 생성
        byte[] outDI = BinaryIsolator.readStream(outPoi, "DocInfo");
        BinaryIsolator.buildFile("output/isolate_E_fixed.hwp", refPoi, refFH, outDI, compressed);
        System.out.println("Created output/isolate_E_fixed.hwp");

        // 전부 우리 것으로도 생성 (우리 FH + 우리 DI + 수정된 Section0)
        byte[] outFH = BinaryIsolator.readStream(outPoi, "FileHeader");
        BinaryIsolator.buildFile("output/isolate_F_fixed.hwp", refPoi, outFH, outDI, compressed);
        System.out.println("Created output/isolate_F_fixed.hwp");

        refPoi.close();
        outPoi.close();
    }

    static int readInt(byte[] d, int o) {
        return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24);
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

    static byte[] compress(byte[] data) throws Exception {
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
}
