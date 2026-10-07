package kr.n.nframe.test;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.poifs.filesystem.*;

/**
 * 엄격한 바이트 수준 비교. 모든 차이는 FAIL로 판정.
 * 불일치마다 정확한 바이트 오프셋과 값을 보고합니다.
 */
public class StrictValidator {

    static final Map<Integer,String> N = new HashMap<>();
    static {
        N.put(0x010,"DOC_PROPS");N.put(0x011,"ID_MAPS");N.put(0x012,"BIN_DATA");
        N.put(0x013,"FACE_NAME");N.put(0x014,"BORDER_FILL");N.put(0x015,"CHAR_SHAPE");
        N.put(0x016,"TAB_DEF");N.put(0x017,"NUMBERING");N.put(0x018,"BULLET");
        N.put(0x019,"PARA_SHAPE");N.put(0x01A,"STYLE");N.put(0x01B,"DOC_DATA");
        N.put(0x01E,"COMPAT_DOC");N.put(0x01F,"LAYOUT_COMPAT");N.put(0x020,"TRACKCHG");
        N.put(0x05E,"FORBIDDEN");
        N.put(0x042,"PARA_HDR");N.put(0x043,"PARA_TXT");N.put(0x044,"PARA_CS");
        N.put(0x045,"PARA_LS");N.put(0x046,"RANGE_TAG");N.put(0x047,"CTRL_HDR");
        N.put(0x048,"LIST_HDR");N.put(0x049,"PAGE_DEF");N.put(0x04A,"FN_SHAPE");
        N.put(0x04B,"PG_BF");N.put(0x04C,"SHAPE_CMP");N.put(0x04D,"TABLE");
        N.put(0x055,"PICTURE");N.put(0x057,"CTRL_DATA");
    }
    static String nm(int t){String s=N.get(t);return s!=null?s:String.format("0x%03X",t);}
    static String hex(byte[] d,int m){StringBuilder sb=new StringBuilder();for(int i=0;i<Math.min(d.length,m);i++)sb.append(String.format("%02x",d[i]&0xFF));return sb.toString();}
    static int ctrlId(byte[] d){return d.length<4?0:(d[0]&0xFF)|((d[1]&0xFF)<<8)|((d[2]&0xFF)<<16)|((d[3]&0xFF)<<24);}
    static String ctrlNm(int id){byte[] b={(byte)((id>>24)&0xFF),(byte)((id>>16)&0xFF),(byte)((id>>8)&0xFF),(byte)(id&0xFF)};return new String(b).trim();}

    static class Rec{int tag,level,size;byte[] data;Rec(int t,int l,int s,byte[] d){tag=t;level=l;size=s;data=d;}}

    static List<Rec> parse(byte[] raw){
        List<Rec> r=new ArrayList<>();int p=0;
        while(p+4<=raw.length){
            int h=(raw[p]&0xFF)|((raw[p+1]&0xFF)<<8)|((raw[p+2]&0xFF)<<16)|((raw[p+3]&0xFF)<<24);p+=4;
            int t=h&0x3FF,l=(h>>10)&0x3FF,s=(h>>20)&0xFFF;
            if(s==0xFFF&&p+4<=raw.length){s=(raw[p]&0xFF)|((raw[p+1]&0xFF)<<8)|((raw[p+2]&0xFF)<<16)|((raw[p+3]&0xFF)<<24);p+=4;}
            int e=Math.min(p+s,raw.length);byte[] d=Arrays.copyOfRange(raw,p,e);r.add(new Rec(t,l,s,d));p=e;
        }
        return r;
    }

    static byte[] readStream(POIFSFileSystem p,String... path) throws IOException {
        DirectoryEntry d=p.getRoot();for(int i=0;i<path.length-1;i++)d=(DirectoryEntry)d.getEntry(path[i]);
        DocumentEntry doc=(DocumentEntry)d.getEntry(path[path.length-1]);byte[] data=new byte[doc.getSize()];
        try(InputStream is=new DocumentInputStream(doc)){int t=0;while(t<data.length){int r=is.read(data,t,data.length-t);if(r<0)break;t+=r;}}
        return data;
    }

    static byte[] inflate(byte[] raw){
        try{Inflater i=new Inflater(true);i.setInput(raw);ByteArrayOutputStream o=new ByteArrayOutputStream(raw.length*4);
        byte[] b=new byte[8192];while(!i.finished()){int n=i.inflate(b);if(n==0&&i.needsInput())break;o.write(b,0,n);}
        i.end();return o.toByteArray();}catch(Exception e){return raw;}
    }

    public static void main(String[] args) throws Exception {
        String refPath = args.length>0?args[0]:"hwp/test_hwpx2_conv.hwp";
        String outPath = args.length>1?args[1]:"output/test_hwpx2_conv.hwp";
        int fails = 0;

        POIFSFileSystem rp=new POIFSFileSystem(new FileInputStream(refPath));
        POIFSFileSystem op=new POIFSFileSystem(new FileInputStream(outPath));

        // ===== Section0 순차 비교 (파일 열기에 필수) =====
        System.out.println("===== Section0 STRICT sequential comparison =====");
        byte[] rs=inflate(readStream(rp,"BodyText","Section0"));
        byte[] os=inflate(readStream(op,"BodyText","Section0"));
        List<Rec> rr=parse(rs), or2=parse(os);
        System.out.printf("Records: ref=%d out=%d%n", rr.size(), or2.size());

        int limit = Math.min(rr.size(), or2.size());
        int totalMatch=0, totalFail=0;
        List<String> failDetails = new ArrayList<>();

        for (int i=0; i<limit; i++) {
            Rec r=rr.get(i), o=or2.get(i);
            boolean tagOk = r.tag==o.tag && r.level==o.level;
            boolean sizeOk = r.size==o.size;
            boolean dataOk = Arrays.equals(r.data, o.data);

            if (tagOk && sizeOk && dataOk) {
                totalMatch++;
            } else {
                totalFail++;
                String rCtrl = r.tag==0x047?"/"+ctrlNm(ctrlId(r.data)):"";
                String oCtrl = o.tag==0x047?"/"+ctrlNm(ctrlId(o.data)):"";

                String detail = String.format("[%d] ref=%s%s/lv%d/sz%d  out=%s%s/lv%d/sz%d",
                    i, nm(r.tag),rCtrl,r.level,r.size, nm(o.tag),oCtrl,o.level,o.size);

                if (tagOk && !dataOk) {
                    // 첫 번째 불일치 바이트 찾기
                    int minLen = Math.min(r.data.length, o.data.length);
                    int diffAt = -1;
                    for (int j=0;j<minLen;j++) { if(r.data[j]!=o.data[j]) { diffAt=j; break; } }
                    if (diffAt<0 && r.data.length!=o.data.length) diffAt=minLen;

                    if (diffAt >= 0) {
                        int ctx = Math.max(0, diffAt);
                        int end = Math.min(diffAt+12, Math.max(r.data.length, o.data.length));
                        detail += String.format(" DIFF@byte%d", diffAt);
                        detail += String.format(" ref[%d:]=%s", ctx, hex(Arrays.copyOfRange(r.data,ctx,Math.min(r.data.length,end)),30));
                        detail += String.format(" out[%d:]=%s", ctx, hex(Arrays.copyOfRange(o.data,ctx,Math.min(o.data.length,end)),30));
                    }
                }

                if (!tagOk) detail += " TAG_MISMATCH";

                failDetails.add(detail);

                if (failDetails.size() >= 30) {
                    failDetails.add("... (truncated, too many failures)");
                    break;
                }

                // 첫 태그 불일치에서 중단 — 이후는 정렬이 깨져 의미 없음
                if (!tagOk) {
                    failDetails.add("STOP: Tag mismatch means all subsequent records are misaligned");
                    break;
                }
            }
        }

        System.out.printf("Matched: %d/%d  Failed: %d%n", totalMatch, limit, totalFail);
        for (String d : failDetails) System.out.println("  FAIL: " + d);
        fails += totalFail;

        // ===== DocInfo 핵심 레코드 비교 =====
        System.out.println("\n===== DocInfo key records =====");
        byte[] rd=inflate(readStream(rp,"DocInfo")), od=inflate(readStream(op,"DocInfo"));
        List<Rec> rdr=parse(rd), odr=parse(od);

        // 동일한 태그의 레코드끼리 비교
        Map<Integer,List<Rec>> rg=new LinkedHashMap<>(), og=new LinkedHashMap<>();
        for(Rec r:rdr)rg.computeIfAbsent(r.tag,k->new ArrayList<>()).add(r);
        for(Rec r:odr)og.computeIfAbsent(r.tag,k->new ArrayList<>()).add(r);

        Set<Integer> tags=new TreeSet<>(rg.keySet()); tags.addAll(og.keySet());
        for(int t:tags) {
            List<Rec> rl=rg.getOrDefault(t,Collections.emptyList());
            List<Rec> ol=og.getOrDefault(t,Collections.emptyList());
            if(rl.size()!=ol.size()) {
                System.out.printf("  FAIL [COUNT] %s: ref=%d out=%d%n",nm(t),rl.size(),ol.size());
                fails++; continue;
            }
            int diffs=0,sizeDiffs=0;
            for(int i=0;i<rl.size();i++){
                if(rl.get(i).size!=ol.get(i).size)sizeDiffs++;
                else if(!Arrays.equals(rl.get(i).data,ol.get(i).data))diffs++;
            }
            if(sizeDiffs>0){
                System.out.printf("  FAIL [SIZE] %s: %d/%d differ (ref[0]=%dB out[0]=%dB)%n",
                    nm(t),sizeDiffs,rl.size(),rl.get(0).size,ol.get(0).size);
                fails++;
            } else if(diffs>0) {
                System.out.printf("  FAIL [DATA] %s: %d/%d content differs (size=%dB)%n",
                    nm(t),diffs,rl.size(),rl.get(0).size);
                fails++;
            } else {
                System.out.printf("  PASS %s: %d records identical%n",nm(t),rl.size());
            }
        }

        System.out.printf("\n===== TOTAL FAILS: %d =====%n", fails);

        rp.close(); op.close();
    }
}
