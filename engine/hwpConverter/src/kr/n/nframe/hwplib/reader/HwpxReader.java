package kr.n.nframe.hwplib.reader;

import kr.n.nframe.hwplib.constants.HwpxNs;
import kr.n.nframe.hwplib.model.BinDataItem;
import kr.n.nframe.hwplib.model.HwpDocument;
import kr.n.nframe.hwplib.model.Section;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * HWPX 파일(XML을 담은 ZIP 아카이브)을 읽어
 * HwpDocument 모델에 파싱된 데이터를 채운다.
 */
public class HwpxReader {

    /** 단일 BinData 엔트리 최대 크기 (정상 이미지·임베딩은 이보다 훨씬 작음). */
    private static final long MAX_BINDATA_BYTES = 64L * 1024 * 1024;
    /** 모든 BinData 엔트리 합계 최대 크기. */
    private static final long MAX_TOTAL_BINDATA_BYTES = 256L * 1024 * 1024;
    /** ZIP 아카이브 내 엔트리 최대 개수. */
    private static final int MAX_ZIP_ENTRIES = 10_000;

    /**
     * 주어진 경로에서 HWPX 파일을 읽고 채워진 HwpDocument를 반환.
     *
     * @param hwpxFilePath .hwpx 파일의 절대 경로
     * @return 완전히 채워진 HwpDocument
     * @throws IOException 파일을 읽거나 파싱할 수 없는 경우
     */
    public static HwpDocument read(String hwpxFilePath) throws IOException {
        HwpDocument doc = new HwpDocument();

        try (ZipFile zip = new ZipFile(hwpxFilePath)) {
            // ZIP 아카이브 구조 검증 (엔트리 개수 상한 + 경로 traversal 방어)
            validateZipStructure(zip);

            DocumentBuilder db = createSecureDocumentBuilder();

            // 1. content.hpf 매니페스트를 읽어 바이너리 항목 검색
            List<ManifestItem> manifestItems = readManifest(zip, db);

            // 2. header.xml 파싱
            ZipEntry headerEntry = zip.getEntry("Contents/header.xml");
            if (headerEntry != null) {
                Document headerDoc = parseXml(zip, headerEntry, db);
                Element headElement = headerDoc.getDocumentElement();
                HeaderParser.parse(headElement, doc);
            }

            // 3. 섹션 파일 파싱 (section0.xml, section1.xml, ...)
            int secIdx = 0;
            while (true) {
                String secPath = "Contents/section" + secIdx + ".xml";
                ZipEntry secEntry = zip.getEntry(secPath);
                if (secEntry == null) break;

                Document secDoc = parseXml(zip, secEntry, db);
                Element secElement = secDoc.getDocumentElement();
                Section section = SectionParser.parse(secElement, doc);
                doc.sections.add(section);
                secIdx++;
            }

            // 4. BinData/* 항목을 원시 byte 배열로 추출
            extractBinData(zip, doc, manifestItems);

            // IdMappings의 binData 개수 갱신
            doc.idMappings.counts[0] = doc.binDataItems.size();

        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse HWPX file: " + hwpxFilePath, e);
        }

        return doc;
    }

    /**
     * XXE / 엔티티 확장(Billion Laughs) / DTD 로딩 / XInclude 를 모두 차단하는
     * {@link DocumentBuilder} 를 생성한다.
     *
     * <p>설정 feature 중 일부는 특정 파서에서 지원되지 않을 수 있으나,
     * {@code FEATURE_SECURE_PROCESSING} 과 {@code disallow-doctype-decl} 은
     * 필수이므로 실패 시 예외를 던진다. 나머지는 best-effort 로 시도한다.
     */
    private static DocumentBuilder createSecureDocumentBuilder() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);

        // 필수 feature - 실패 시 예외 전파
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        // best-effort feature - 미지원 파서에서는 무시
        setFeatureQuiet(dbf, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureQuiet(dbf, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureQuiet(dbf, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        // 외부 리소스 로딩 URI 를 모두 차단
        try { dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); } catch (IllegalArgumentException ignored) {}
        try { dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ""); } catch (IllegalArgumentException ignored) {}

        DocumentBuilder db = dbf.newDocumentBuilder();
        // entity resolver 를 강제로 빈 입력으로 고정 (이중 방어)
        db.setEntityResolver((publicId, systemId) ->
                new org.xml.sax.InputSource(new java.io.ByteArrayInputStream(new byte[0])));
        return db;
    }

    private static void setFeatureQuiet(DocumentBuilderFactory dbf, String key, boolean value) {
        try { dbf.setFeature(key, value); } catch (Exception ignored) {}
    }

    /**
     * ZIP 아카이브의 구조적 악성 신호를 검사한다.
     * <ul>
     *   <li>엔트리 개수가 {@link #MAX_ZIP_ENTRIES} 초과</li>
     *   <li>엔트리 이름이 절대경로 / Zip Slip(../, ..\\) / NUL byte / 드라이브 레터 포함</li>
     * </ul>
     */
    private static void validateZipStructure(ZipFile zip) throws IOException {
        int count = 0;
        Enumeration<? extends ZipEntry> e = zip.entries();
        while (e.hasMoreElements()) {
            ZipEntry entry = e.nextElement();
            if (++count > MAX_ZIP_ENTRIES) {
                throw new IOException("ZIP entry count exceeds limit (" + MAX_ZIP_ENTRIES + ")");
            }
            String name = entry.getName();
            if (name == null) continue;
            if (name.indexOf('\0') >= 0) {
                throw new IOException("Malicious ZIP entry name (NUL byte): " + name);
            }
            // 정규화: 역슬래시를 슬래시로 통일한 뒤 검사
            String norm = name.replace('\\', '/');
            if (norm.startsWith("/")
                    || norm.contains("../")
                    || norm.equals("..")
                    || norm.endsWith("/..")
                    || (norm.length() >= 2 && norm.charAt(1) == ':')) {
                throw new IOException("Malicious ZIP entry path (traversal / absolute): " + name);
            }
        }
    }

    /**
     * Contents/content.hpf 매니페스트(OPF 포맷)를 읽어
     * 바이너리 데이터 항목과 참조를 검색.
     */
    private static List<ManifestItem> readManifest(ZipFile zip, DocumentBuilder db) {
        List<ManifestItem> items = new ArrayList<>();
        ZipEntry hpfEntry = zip.getEntry("Contents/content.hpf");
        if (hpfEntry == null) return items;

        try {
            Document hpfDoc = parseXml(zip, hpfEntry, db);
            Element root = hpfDoc.getDocumentElement();

            // 매니페스트에서 opf:item 또는 item 요소 탐색
            // OPF 네임스페이스는 있을 수도 없을 수도 있음
            NodeList itemNodes = root.getElementsByTagName("*");
            for (int i = 0; i < itemNodes.getLength(); i++) {
                if (!(itemNodes.item(i) instanceof Element)) continue;
                Element el = (Element) itemNodes.item(i);
                String localName = el.getLocalName();
                if (localName == null) {
                    String tag = el.getTagName();
                    int idx = tag.indexOf(':');
                    localName = idx >= 0 ? tag.substring(idx + 1) : tag;
                }

                if ("item".equals(localName)) {
                    ManifestItem mi = new ManifestItem();
                    mi.id = el.getAttribute("id");
                    mi.href = el.getAttribute("href");
                    mi.mediaType = el.getAttribute("media-type");
                    if (mi.href != null && !mi.href.isEmpty()) {
                        items.add(mi);
                    }
                }
            }
        } catch (Exception e) {
            // 매니페스트 파싱은 best-effort. 실패 시 그대로 진행
        }

        return items;
    }

    /**
     * ZIP 아카이브에서 바이너리 데이터 파일 추출.
     * BinData/ 디렉터리 하위 항목 탐색.
     */
    private static void extractBinData(ZipFile zip, HwpDocument doc, List<ManifestItem> manifestItems) {
        int binId = 1;
        long[] totalBytes = {0L};

        // 먼저 매니페스트에 참조된 항목 시도
        for (ManifestItem mi : manifestItems) {
            if (mi.href == null) continue;
            // 매니페스트 href는 Contents/ 기준 상대 경로지만, 바이너리 파일은 보통
            // BinData/xxx 또는 ../BinData/xxx로 참조됨
            String zipPath = resolveManifestPath(mi.href);
            if (zipPath == null || !zipPath.startsWith("BinData/")) continue;

            ZipEntry entry = zip.getEntry(zipPath);
            if (entry == null) {
                // Contents/ 접두사로 시도
                entry = zip.getEntry("Contents/" + mi.href);
            }
            if (entry == null) continue;

            try {
                byte[] data = readBinDataBytes(zip, entry, totalBytes);
                if (data == null) continue;
                BinDataItem bdi = new BinDataItem();
                bdi.type = 1; // EMBEDDING
                bdi.binDataId = binId++;
                bdi.relativePath = mi.href;
                bdi.extension = extractExtension(mi.href);
                bdi.data = data;
                doc.binDataItems.add(bdi);
            } catch (IOException e) {
                // 오류 시 이 바이너리 항목 건너뜀
            }
        }

        // 매니페스트에 없는 BinData/ 파일도 ZIP 항목에서 직접 스캔
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory()) continue;

            if (name.startsWith("BinData/") || name.startsWith("Contents/BinData/")) {
                // 이미 추출되었는지 확인
                boolean alreadyExtracted = false;
                for (BinDataItem existing : doc.binDataItems) {
                    if (name.endsWith(existing.relativePath) ||
                        (existing.relativePath != null && name.contains(existing.relativePath))) {
                        alreadyExtracted = true;
                        break;
                    }
                }
                if (alreadyExtracted) continue;

                try {
                    byte[] data = readBinDataBytes(zip, entry, totalBytes);
                    if (data == null) continue;
                    BinDataItem bdi = new BinDataItem();
                    bdi.type = 1; // EMBEDDING
                    bdi.binDataId = binId++;
                    bdi.relativePath = name;
                    bdi.extension = extractExtension(name);
                    bdi.data = data;
                    doc.binDataItems.add(bdi);
                } catch (IOException e) {
                    // 오류 시 건너뜀
                }
            }
        }
    }

    /**
     * 매니페스트 href 경로를 ZIP 항목 경로로 변환.
     * 선행 ../ / ./ 를 반복 제거한 뒤 Path.normalize 로 추가 정규화하고,
     * 정규화 후에도 traversal 신호가 남으면 null 반환.
     */
    private static String resolveManifestPath(String href) {
        if (href == null) return null;
        String resolved = href.replace('\\', '/');
        while (resolved.startsWith("../")) {
            resolved = resolved.substring(3);
        }
        if (resolved.startsWith("./")) {
            resolved = resolved.substring(2);
        }
        // URL 디코딩된 형태의 traversal 시도 차단
        String lower = resolved.toLowerCase();
        if (lower.contains("%2e%2e") || lower.contains("..")) return null;
        if (resolved.startsWith("/") || (resolved.length() >= 2 && resolved.charAt(1) == ':')) return null;
        return resolved;
    }

    /**
     * 경로에서 파일 확장자 추출.
     */
    private static String extractExtension(String path) {
        if (path == null) return "";
        int dotIdx = path.lastIndexOf('.');
        if (dotIdx < 0) return "";
        return path.substring(dotIdx + 1).toLowerCase();
    }

    /**
     * ZIP 항목을 XML 문서로 파싱.
     * 스트림 크기 제한 없음 — 이전 버전에 존재했던 BoundedInputStream (64 MB 상한)은
     * 이미지/표가 많은 10 MB+ HWPX 파일의 section0.xml 이 압축 해제 시 100 MB+ 로
     * 팽창하면 "Stream exceeds limit (67108864 bytes)" IOException 을 유발했다.
     * 메모리 상한은 JVM 힙(-Xmx4g, bat 파일에 고정)으로 제어한다.
     */
    private static Document parseXml(ZipFile zip, ZipEntry entry, DocumentBuilder db)
            throws Exception {
        try (InputStream is = zip.getInputStream(entry)) {
            return db.parse(is);
        }
    }

    /**
     * 단일 BinData 엔트리를 읽는다. 엔트리 단일 크기 / 누적 크기 상한을 모두 검사.
     * 한도 초과 시 {@code IOException} 을 던져 해당 엔트리는 스킵되게 한다.
     */
    private static byte[] readBinDataBytes(ZipFile zip, ZipEntry entry, long[] totalBytes) throws IOException {
        long declared = entry.getSize();
        if (declared > MAX_BINDATA_BYTES) {
            throw new IOException("BinData entry too large: " + entry.getName() + " (" + declared + ")");
        }
        int initial = (declared >= 0 && declared <= Integer.MAX_VALUE) ? (int) declared : 8192;
        try (InputStream raw = zip.getInputStream(entry);
             InputStream is = new BoundedInputStream(raw, MAX_BINDATA_BYTES)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(initial, 256));
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
                if ((long) baos.size() + totalBytes[0] > MAX_TOTAL_BINDATA_BYTES) {
                    throw new IOException("Total BinData size exceeds limit ("
                            + MAX_TOTAL_BINDATA_BYTES + " bytes)");
                }
            }
            totalBytes[0] += baos.size();
            return baos.toByteArray();
        }
    }

    /** 최대 N byte 까지만 읽고, 초과 시 {@code IOException} 을 던지는 {@link InputStream} 래퍼. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream delegate;
        private final long limit;
        private long read;

        BoundedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                read++;
                if (read > limit) throw new IOException("Stream exceeds limit (" + limit + " bytes)");
            }
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                read += n;
                if (read > limit) throw new IOException("Stream exceeds limit (" + limit + " bytes)");
            }
            return n;
        }

        @Override public void close() throws IOException { delegate.close(); }
    }

    /**
     * content.hpf의 매니페스트 항목 내부 표현.
     */
    private static class ManifestItem {
        String id;
        String href;
        String mediaType;
    }
}
