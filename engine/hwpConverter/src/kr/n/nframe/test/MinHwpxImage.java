package kr.n.nframe.test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 최소 HWPX with 1 image 테스트.
 */
public class MinHwpxImage {
    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "build/v1411/poc/min.hwpx";
        Files.createDirectories(Paths.get(out).getParent());

        // 1 image (red square)
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED); g.fillRect(0,0,400,300);
        g.setColor(Color.BLACK); g.drawString("Hello HWPX", 100, 150);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        byte[] png = baos.toByteArray();

        try (OutputStream os = Files.newOutputStream(Paths.get(out));
             ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {

            byte[] mimetype = loadResource("/kr/n/nframe/resources/hwpx-template/mimetype");
            ZipEntry mime = new ZipEntry("mimetype");
            mime.setMethod(ZipEntry.STORED);
            mime.setSize(mimetype.length);
            mime.setCompressedSize(mimetype.length);
            CRC32 crc = new CRC32(); crc.update(mimetype);
            mime.setCrc(crc.getValue());
            zip.putNextEntry(mime); zip.write(mimetype); zip.closeEntry();

            putRes(zip, "version.xml", "/kr/n/nframe/resources/hwpx-template/version.xml");
            putRes(zip, "settings.xml", "/kr/n/nframe/resources/hwpx-template/settings.xml");
            putRes(zip, "META-INF/container.xml", "/kr/n/nframe/resources/hwpx-template/META-INF/container.xml");
            putRes(zip, "META-INF/container.rdf", "/kr/n/nframe/resources/hwpx-template/META-INF/container.rdf");
            putRes(zip, "META-INF/manifest.xml", "/kr/n/nframe/resources/hwpx-template/META-INF/manifest.xml");

            // content.hpf — Scripts/* 제거만
            String hpf = new String(loadResource("/kr/n/nframe/resources/hwpx-template/Contents/content.hpf"), StandardCharsets.UTF_8);
            hpf = hpf.replaceAll("<opf:item\\s+[^/]*href=\"Scripts/[^\"]+\"[^/]*/\\s*>", "");
            hpf = hpf.replaceAll("<opf:itemref\\s+idref=\"headersc\"[^/]*/\\s*>", "");
            hpf = hpf.replaceAll("<opf:itemref\\s+idref=\"sourcesc\"[^/]*/\\s*>", "");
            put(zip, "Contents/content.hpf", hpf);

            // header.xml — binDataList 주입 테스트
            String header = new String(loadResource("/kr/n/nframe/resources/hwpx-template/Contents/header.xml"), StandardCharsets.UTF_8);
            String bin = "<hh:binDataList itemCnt=\"1\">"
                + "<hh:binItem id=\"image1\" type=\"embedding\" href=\"BinData/image1.png\" mediaType=\"image/png\"/>"
                + "</hh:binDataList>";
            // 기존 binDataList 가 있는지 확인
            int s = header.indexOf("<hh:binDataList");
            if (s >= 0) {
                int closeIdx = header.indexOf("</hh:binDataList>", s);
                if (closeIdx >= 0) {
                    int endTag = closeIdx + "</hh:binDataList>".length();
                    header = header.substring(0, s) + bin + header.substring(endTag);
                } else {
                    int selfClose = header.indexOf("/>", s);
                    if (selfClose >= 0) {
                        header = header.substring(0, s) + bin + header.substring(selfClose + 2);
                    }
                }
            } else {
                // binDataList 는 refList 의 첫 element 여야 함
                int idx = header.indexOf("<hh:refList>");
                if (idx >= 0) {
                    int after = idx + "<hh:refList>".length();
                    header = header.substring(0, after) + bin + header.substring(after);
                }
            }
            put(zip, "Contents/header.xml", header);

            // BinData/image1.png
            zip.putNextEntry(new ZipEntry("BinData/image1.png"));
            zip.write(png);
            zip.closeEntry();

            // section0.xml — 그대로 (간단)
            put(zip, "Contents/section0.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>"
                    + "<hs:sec xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\""
                    + " xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\">"
                    + "<hp:p id=\"1\" paraPrIDRef=\"0\" styleIDRef=\"0\" pageBreak=\"0\" columnBreak=\"0\" merged=\"0\">"
                    + "<hp:run charPrIDRef=\"0\"><hp:t>test</hp:t></hp:run>"
                    + "<hp:linesegarray><hp:lineseg textpos=\"0\" vertpos=\"0\" vertsize=\"1000\""
                    + " textheight=\"1000\" baseline=\"850\" spacing=\"600\" horzpos=\"0\""
                    + " horzsize=\"41954\" flags=\"393216\"/></hp:linesegarray>"
                    + "</hp:p></hs:sec>");
        }

        // 검증
        try {
            kr.dogfoot.hwpxlib.object.HWPXFile file = kr.dogfoot.hwpxlib.reader.HWPXReader.fromFilepath(out);
            System.out.println("[OK] hwpxlib 읽기: sections=" + file.sectionXMLFileList().count());
        } catch (Throwable t) {
            System.out.println("[FAIL] " + t.getClass().getSimpleName() + " : " + t.getMessage());
            t.printStackTrace();
        }
    }

    static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
    static void putRes(ZipOutputStream zip, String name, String resPath) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(loadResource(resPath));
        zip.closeEntry();
    }
    static byte[] loadResource(String path) throws IOException {
        try (InputStream in = MinHwpxImage.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing: " + path);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }
}
