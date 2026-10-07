package kr.n.nframe.newfeature.odtcommon;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ODT(zip) 패키지 기록. mimetype 은 반드시 첫 엔트리·STORED(무압축).
 * 나머지(content/styles/meta/manifest/Pictures)는 DEFLATE.
 * manifest 는 이미지 미디어타입까지 완결(서연 리서치 §8).
 */
public final class OdtZipPackager {
    private OdtZipPackager() {}

    private static final String MIME = "application/vnd.oasis.opendocument.text";

    /**
     * v16t34 문제1: LibreOffice 호환 플래그. 수동 줄바꿈(&lt;text:line-break/&gt;)으로 끝나는 줄을
     * 양쪽정렬(justify)로 양끝까지 늘리지 않는다(= 한컴/MS Word 동작). 전역 1플래그라
     * 일반 justify 문단(수동줄바꿈 없는 줄)은 그대로 양쪽정렬되어 회귀 없음.
     */
    private static final String SETTINGS_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
      + "<office:document-settings xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
      + " xmlns:config=\"urn:oasis:names:tc:opendocument:xmlns:config:1.0\" office:version=\"1.2\">"
      + "<office:settings>"
      + "<config:config-item-set config:name=\"ooo:configuration-settings\">"
      + "<config:config-item config:name=\"DoNotJustifyLinesWithManualBreak\" config:type=\"boolean\">true</config:config-item>"
      + "</config:config-item-set>"
      + "</office:settings>"
      + "</office:document-settings>";

    public static void write(Path odt, OdtWriter writer, PictureRegistry pics, MathRegistry maths)
            throws IOException {
        Path parent = odt.getParent();
        if (parent != null) Files.createDirectories(parent);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(odt))) {
            // 1) mimetype — STORED, 첫 엔트리
            byte[] mime = MIME.getBytes(StandardCharsets.US_ASCII);
            ZipEntry me = new ZipEntry("mimetype");
            me.setMethod(ZipEntry.STORED);
            me.setSize(mime.length);
            me.setCompressedSize(mime.length);
            CRC32 crc = new CRC32(); crc.update(mime);
            me.setCrc(crc.getValue());
            zos.putNextEntry(me); zos.write(mime); zos.closeEntry();

            // 2) 본문 파트
            putDeflated(zos, "content.xml", writer.contentXml().getBytes(StandardCharsets.UTF_8));
            putDeflated(zos, "styles.xml",  writer.stylesXml().getBytes(StandardCharsets.UTF_8));
            putDeflated(zos, "meta.xml",    writer.metaXml().getBytes(StandardCharsets.UTF_8));
            putDeflated(zos, "settings.xml", SETTINGS_XML.getBytes(StandardCharsets.UTF_8));

            // 3) 이미지
            for (Map.Entry<String, byte[]> e : pics.entries().entrySet()) {
                putDeflated(zos, "Pictures/" + e.getKey(), e.getValue());
            }

            // 4) 임베디드 수식 서브문서(ObjectNN/content.xml)
            if (maths != null) {
                for (Map.Entry<String, byte[]> e : maths.entries().entrySet()) {
                    putDeflated(zos, e.getKey() + "/content.xml", e.getValue());
                }
            }

            // 5) manifest (이미지·수식 미디어타입 포함)
            putDeflated(zos, "META-INF/manifest.xml",
                    manifest(pics, maths).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String manifest(PictureRegistry pics, MathRegistry maths) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<manifest:manifest"
                + " xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\""
                + " manifest:version=\"1.2\">");
        sb.append(fe("/", MIME));
        sb.append(fe("content.xml", "text/xml"));
        sb.append(fe("styles.xml", "text/xml"));
        sb.append(fe("meta.xml", "text/xml"));
        sb.append(fe("settings.xml", "text/xml"));
        for (Map.Entry<String, byte[]> e : pics.entries().entrySet()) {
            sb.append(fe("Pictures/" + e.getKey(), pics.mediaTypeOf(e.getKey())));
        }
        // 수식 객체: 디렉터리는 formula 미디어타입, 내부 content.xml 은 text/xml.
        if (maths != null) {
            for (Map.Entry<String, byte[]> e : maths.entries().entrySet()) {
                sb.append(fe(e.getKey() + "/", MathRegistry.FORMULA_MEDIA_TYPE));
                sb.append(fe(e.getKey() + "/content.xml", "text/xml"));
            }
        }
        sb.append("</manifest:manifest>");
        return sb.toString();
    }

    private static String fe(String path, String mediaType) {
        return "<manifest:file-entry manifest:full-path=\"" + path
             + "\" manifest:media-type=\"" + mediaType + "\"/>";
    }

    private static void putDeflated(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.DEFLATED);
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }
}
