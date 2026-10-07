package kr.n.nframe.newfeature.odtcommon;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ODT Pictures/ 폴더에 들어갈 이미지 바이트 누적 + manifest 미디어타입 관리.
 *
 * <p>키 = Pictures/ 내부 파일명(예: "image1.png"). 값 = 원본 바이트.
 * 미디어타입은 매니페스트 완결(서연 리서치 §8)을 위해 확장자→MIME 매핑.
 */
public final class PictureRegistry {

    private final Map<String, byte[]> data = new LinkedHashMap<>();
    private final Map<String, String> mediaType = new LinkedHashMap<>();
    private final Map<String, String> byContent = new java.util.HashMap<>();
    private int counter = 0;

    /**
     * 이미지 바이트를 등록하고 Pictures/ 내부 경로(예: "Pictures/image1.png")를 반환.
     * 동일 바이트가 이미 등록돼 있으면 새로 저장하지 않고 기존 경로를 재사용한다
     * (같은 이미지가 페이지마다 반복될 때 ODT 가 수백 배로 부푸는 것을 방지).
     * @param ext 확장자(점 없이, 예: "png","jpg","bmp","gif")
     */
    public String add(byte[] bytes, String ext) {
        String e = normalizeExt(ext);
        String key = contentKey(bytes);
        if (key != null) {
            String existing = byContent.get(key);
            if (existing != null) return existing;
        }
        String name = "image" + (++counter) + "." + e;
        data.put(name, bytes);
        mediaType.put(name, mimeFor(e));
        String href = "Pictures/" + name;
        if (key != null) byContent.put(key, href);
        return href;
    }

    /** 바이트 내용 해시 키(SHA-256). 동일 이미지 중복 저장 방지용. */
    private static String contentKey(byte[] bytes) {
        if (bytes == null) return null;
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                               .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            return bytes.length + ":" + java.util.Arrays.hashCode(bytes);
        }
    }

    public boolean isEmpty() { return data.isEmpty(); }

    /** name(확장자 포함, 폴더 제외) → bytes */
    public Map<String, byte[]> entries() { return data; }

    /** name → MIME (manifest 작성용) */
    public String mediaTypeOf(String name) {
        return mediaType.getOrDefault(name, "application/octet-stream");
    }

    private static String normalizeExt(String ext) {
        if (ext == null || ext.isEmpty()) return "png";
        String e = ext.trim().toLowerCase(java.util.Locale.ROOT);
        if (e.startsWith(".")) e = e.substring(1);
        if (e.equals("jpeg")) e = "jpg";
        return e.isEmpty() ? "png" : e;
    }

    private static String mimeFor(String ext) {
        switch (ext) {
            case "png":  return "image/png";
            case "jpg":  return "image/jpeg";
            case "gif":  return "image/gif";
            case "bmp":  return "image/bmp";
            case "tif":
            case "tiff": return "image/tiff";
            case "wmf":  return "image/x-wmf";
            case "emf":  return "image/x-emf";
            case "svg":  return "image/svg+xml";
            default:     return "application/octet-stream";
        }
    }
}
