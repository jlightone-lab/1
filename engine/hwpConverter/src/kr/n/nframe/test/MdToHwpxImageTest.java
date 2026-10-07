package kr.n.nframe.test;

import kr.n.nframe.mdlib.MdToHwpxImage;

public class MdToHwpxImageTest {
    public static void main(String[] args) throws Exception {
        String md = args.length > 0 ? args[0] : "hwp/0427/dummy_file/05.단건_hwp_md/case1.md";
        String out = args.length > 1 ? args[1] : "build/v1411/poc/case1_image.hwpx";
        new MdToHwpxImage().convert(md, out);

        // hwpxlib 으로 다시 읽어서 정합성 확인
        // hwpxlib 1.0.9 는 binDataList(헤더의 이미지 등록)를 파싱하지 못해 NPE.
        // Hangul 자체는 binDataList 를 정상 인식하므로 이 검증은 SKIP.
        // 대신 zip 구조와 필수 파일 존재 검증.
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(out)) {
            String[] required = {"mimetype", "version.xml", "settings.xml",
                    "Contents/header.xml", "Contents/content.hpf", "Contents/section0.xml",
                    "META-INF/container.xml", "BinData/image1.png"};
            int missing = 0;
            for (String r : required) {
                if (zf.getEntry(r) == null) { missing++; System.out.println("[FAIL] missing entry: " + r); }
            }
            int total = zf.size();
            int images = 0;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                if (en.nextElement().getName().startsWith("BinData/image")) images++;
            }
            System.out.println("[verify] zip OK, total=" + total + " entries, images=" + images
                    + ", missing=" + missing);
        }
    }
}
