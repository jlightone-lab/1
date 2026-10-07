package kr.n.nframe.newfeature;

import java.io.File;

/**
 * v16t42: 전 기능 비덮어쓰기 — 출력 파일이 이미 존재하면 덮어쓰지 않고
 *   "이름(1).확장자", "이름(2).확장자" … 순서로 첫 빈 이름을 골라 반환한다.
 *   모든 최종 파일쓰기 지점(직접변환기·레거시·dist·복사통과)이 이 유틸을 경유한다.
 */
public final class OutputNaming {

    private OutputNaming() {}

    /** 요청경로(절대) → 실제 사용경로. 후처리기(repair 등)가 실제 경로를 되찾을 때 사용. */
    private static final java.util.Map<String, String> ACTUAL =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 경로가 비어 있지 않으면 "stem(N).ext" 형태의 첫 빈 경로를 반환(안내 출력). */
    public static String unique(String path) {
        File f = new File(path);
        if (!f.exists()) return path;
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext  = dot > 0 ? name.substring(dot) : "";
        File dir = f.getParentFile();
        for (int i = 1; ; i++) {
            File cand = new File(dir, stem + "(" + i + ")" + ext);
            if (!cand.exists()) {
                System.out.println("[안내] 출력 파일이 이미 있어 새 이름으로 저장합니다: " + cand.getPath());
                ACTUAL.put(f.getAbsolutePath(), cand.getPath());
                return cand.getPath();
            }
        }
    }

    /** 요청 경로가 비덮어쓰기로 바뀌었으면 실제 사용된 경로, 아니면 요청 경로 그대로. */
    public static String actual(String requested) {
        return ACTUAL.getOrDefault(new File(requested).getAbsolutePath(), requested);
    }

    public static java.nio.file.Path unique(java.nio.file.Path path) {
        return new File(unique(path.toFile().getPath())).toPath();
    }
}
