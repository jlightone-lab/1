package kr.n.nframe.test;

import org.apache.poi.poifs.filesystem.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * 자동 이진 탐색: reference Section0 레코드를 우리 레코드로 점진적으로 교체하여
 * 정확한 손상 지점을 찾는다.
 *
 * ref DocInfo (정상 확인됨) + ref BinData 사용.
 * 처음 N개 레코드는 REFERENCE에서, 나머지는 OUTPUT에서 가져와 파일을 생성한다.
 */
public class AutoBisect {
    public static void main(String[] args) throws Exception {
        POIFSFileSystem refPoi = new POIFSFileSystem(new FileInputStream("hwp/test_hwpx2_conv.hwp"));
        POIFSFileSystem outPoi = new POIFSFileSystem(new FileInputStream("output/test_hwpx2_conv.hwp"));

        byte[] refFH = BinaryIsolator.readStream(refPoi, "FileHeader");
        byte[] refDI = BinaryIsolator.readStream(refPoi, "DocInfo");

        byte[] refSec = decomp(BinaryIsolator.readStream(refPoi, "BodyText", "Section0"));
        byte[] outSec = decomp(BinaryIsolator.readStream(outPoi, "BodyText", "Section0"));

        int[] refOff = offsets(refSec);
        int[] outOff = offsets(outSec);

        System.out.println("ref=" + (refOff.length-1) + " out=" + (outOff.length-1) + " records");

        // 테스트 지점: 0부터 N까지는 ref 레코드, N 이후부터는 out 레코드
        // ref 레코드 경계와 out 레코드 경계를 사용한다
        int[] points = {0, 1, 5, 10, 20, 50, 62, 63, 64, 65, 100, 200, 500, 1000, 5000, 10000, 15000, refOff.length-1};

        for (int n : points) {
            if (n >= refOff.length || n >= outOff.length) continue;
            byte[] combined;
            if (n == 0) {
                combined = outSec;
            } else if (n >= refOff.length - 1) {
                combined = refSec;
            } else {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bos.write(refSec, 0, refOff[n]);
                bos.write(outSec, outOff[n], outSec.length - outOff[n]);
                combined = bos.toByteArray();
            }
            byte[] comp = comp(combined);
            String fname = String.format("output/bisect_%05d.hwp", n);
            BinaryIsolator.buildFile(fname, refPoi, refFH, refDI, comp);
        }

        refPoi.close();
        outPoi.close();
        System.out.println("Done. Open files in Hangul from highest N downwards.");
        System.out.println("bisect_18472 = all ref (should open)");
        System.out.println("bisect_00000 = all out (won't open)");
        System.out.println("Find the boundary where it stops opening.");
    }

    static int[] offsets(byte[] data) {
        List<Integer> off = new ArrayList<>();
        int pos = 0;
        while (pos+4 <= data.length) {
            off.add(pos);
            int h = ri(data,pos); pos+=4;
            int sz=(h>>20)&0xFFF;
            if(sz==0xFFF){sz=ri(data,pos);pos+=4;}
            pos+=sz;
        }
        off.add(pos);
        return off.stream().mapToInt(Integer::intValue).toArray();
    }

    static int ri(byte[] d,int o){return(d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24);}

    static byte[] decomp(byte[] raw) throws Exception {
        Inflater i=new Inflater(true);i.setInput(raw);
        ByteArrayOutputStream o=new ByteArrayOutputStream(raw.length*4);
        byte[] b=new byte[8192];while(!i.finished()){int n=i.inflate(b);if(n==0&&i.needsInput())break;o.write(b,0,n);}
        i.end();return o.toByteArray();
    }

    static byte[] comp(byte[] data) throws Exception {
        Deflater d=new Deflater(Deflater.DEFAULT_COMPRESSION,true);d.setInput(data);d.finish();
        ByteArrayOutputStream o=new ByteArrayOutputStream(data.length);
        byte[] b=new byte[8192];while(!d.finished()){int n=d.deflate(b);o.write(b,0,n);}
        d.end();return o.toByteArray();
    }
}
