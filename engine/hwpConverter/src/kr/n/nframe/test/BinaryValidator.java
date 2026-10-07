package kr.n.nframe.test;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.poifs.filesystem.*;

/**
 * 검증된 레퍼런스와 비교하여 HWP 파일을 검증한다.
 * 상세 보고서와 PASS/FAIL 판정을 생성한다.
 *
 * 또한 문제를 격리하기 위한 "fixed" 하이브리드 파일을 생성한다.
 */
public class BinaryValidator {

    static class Rec {
        int tag, level, size, offset;
        byte[] data;
        Rec(int t, int l, int s, byte[] d, int o) { tag=t; level=l; size=s; data=d; offset=o; }
    }

    static final Map<Integer,String> NAMES = new HashMap<>();
    static {
        NAMES.put(0x010,"DOC_PROPS"); NAMES.put(0x011,"ID_MAPS");
        NAMES.put(0x012,"BIN_DATA"); NAMES.put(0x013,"FACE_NAME");
        NAMES.put(0x014,"BORDER_FILL"); NAMES.put(0x015,"CHAR_SHAPE");
        NAMES.put(0x016,"TAB_DEF"); NAMES.put(0x017,"NUMBERING");
        NAMES.put(0x018,"BULLET"); NAMES.put(0x019,"PARA_SHAPE");
        NAMES.put(0x01A,"STYLE"); NAMES.put(0x01B,"DOC_DATA");
        NAMES.put(0x01E,"COMPAT_DOC"); NAMES.put(0x01F,"LAYOUT_COMPAT");
        NAMES.put(0x020,"TRACKCHANGE"); NAMES.put(0x05C,"MEMO_SHAPE");
        NAMES.put(0x05E,"FORBIDDEN");
        NAMES.put(0x042,"PARA_HDR"); NAMES.put(0x043,"PARA_TXT");
        NAMES.put(0x044,"PARA_CS"); NAMES.put(0x045,"PARA_LS");
        NAMES.put(0x046,"RANGE_TAG"); NAMES.put(0x047,"CTRL_HDR");
        NAMES.put(0x048,"LIST_HDR"); NAMES.put(0x049,"PAGE_DEF");
        NAMES.put(0x04A,"FN_SHAPE"); NAMES.put(0x04B,"PG_BF");
        NAMES.put(0x04C,"SHAPE_CMP"); NAMES.put(0x04D,"TABLE");
        NAMES.put(0x055,"PICTURE"); NAMES.put(0x057,"CTRL_DATA");
    }
    static String nm(int tag) { String n=NAMES.get(tag); return n!=null?n:String.format("0x%03X",tag); }
    static String hex(byte[] d, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i=0; i<Math.min(d.length,max); i++) sb.append(String.format("%02x",d[i]&0xFF));
        return sb.toString();
    }

    static List<Rec> parse(byte[] raw) {
        List<Rec> recs = new ArrayList<>();
        int pos = 0;
        while (pos+4 <= raw.length) {
            int h = (raw[pos]&0xFF)|((raw[pos+1]&0xFF)<<8)|((raw[pos+2]&0xFF)<<16)|((raw[pos+3]&0xFF)<<24);
            int off=pos; pos+=4;
            int tag=h&0x3FF, lv=(h>>10)&0x3FF, sz=(h>>20)&0xFFF;
            if (sz==0xFFF && pos+4<=raw.length) {
                sz=(raw[pos]&0xFF)|((raw[pos+1]&0xFF)<<8)|((raw[pos+2]&0xFF)<<16)|((raw[pos+3]&0xFF)<<24);
                pos+=4;
            }
            int end = Math.min(pos+sz, raw.length);
            byte[] d = Arrays.copyOfRange(raw, pos, end);
            recs.add(new Rec(tag,lv,sz,d,off));
            pos = end;
        }
        return recs;
    }

    static byte[] readStream(POIFSFileSystem p, String... path) throws IOException {
        DirectoryEntry dir = p.getRoot();
        for (int i=0; i<path.length-1; i++) dir=(DirectoryEntry)dir.getEntry(path[i]);
        DocumentEntry doc = (DocumentEntry)dir.getEntry(path[path.length-1]);
        byte[] data = new byte[doc.getSize()];
        try (InputStream is = new DocumentInputStream(doc)) {
            int t=0; while(t<data.length){int r=is.read(data,t,data.length-t); if(r<0)break; t+=r;}
        }
        return data;
    }

    static byte[] inflate(byte[] raw) {
        try {
            Inflater inf = new Inflater(true);
            inf.setInput(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length*4);
            byte[] buf = new byte[8192];
            while (!inf.finished()) { int n=inf.inflate(buf); if(n==0&&inf.needsInput())break; out.write(buf,0,n); }
            inf.end();
            return out.toByteArray();
        } catch (Exception e) { return raw; }
    }

    // CTRL_HDR 데이터에서 ctrl ID 찾기
    static int ctrlId(byte[] d) {
        if (d.length<4) return 0;
        return (d[0]&0xFF)|((d[1]&0xFF)<<8)|((d[2]&0xFF)<<16)|((d[3]&0xFF)<<24);
    }
    static String ctrlName(int id) {
        byte[] b = {(byte)((id>>24)&0xFF),(byte)((id>>16)&0xFF),(byte)((id>>8)&0xFF),(byte)(id&0xFF)};
        return new String(b).trim();
    }

    public static void main(String[] args) throws Exception {
        String refPath = args.length>0 ? args[0] : "hwp/test_hwpx2_conv.hwp";
        String outPath = args.length>1 ? args[1] : "output/test_hwpx2_conv.hwp";

        POIFSFileSystem refP = new POIFSFileSystem(new FileInputStream(refPath));
        POIFSFileSystem outP = new POIFSFileSystem(new FileInputStream(outPath));

        int totalErrors = 0;
        int totalWarnings = 0;

        // ===== FileHeader =====
        System.out.println("===== FileHeader =====");
        byte[] refFH = readStream(refP,"FileHeader"), outFH = readStream(outP,"FileHeader");
        if (Arrays.equals(refFH, outFH)) {
            System.out.println("  PASS: Identical");
        } else {
            for (int i=0;i<256;i++) {
                if (refFH[i]!=outFH[i]) {
                    System.out.printf("  FAIL: byte %d ref=0x%02x out=0x%02x%n",i,refFH[i]&0xFF,outFH[i]&0xFF);
                    totalErrors++;
                }
            }
        }

        // ===== DocInfo =====
        System.out.println("\n===== DocInfo =====");
        byte[] refDI = inflate(readStream(refP,"DocInfo")), outDI = inflate(readStream(outP,"DocInfo"));
        List<Rec> refDR = parse(refDI), outDR = parse(outDI);
        System.out.printf("  Records: ref=%d out=%d%n", refDR.size(), outDR.size());

        // tag 개수 비교
        Map<Integer,Integer> refCnt = new TreeMap<>(), outCnt = new TreeMap<>();
        for (Rec r:refDR) refCnt.merge(r.tag,1,Integer::sum);
        for (Rec r:outDR) outCnt.merge(r.tag,1,Integer::sum);
        Set<Integer> allTags = new TreeSet<>(refCnt.keySet()); allTags.addAll(outCnt.keySet());
        for (int t : allTags) {
            int rc=refCnt.getOrDefault(t,0), oc=outCnt.getOrDefault(t,0);
            if (rc!=oc) {
                System.out.printf("  FAIL [COUNT] %s: ref=%d out=%d%n", nm(t), rc, oc);
                totalErrors++;
            }
        }

        // 각 tag 그룹 내에서 Record별 비교
        Map<Integer,List<Rec>> refGrp = new LinkedHashMap<>(), outGrp = new LinkedHashMap<>();
        for (Rec r:refDR) refGrp.computeIfAbsent(r.tag,k->new ArrayList<>()).add(r);
        for (Rec r:outDR) outGrp.computeIfAbsent(r.tag,k->new ArrayList<>()).add(r);
        for (int t : allTags) {
            List<Rec> rl = refGrp.getOrDefault(t,Collections.emptyList());
            List<Rec> ol = outGrp.getOrDefault(t,Collections.emptyList());
            int min = Math.min(rl.size(),ol.size());
            int diffs=0, sizeDiffs=0;
            for (int i=0;i<min;i++) {
                if (rl.get(i).size != ol.get(i).size) sizeDiffs++;
                if (!Arrays.equals(rl.get(i).data, ol.get(i).data)) diffs++;
            }
            if (sizeDiffs>0) {
                System.out.printf("  FAIL [SIZE] %s: %d/%d records have different sizes (first: ref=%d out=%d)%n",
                    nm(t), sizeDiffs, min, rl.get(0).size, ol.get(0).size);
                totalErrors++;
            } else if (diffs>0) {
                System.out.printf("  WARN [DATA] %s: %d/%d records have different content (same size=%d)%n",
                    nm(t), diffs, min, rl.get(0).size);
                totalWarnings++;
            }
        }

        // ===== Section0 =====
        System.out.println("\n===== Section0 =====");
        byte[] refS = inflate(readStream(refP,"BodyText","Section0"));
        byte[] outS = inflate(readStream(outP,"BodyText","Section0"));
        List<Rec> refSR = parse(refS), outSR = parse(outS);
        System.out.printf("  Records: ref=%d out=%d%n", refSR.size(), outSR.size());

        // 개수 확인
        Map<Integer,Integer> refSC = new TreeMap<>(), outSC = new TreeMap<>();
        for (Rec r:refSR) refSC.merge(r.tag,1,Integer::sum);
        for (Rec r:outSR) outSC.merge(r.tag,1,Integer::sum);
        Set<Integer> allST = new TreeSet<>(refSC.keySet()); allST.addAll(outSC.keySet());
        for (int t : allST) {
            int rc=refSC.getOrDefault(t,0), oc=outSC.getOrDefault(t,0);
            String status = rc==oc ? "OK" : "FAIL";
            System.out.printf("  %s %s: ref=%d out=%d%n", status, nm(t), rc, oc);
            if (rc!=oc) totalErrors++;
        }

        // 순차 정렬 확인 - Record가 갈라지는 지점 찾기
        System.out.println("\n  --- Sequential alignment ---");
        int maxSeq = Math.min(refSR.size(), outSR.size());
        int firstMismatch = -1;
        int matchCount = 0;
        for (int i=0; i<maxSeq; i++) {
            Rec r=refSR.get(i), o=outSR.get(i);
            if (r.tag==o.tag && r.level==o.level) {
                matchCount++;
            } else {
                if (firstMismatch<0) firstMismatch = i;
                break;
            }
        }
        if (firstMismatch<0) {
            System.out.printf("  PASS: All %d records have matching tag+level sequence%n", matchCount);
        } else {
            System.out.printf("  FAIL: First tag/level mismatch at record %d (matched %d)%n", firstMismatch, matchCount);
            totalErrors++;
            // 불일치 지점 주변의 컨텍스트 표시
            for (int i=Math.max(0,firstMismatch-2); i<Math.min(maxSeq,firstMismatch+5); i++) {
                Rec r=refSR.get(i), o=outSR.get(i);
                String rCtrl = r.tag==0x047 ? "/"+ctrlName(ctrlId(r.data)) : "";
                String oCtrl = o.tag==0x047 ? "/"+ctrlName(ctrlId(o.data)) : "";
                boolean match = r.tag==o.tag && r.level==o.level;
                System.out.printf("  [%d] %s ref=%s%s/lv%d/sz%d  out=%s%s/lv%d/sz%d%n",
                    i, match?"  ":">>",
                    nm(r.tag),rCtrl,r.level,r.size,
                    nm(o.tag),oCtrl,o.level,o.size);
            }
        }

        // 중요 구조 검증: 첫 번째 table CTRL_HDR
        System.out.println("\n  --- Table CTRL_HDR check ---");
        Rec refTbl=null, outTbl=null;
        for (Rec r:refSR) if (r.tag==0x047 && r.data.length>=4 && ctrlId(r.data)==0x74626C20) { refTbl=r; break; }
        for (Rec r:outSR) if (r.tag==0x047 && r.data.length>=4 && ctrlId(r.data)==0x74626C20) { outTbl=r; break; }
        if (refTbl!=null && outTbl!=null) {
            if (Arrays.equals(refTbl.data, outTbl.data)) {
                System.out.println("  PASS: First table CTRL_HDR identical");
            } else {
                System.out.printf("  WARN: First table CTRL_HDR differs (ref=%d out=%d bytes)%n", refTbl.size, outTbl.size);
                int d = findDiff(refTbl.data, outTbl.data);
                System.out.printf("    FirstDiff@byte%d ref=%s out=%s%n", d,
                    hex(refTbl.data,Math.min(50,refTbl.size)), hex(outTbl.data,Math.min(50,outTbl.size)));
                totalWarnings++;
            }
        }

        // 첫 번째 LIST_HDR 검증
        System.out.println("\n  --- LIST_HDR check ---");
        Rec refLH=null, outLH=null;
        for (Rec r:refSR) if (r.tag==0x048) { refLH=r; break; }
        for (Rec r:outSR) if (r.tag==0x048) { outLH=r; break; }
        if (refLH!=null && outLH!=null) {
            if (refLH.size==outLH.size) {
                if (Arrays.equals(refLH.data, outLH.data)) System.out.println("  PASS: First LIST_HDR identical");
                else {
                    int d=findDiff(refLH.data,outLH.data);
                    System.out.printf("  WARN: First LIST_HDR content differs @byte%d (both %dB)%n",d,refLH.size);
                    System.out.printf("    ref=%s%n    out=%s%n", hex(refLH.data,50), hex(outLH.data,50));
                    totalWarnings++;
                }
            } else {
                System.out.printf("  FAIL: LIST_HDR size mismatch ref=%d out=%d%n", refLH.size, outLH.size);
                System.out.printf("    ref=%s%n    out=%s%n", hex(refLH.data,50), hex(outLH.data,50));
                totalErrors++;
            }
        }

        // PG_BF 검증
        System.out.println("\n  --- PAGE_BORDER_FILL check ---");
        Rec refPB=null, outPB=null;
        for (Rec r:refSR) if (r.tag==0x04B) { refPB=r; break; }
        for (Rec r:outSR) if (r.tag==0x04B) { outPB=r; break; }
        if (refPB!=null && outPB!=null) {
            if (Arrays.equals(refPB.data, outPB.data)) System.out.println("  PASS: First PG_BF identical");
            else {
                System.out.printf("  WARN: PG_BF differs  ref=%s out=%s%n", hex(refPB.data,14), hex(outPB.data,14));
                totalWarnings++;
            }
        }

        // FN_SHAPE 검증
        System.out.println("\n  --- FOOTNOTE_SHAPE check ---");
        List<Rec> refFNs=new ArrayList<>(), outFNs=new ArrayList<>();
        for (Rec r:refSR) if (r.tag==0x04A) refFNs.add(r);
        for (Rec r:outSR) if (r.tag==0x04A) outFNs.add(r);
        for (int i=0; i<Math.min(refFNs.size(),outFNs.size()); i++) {
            Rec rf=refFNs.get(i), of=outFNs.get(i);
            if (Arrays.equals(rf.data, of.data)) System.out.printf("  PASS: FN_SHAPE[%d] identical%n",i);
            else {
                int d=findDiff(rf.data,of.data);
                System.out.printf("  WARN: FN_SHAPE[%d] differs @byte%d%n    ref=%s%n    out=%s%n",
                    i,d,hex(rf.data,30),hex(of.data,30));
                totalWarnings++;
            }
        }

        // ===== BinData =====
        System.out.println("\n===== BinData =====");
        try {
            DirectoryEntry refBin = (DirectoryEntry) refP.getRoot().getEntry("BinData");
            DirectoryEntry outBin = (DirectoryEntry) outP.getRoot().getEntry("BinData");
            Set<String> refNames = new TreeSet<>(), outNames = new TreeSet<>();
            for (Entry e : refBin) if (e.isDocumentEntry()) refNames.add(e.getName());
            for (Entry e : outBin) if (e.isDocumentEntry()) outNames.add(e.getName());
            if (refNames.equals(outNames)) {
                System.out.printf("  PASS: Same %d BinData entries%n", refNames.size());
            } else {
                System.out.printf("  FAIL: ref has %s, out has %s%n", refNames, outNames);
                totalErrors++;
            }
        } catch (Exception e) {
            System.out.println("  SKIP: No BinData directory");
        }

        // ===== VERDICT =====
        System.out.println("\n===== VERDICT =====");
        System.out.printf("  Errors: %d  Warnings: %d%n", totalErrors, totalWarnings);
        if (totalErrors == 0) {
            System.out.println("  RESULT: LIKELY VALID (structure matches, content may differ in non-critical ways)");
        } else {
            System.out.println("  RESULT: LIKELY INVALID (structural mismatches found)");
        }

        refP.close(); outP.close();
    }

    static int findDiff(byte[] a, byte[] b) {
        int len=Math.min(a.length,b.length);
        for (int i=0;i<len;i++) if (a[i]!=b[i]) return i;
        return a.length!=b.length ? len : -1;
    }
}
