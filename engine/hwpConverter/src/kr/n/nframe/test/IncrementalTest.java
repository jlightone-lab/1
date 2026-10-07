package kr.n.nframe.test;

import kr.n.nframe.HwpConverter;
import kr.dogfoot.hwplib.reader.HWPReader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

/**
 * N개 paragraph로 잘라낸 HWPX 파일을 만들어 변환을 테스트한다.
 * 변환이 실패하기 시작하는 정확한 paragraph 개수를 찾는다.
 */
public class IncrementalTest {

    public static void main(String[] args) throws Exception {
        String hwpxPath = "hwp/test_hwpx2.hwpx";

        // 원본 HWPX 읽기
        ZipFile origZip = new ZipFile(hwpxPath);
        byte[] headerXml = readEntry(origZip, "Contents/header.xml");
        byte[] contentHpf = readEntry(origZip, "Contents/content.hpf");
        byte[] origSection = readEntry(origZip, "Contents/section0.xml");
        byte[] versionXml = readEntry(origZip, "version.xml");
        byte[] settingsXml = readEntry(origZip, "settings.xml");

        String sectionStr = new String(origSection, StandardCharsets.UTF_8);

        // section XML에서 paragraph 경계 찾기
        String HP = "http://www.hancom.co.kr/hwpml/2011/paragraph";
        String paraOpen = "<hp:p ";
        String paraClose = "</hp:p>";

        // paragraph 개수 세기
        int totalParas = 0;
        int idx = 0;
        while ((idx = sectionStr.indexOf(paraOpen, idx)) >= 0) {
            totalParas++;
            idx++;
        }
        System.out.println("Total paragraphs: " + totalParas);

        // paragraph 수를 점차 늘려가며 테스트
        int[] testCounts = {1, 2, 3, 4, 5, 10, 20, 50};

        for (int n : testCounts) {
            if (n > totalParas) continue;

            // section XML을 N개 paragraph로 자름
            String truncated = truncateSection(sectionStr, n);

            // 잘라낸 section으로 임시 HWPX 생성
            String tempHwpx = "output/test_" + n + "p.hwpx";
            String tempHwp = "output/test_" + n + "p.hwp";

            createTruncatedHwpx(tempHwpx, origZip, truncated.getBytes(StandardCharsets.UTF_8));

            // 변환
            try {
                new HwpConverter().convertHwpxToHwp(tempHwpx, tempHwp);

                System.out.println("  " + n + " paragraphs: converted, file=" +
                    new File(tempHwp).length() + "B");
            } catch (Exception e) {
                System.out.println("  " + n + " paragraphs: CONVERT FAIL: " + e.getMessage());
            }
        }

        origZip.close();
    }

    static String truncateSection(String sectionXml, int maxParas) {
        // N번째 </hp:p>를 찾아 거기서 자르고 section을 닫는다
        String paraClose = "</hp:p>";
        int paraCount = 0;
        int cutPos = -1;

        int idx = 0;
        while ((idx = sectionXml.indexOf(paraClose, idx)) >= 0) {
            paraCount++;
            idx += paraClose.length();
            if (paraCount >= maxParas) {
                cutPos = idx;
                break;
            }
        }

        if (cutPos < 0) return sectionXml; // paragraph 개수 부족

        // section 닫는 태그 찾기
        String secClose = "</hs:sec>";
        return sectionXml.substring(0, cutPos) + secClose;
    }

    static void createTruncatedHwpx(String outPath, ZipFile origZip, byte[] newSection) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outPath))) {
            // section0.xml을 제외한 모든 엔트리 복사
            java.util.Enumeration<? extends ZipEntry> entries = origZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().equals("Contents/section0.xml")) {
                    // 잘라낸 section 기록
                    ZipEntry newEntry = new ZipEntry("Contents/section0.xml");
                    zos.putNextEntry(newEntry);
                    zos.write(newSection);
                    zos.closeEntry();
                } else {
                    zos.putNextEntry(new ZipEntry(entry.getName()));
                    try (InputStream is = origZip.getInputStream(entry)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) > 0) zos.write(buf, 0, len);
                    }
                    zos.closeEntry();
                }
            }
        }
    }

    static byte[] readEntry(ZipFile zf, String name) throws IOException {
        ZipEntry entry = zf.getEntry(name);
        if (entry == null) return new byte[0];
        try (InputStream is = zf.getInputStream(entry)) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }
}
