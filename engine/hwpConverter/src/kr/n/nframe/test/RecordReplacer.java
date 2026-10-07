package kr.n.nframe.test;

import org.apache.poi.poifs.filesystem.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * reference Section0에서 시작하여, 레코드 그룹을 하나씩 우리 데이터로 교체한다.
 * 어떤 레코드 타입이 손상을 일으키는지 정확히 찾아낸다.
 *
 * 그룹: PARA_HDR, PARA_TXT, PARA_CS, PARA_LS, CTRL_HDR, LIST_HDR, TABLE 등.
 * 각 그룹마다 해당 타입의 모든 레코드를 교체하고 테스트한다.
 */
public class RecordReplacer {

    static final Map<Integer,String> NM = new HashMap<>();
    static {
        NM.put(0x042,"PARA_HDR"); NM.put(0x043,"PARA_TXT"); NM.put(0x044,"PARA_CS");
        NM.put(0x045,"PARA_LS"); NM.put(0x047,"CTRL_HDR"); NM.put(0x048,"LIST_HDR");
        NM.put(0x049,"PAGE_DEF"); NM.put(0x04A,"FN_SHAPE"); NM.put(0x04B,"PG_BF");
        NM.put(0x04C,"SHAPE_CMP"); NM.put(0x04D,"TABLE"); NM.put(0x055,"PICTURE");
    }

    public static void main(String[] args) throws Exception {
        POIFSFileSystem refPoi = new POIFSFileSystem(new FileInputStream("hwp/test_hwpx2_conv.hwp"));
        POIFSFileSystem outPoi = new POIFSFileSystem(new FileInputStream("output/test_hwpx2_conv.hwp"));

        byte[] refFH = BinaryIsolator.readStream(refPoi, "FileHeader");
        byte[] refDI = BinaryIsolator.readStream(refPoi, "DocInfo");
        byte[] refSec = decomp(BinaryIsolator.readStream(refPoi, "BodyText", "Section0"));
        byte[] outSec = decomp(BinaryIsolator.readStream(outPoi, "BodyText", "Section0"));

        List<byte[]> refRecs = parseRawRecords(refSec);
        List<byte[]> outRecs = parseRawRecords(outSec);
        List<int[]> refTags = parseTags(refSec);
        List<int[]> outTags = parseTags(outSec);

        System.out.println("ref=" + refRecs.size() + " out=" + outRecs.size());

        // 레코드 개수가 다르므로 ref를 기준(base)으로 삼고
        // 매칭되는 레코드만 선택적으로 우리 데이터로 교체한다.

        // 전략: 전체 ref 레코드(정상 확인됨)에서 시작.
        // 한 번에 하나의 TYPE만 우리 데이터로 교체한다.
        // 테스트할 타입: PARA_HDR, PARA_TXT, PARA_CS, PARA_LS, CTRL_HDR 등.

        int[] typesToTest = {0x042, 0x043, 0x044, 0x045, 0x047, 0x048, 0x04D, 0x04C, 0x055, 0x049, 0x04A, 0x04B};

        for (int targetTag : typesToTest) {
            // section 구성: ref 레코드 기반에서 targetTag 타입의 레코드를 out 데이터로 교체
            // 각 tag 타입 내에서 순차 인덱스로 매칭
            Map<Integer, List<Integer>> refByTag = groupByTag(refTags);
            Map<Integer, List<Integer>> outByTag = groupByTag(outTags);

            List<Integer> refIndices = refByTag.getOrDefault(targetTag, Collections.emptyList());
            List<Integer> outIndices = outByTag.getOrDefault(targetTag, Collections.emptyList());

            ByteArrayOutputStream combined = new ByteArrayOutputStream();
            Map<Integer, byte[]> replacements = new HashMap<>();

            int minCount = Math.min(refIndices.size(), outIndices.size());
            for (int k = 0; k < minCount; k++) {
                replacements.put(refIndices.get(k), outRecs.get(outIndices.get(k)));
            }

            for (int i = 0; i < refRecs.size(); i++) {
                if (replacements.containsKey(i)) {
                    combined.write(replacements.get(i));
                } else {
                    combined.write(refRecs.get(i));
                }
            }

            byte[] comp = comp(combined.toByteArray());
            String tagName = NM.getOrDefault(targetTag, String.format("0x%03X", targetTag));
            String fname = "output/replace_" + tagName + ".hwp";
            BinaryIsolator.buildFile(fname, refPoi, refFH, refDI, comp);

            // 교체된 레코드 개수 세기
            int replaced = replacements.size();
            int different = 0;
            for (Map.Entry<Integer, byte[]> e : replacements.entrySet()) {
                if (!Arrays.equals(refRecs.get(e.getKey()), e.getValue())) different++;
            }
            System.out.println(tagName + ": replaced " + replaced + " records (" + different + " actually differ) -> " + fname);
        }

        refPoi.close();
        outPoi.close();
        System.out.println("\nOpen each file in Hangul. The one that fails tells you which record type is broken.");
    }

    static List<byte[]> parseRawRecords(byte[] data) {
        List<byte[]> recs = new ArrayList<>();
        int pos = 0;
        while (pos + 4 <= data.length) {
            int rstart = pos;
            int h = ri(data, pos); pos += 4;
            int sz = (h >> 20) & 0xFFF;
            if (sz == 0xFFF) { sz = ri(data, pos); pos += 4; }
            pos += sz;
            recs.add(Arrays.copyOfRange(data, rstart, pos));
        }
        return recs;
    }

    static List<int[]> parseTags(byte[] data) {
        List<int[]> tags = new ArrayList<>();
        int pos = 0;
        while (pos + 4 <= data.length) {
            int h = ri(data, pos); pos += 4;
            int tag = h & 0x3FF; int lv = (h >> 10) & 0x3FF; int sz = (h >> 20) & 0xFFF;
            if (sz == 0xFFF) { sz = ri(data, pos); pos += 4; }
            tags.add(new int[]{tag, lv, sz});
            pos += sz;
        }
        return tags;
    }

    static Map<Integer, List<Integer>> groupByTag(List<int[]> tags) {
        Map<Integer, List<Integer>> map = new HashMap<>();
        for (int i = 0; i < tags.size(); i++) {
            map.computeIfAbsent(tags.get(i)[0], k -> new ArrayList<>()).add(i);
        }
        return map;
    }

    static int ri(byte[] d, int o) { return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24); }

    static byte[] decomp(byte[] raw) throws Exception {
        Inflater i = new Inflater(true); i.setInput(raw);
        ByteArrayOutputStream o = new ByteArrayOutputStream(raw.length*4);
        byte[] b = new byte[8192]; while(!i.finished()){int n=i.inflate(b);if(n==0&&i.needsInput())break;o.write(b,0,n);}
        i.end(); return o.toByteArray();
    }

    static byte[] comp(byte[] data) throws Exception {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true); d.setInput(data); d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream(data.length);
        byte[] b = new byte[8192]; while(!d.finished()){int n=d.deflate(b);o.write(b,0,n);}
        d.end(); return o.toByteArray();
    }
}
