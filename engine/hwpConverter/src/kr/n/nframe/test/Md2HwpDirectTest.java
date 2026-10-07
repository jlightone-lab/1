package kr.n.nframe.test;

import kr.n.nframe.mdlib.MdToHwpDirect;

public class Md2HwpDirectTest {
    public static void main(String[] args) throws Exception {
        String md = args.length > 0 ? args[0] : "hwp/0427/dummy_file/05.단건_hwp_md/case1.md";
        String hwp = args.length > 1 ? args[1] : "build/v146/poc/case1_v146.hwp";
        new MdToHwpDirect().convert(md, hwp);

        // 역검증: hwplib 으로 다시 읽기
        kr.dogfoot.hwplib.object.HWPFile re = kr.dogfoot.hwplib.reader.HWPReader.fromFile(hwp);
        System.out.println("[검증] re-read paragraphs: "
                + re.getBodyText().getLastSection().getParagraphCount());
        System.out.println("[검증] file size: " + new java.io.File(hwp).length() + " bytes");
    }
}
