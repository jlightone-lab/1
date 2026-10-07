package kr.n.nframe.newfeature;

import java.io.File;
import java.util.Locale;

/**
 * v15.35-newfeature : hwpConverter 단일 진입점.
 *
 * <p>ODT 관련 인자는 본 패키지의 {@link OdfConverter} 로 라우팅하고,
 *   그 외 모든 hwp/hwpx/md/dist 관련 인자는 기존 {@link kr.n.nframe.HwpConverter}
 *   에 그대로 위임한다. App 영역(HwpConverter.java) 무수정.
 *
 * <h2>지원 모드</h2>
 * <pre>
 *   [ODT 단건]
 *     hwpConverter &lt;in.odt&gt;  &lt;out.hwp|hwpx&gt;     ODT → HWP / HWPX
 *     hwpConverter &lt;in.hwp|hwpx&gt; &lt;out.odt&gt;     HWP / HWPX → ODT
 *
 *   [ODT 폴더 배치]
 *     hwpConverter &lt;inDir&gt; &lt;outDir&gt; --to-hwp     (디렉토리 안 .odt → .hwp)
 *     hwpConverter &lt;inDir&gt; &lt;outDir&gt; --to-hwpx    (디렉토리 안 .odt → .hwpx)
 *     hwpConverter &lt;inDir&gt; &lt;outDir&gt; --to-odt     (디렉토리 안 .hwp/.hwpx → .odt)
 *
 *   [그 외 — HwpConverter 위임]
 *     hwp ↔ hwpx, hwp/hwpx → md, md → hwp/hwpx, --dist, 다건파일 등 모두 그대로.
 * </pre>
 */
public class HwpConverterCli {

    public static void main(String[] args) throws Exception {
        if (args == null || args.length == 0) {
            kr.n.nframe.HwpConverter.main(args);
            printOdtUsage();
            return;
        }

        // v16t42: 대시 1개 표기(-to-hwp 등)를 정규 표기(--to-hwp)로 보정 후 검증.
        args = normalizeFlags(args);
        rejectUnknownFlags(args);

        // [v16t56 신기능 / v16t58 일반화] 확장자 무관 시그니처 판별 — 모든 입력 위치
        //   파일(단건 args[0]뿐 아니라 레거시 --dist 입력, 다중 파일 리스트 전부)의 실제
        //   포맷이 확장자와 다르거나 확장자가 없으면 시그니처 우선으로 정규 확장자 temp
        //   사본을 만들어 하류 변환 경로가 올바로 라우팅되게 한다. 출력/암호/플래그 인자는
        //   건드리지 않으며, UNKNOWN/정상일치는 무변(temp 미생성 → 회귀 위험 0).
        args = resolveInputBySignature(args);

        // v16t41: md↔odt 비활성 가드 + 확장 라우팅(파일목록 →odt / odt→, 동일포맷 복사통과)
        if (handleExtendedRouting(args)) return;

        Mode mode = detectMode(args);
        switch (mode) {
            case ODT_SINGLE:
                // v16.00-newfeature : odt → hwp/hwpx 는 MD 미경유 직접변환(OdtDirectConverter).
                //   hwp/hwpx → odt 는 기존 OdfConverter(MD 경유) 그대로 유지.
                if (args[0].toLowerCase(Locale.ROOT).endsWith(".odt")) {
                    kr.n.nframe.newfeature.OdtDirectConverter.main(args);
                } else {
                    kr.n.nframe.newfeature.OdfConverter.main(args);
                }
                return;
            case ODT_BATCH_TO_HWP:
                // v16.00-newfeature : 폴더 단위도 직접변환을 우선. 실패 시 호출자가 로그로 확인.
                new OdtDirectConverter().batchOdtTo(args[0], args[1], "hwp");
                return;
            case ODT_BATCH_TO_HWPX:
                new OdtDirectConverter().batchOdtTo(args[0], args[1], "hwpx");
                return;
            case ANY_BATCH_TO_ODT:
                new OdfConverter().batchAnyToOdt(args[0], args[1]);
                return;
            case NONE:
            default:
                kr.n.nframe.HwpConverter.main(args);
                // v16t18: MD → HWP/HWPX 변환 결과에 손상팝업 + 표 셀 겹침 후처리.
                applyMdHwpRepairIfApplicable(args);
        }
    }

    /**
     * [v16t56 신기능 / v16t58 일반화] 입력 위치의 실제 포맷을 {@link FormatSniffer} 로
     * 판별하여 확장자와 불일치(또는 확장자 없음)이면 시그니처가 가리키는 정규 확장자로
     * OS temp 사본을 만들고 해당 인자를 그 경로로 치환한다.
     *
     * <p>v16t58: args[0] 단건뿐 아니라 <strong>모든 입력 위치 인자</strong>(레거시
     *   {@code --dist} 입력, 다중 파일 리스트 포함)를 정규화한다. 출력·암호·플래그
     *   인자는 절대 건드리지 않는다. 정상(확장자==시그니처)·UNKNOWN 은 무변(temp
     *   미생성)이므로 회귀 위험 ≈ 0. temp 는 JVM 종료 시 자동 삭제.</p>
     *
     * <p>입력/출력 위치 식별 규칙(사전 파서 재구현 없이):</p>
     * <ol>
     *   <li>플래그({@code -} 로 시작) 제외.</li>
     *   <li>마지막 위치인자 = 출력 → 제외.</li>
     *   <li>레거시 dist({@code --dist} 있고 {@code --to-*} 없음)는 끝 2개(출력+암호) 제외.</li>
     *   <li>남은 위치인자: {@code isFile} 이면 {@link #normalizeOneBySignature}, 첫
     *       위치인자가 {@code isDirectory} 이면 {@link #normalizeDirBySignature}.</li>
     * </ol>
     */
    private static String[] resolveInputBySignature(String[] args) {
        try {
            if (args == null || args.length == 0) return args;

            // 위치인자 인덱스 수집 (플래그 제외) + dist/to-* 동반 여부
            java.util.List<Integer> posIdx = new java.util.ArrayList<>();
            boolean hasDist = false, hasToFlag = false;
            for (int i = 0; i < args.length; i++) {
                String t = args[i] == null ? "" : args[i].trim();
                String low = t.toLowerCase(Locale.ROOT);
                if (low.equals("--dist")) { hasDist = true; continue; }
                if (low.equals("--to-hwp") || low.equals("--to-hwpx")
                        || low.equals("--to-md") || low.equals("--to-odt")) { hasToFlag = true; continue; }
                if (t.startsWith("-")) continue;            // 기타 플래그(--no-copy, --out-hwp 등)
                posIdx.add(i);
            }
            if (posIdx.isEmpty()) return args;

            // 출력(+암호) 위치 제외 개수
            int tail = 1;                                   // 마지막 = 출력
            if (hasDist && !hasToFlag && posIdx.size() >= 2) tail = 2;  // 레거시 dist: 출력+암호
            int inputCount = Math.max(0, posIdx.size() - tail);

            String[] out = args.clone();
            boolean changed = false;
            for (int k = 0; k < inputCount; k++) {
                int ai = posIdx.get(k);
                File in = new File(args[ai]);
                if (in.isFile()) {
                    String norm = normalizeOneBySignature(in);          // 기존 메서드 재사용
                    if (norm != null) { out[ai] = norm; changed = true; }
                } else if (k == 0 && in.isDirectory()) {
                    String nd = normalizeDirBySignature(in);            // 기존 메서드 재사용
                    if (nd != null) { out[ai] = nd; changed = true; }
                }
            }
            return changed ? out : args;
        } catch (Exception e) {
            System.err.println("[FormatSniffer] 입력 시그니처 판별 건너뜀: " + e.getMessage());
        }
        return args;
    }

    /** 단건: 확장자≠시그니처면 정규 확장자 temp 사본 경로 반환, 무변경이면 null. */
    private static String normalizeOneBySignature(File in) throws java.io.IOException {
        FormatSniffer.Format fmt = FormatSniffer.sniff(in);
        String canon = FormatSniffer.canonicalExt(fmt);
        if (canon == null) return null;                       // UNKNOWN/md 등 무변
        String name = in.getName();
        if (canon.equalsIgnoreCase(extOf(name))) return null; // 정상: 무변
        java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("hwpconv_sniff_");
        tmpDir.toFile().deleteOnExit();
        java.nio.file.Path dst = tmpDir.resolve(stripExt(name) + "." + canon);
        java.nio.file.Files.copy(in.toPath(), dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        dst.toFile().deleteOnExit();
        System.err.println("[FormatSniffer] 확장자(" + (extOf(name).isEmpty() ? "<없음>" : extOf(name))
                + ")와 실제 포맷(" + canon + ") 불일치 — 시그니처 우선: " + name);
        return dst.toString();
    }

    /** 배치(dir): 불일치 파일이 하나라도 있으면 전체를 정규 확장자로 temp dir 미러, 없으면 null. */
    private static String normalizeDirBySignature(File dir) throws java.io.IOException {
        File[] files = dir.listFiles(File::isFile);
        if (files == null || files.length == 0) return null;
        boolean anyMismatch = false;
        for (File f : files) {
            String canon = FormatSniffer.canonicalExt(FormatSniffer.sniff(f));
            if (canon != null && !canon.equalsIgnoreCase(extOf(f.getName()))) { anyMismatch = true; break; }
        }
        if (!anyMismatch) return null;
        java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("hwpconv_sniffdir_");
        tmpDir.toFile().deleteOnExit();
        for (File f : files) {
            String canon = FormatSniffer.canonicalExt(FormatSniffer.sniff(f));
            String outName;
            if (canon != null && !canon.equalsIgnoreCase(extOf(f.getName()))) {
                outName = stripExt(f.getName()) + "." + canon;
                System.err.println("[FormatSniffer] (배치) " + f.getName() + " → " + outName);
            } else {
                outName = f.getName();
            }
            java.nio.file.Path dst = tmpDir.resolve(outName);
            java.nio.file.Files.copy(f.toPath(), dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dst.toFile().deleteOnExit();
        }
        return tmpDir.toString();
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1);
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(0, i);
    }

    /** args 의 출력 경로 또는 출력 디렉터리 안 .hwp/.hwpx 에 repair 적용. 입력이 .md 인 경우만. */
    private static void applyMdHwpRepairIfApplicable(String[] args) {
        try {
            if (args == null || args.length < 2) return;
            String in  = args[0].trim().toLowerCase(Locale.ROOT);
            String out = args[1].trim();
            String outLow = out.toLowerCase(Locale.ROOT);

            // 단건: <in.md> <out.hwp|hwpx>
            if (in.endsWith(".md") && (outLow.endsWith(".hwp") || outLow.endsWith(".hwpx"))) {
                // v16t42: 비덮어쓰기로 실제 출력명이 바뀌었을 수 있으므로 실제 경로에 적용.
                MdHwpRepairPostProcessor.repair(OutputNaming.actual(out));
                return;
            }

            // 배치: <inDir> <outDir> --to-hwp|--to-hwpx (입력 dir 안에 .md 가 있을 때)
            File inDir  = new File(args[0]);
            File outDir = new File(args[1]);
            if (inDir.isDirectory() && outDir.isDirectory()) {
                File[] mdIn = inDir.listFiles((d,n) -> n.toLowerCase(Locale.ROOT).endsWith(".md"));
                boolean hasMd = mdIn != null && mdIn.length > 0;
                if (hasMd) {
                    File[] outs = outDir.listFiles((d,n) -> {
                        String s = n.toLowerCase(Locale.ROOT);
                        return s.endsWith(".hwp") || s.endsWith(".hwpx");
                    });
                    if (outs != null) for (File o : outs) {
                        try { MdHwpRepairPostProcessor.repair(o.getAbsolutePath()); }
                        catch (Exception ignore) { /* per-file best effort */ }
                    }
                }
            }
        } catch (Exception ignore) {
            // 후처리 실패는 변환 자체를 막지 않음 — 진단 메시지만 출력하고 진행
            System.err.println("[Repair] 후처리 중 무시할 수 있는 오류 발생: " + ignore.getMessage());
        }
    }

    /** v16t41: 전 모듈(CLI·HwpConverter)에서 인식하는 '-' 옵션 전체. */
    private static final java.util.Set<String> KNOWN_FLAGS = new java.util.HashSet<>(java.util.Arrays.asList(
            "--to-hwp", "--to-hwpx", "--to-md", "--to-odt",
            "--dist", "--no-copy", "--no-print", "--out-hwp", "--out-hwpx",
            "--no-embed", "--no-sidecar", "--structure"));

    /**
     * v16t42: 대시 1개로 붙여 쓴 알려진 옵션(예: "-to-hwp")을 정규 표기("--to-hwp")로
     * 보정한다. 분리형("-to -md")이나 미지의 옵션은 보정하지 않고 기존대로
     * {@link #rejectUnknownFlags} 에서 거부된다 (방어 의도 유지).
     */
    private static String[] normalizeFlags(String[] args) {
        String[] out = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            out[i] = a;
            if (a == null) continue;
            String t = a.trim();
            if (t.startsWith("-") && !t.startsWith("--")) {
                String cand = "-" + t.toLowerCase(Locale.ROOT);
                if (KNOWN_FLAGS.contains(cand)) {
                    System.out.println("[안내] 옵션 표기를 보정했습니다: " + t + " -> " + cand);
                    out[i] = cand;
                }
            }
        }
        return out;
    }

    /**
     * v16t41 방어수정: 인식할 수 없는 '-' 인자(예: "-to -md", "-to-md")가 섞이면
     * 조용히 기본동작(자동 판별 변환)으로 진행하지 않고 명확한 오류로 즉시 종료한다.
     * 정상 옵션·경로만으로 이루어진 호출은 종전과 완전히 동일하게 동작한다.
     */
    private static void rejectUnknownFlags(String[] args) {
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (String a : args) {
            if (a == null) continue;
            String t = a.trim();
            if (t.startsWith("-") && !KNOWN_FLAGS.contains(t.toLowerCase(Locale.ROOT))) bad.add(t);
        }
        if (bad.isEmpty()) return;
        System.err.println("[오류] 인식할 수 없는 옵션입니다: " + String.join(" ", bad));
        System.err.println("       변환 옵션:  --to-hwp | --to-hwpx | --to-md | --to-odt   (대시 2개 '--')");
        System.err.println("       배포 옵션:  --dist [--no-copy] [--no-print] [--out-hwp|--out-hwpx]");
        System.err.println("       예) 잘못됨: \"-to -md\" 또는 \"-to-md\"   →   올바름: \"--to-md\"");
        System.err.println("       ※ '-' 로 시작하는 암호는 옵션과 구분할 수 없어 지원되지 않습니다.");
        System.exit(2);
    }

    /**
     * v16t41 확장 라우팅. 처리했으면 true (호출자는 즉시 종료).
     *
     * <p>덕수 결정(2026-06-10): ★마크다운 ↔ ODT(md→odt, odt→md) 기능은 비활성★.
     *   호출경로를 여기서 차단한다. 클래스는 삭제하지 않음.
     *   hwp/hwpx↔odt, hwp↔hwpx, hwp/hwpx↔md, dist 는 전부 종전과 동일.
     * <pre>
     *   // [비활성 — 결정 기록] md→odt 단건이 살아있다면 이렇게 배선했을 자리:
     *   //   new MdToOdtWriter().write(args[0], args[1]);
     *   // [비활성 — 결정 기록] odt→md 단건이 살아있다면 이렇게 배선했을 자리:
     *   //   new kr.n.nframe.OdtToMdConverter().convert(args[0], args[1]);
     * </pre>
     *
     * <p>추가 배선(덕수 승인): 파일목록 →odt 루프(HwpToOdtConverter/HwpxToOdtConverter 직접변환),
     *   파일목록 odt→hwp/hwpx 루프(OdtDirectConverter), 동일포맷 hwp&gt;hwp·hwpx&gt;hwpx 원본복사 통과.
     */
    private static boolean handleExtendedRouting(String[] args) throws Exception {
        boolean toOdt = false, toMd = false, toHwp = false, toHwpx = false, dist = false;
        java.util.List<String> pos = new java.util.ArrayList<>();
        for (String a : args) {
            if (a == null) continue;
            String t = a.trim();
            String low = t.toLowerCase(Locale.ROOT);
            if ("--to-odt".equals(low)) toOdt = true;
            else if ("--to-md".equals(low)) toMd = true;
            else if ("--to-hwp".equals(low)) toHwp = true;
            else if ("--to-hwpx".equals(low)) toHwpx = true;
            else if ("--dist".equals(low)) dist = true;
            else if (!low.startsWith("-")) pos.add(t);
        }
        // v16t42: --dist 가 --to-hwp/--to-hwpx 와 함께 오면 = 파일목록 일괄 "변환" 모드.
        //   (암호화 아님 — 마지막 위치인자가 출력폴더, 나머지가 입력파일들. md/odt 입력 포함)
        //   진짜 dist(입력/출력/암호 3인자 배포)는 --to-hwp(x) 를 동반하지 않으므로 종전 그대로.
        if (dist && (toHwp || toHwpx)) {
            String target = toHwp ? "hwp" : "hwpx";
            if (pos.size() < 2) {
                System.err.println("[오류] --dist --to-" + target + " 파일목록 변환은 <입력파일...> <출력폴더> 가 필요합니다.");
                System.exit(2);
            }
            return batchFilesConvertTo(pos.subList(0, pos.size() - 1), pos.get(pos.size() - 1), target);
        }
        // v16t43: --dist + --to-odt = 파일목록 →odt 일괄 변환 (직접변환기, MD 중간파일 없음).
        if (dist && toOdt) {
            if (pos.size() < 2) {
                System.err.println("[오류] --dist --to-odt 파일목록 변환은 <입력파일...> <출력폴더> 가 필요합니다.");
                System.exit(2);
            }
            java.util.List<String> ins = pos.subList(0, pos.size() - 1);
            for (String in : ins) {
                if (in.toLowerCase(Locale.ROOT).endsWith(".md")) exitMdOdtDisabled();
            }
            return batchFilesToOdt(ins, pos.get(pos.size() - 1));
        }
        // v16t43: --dist + --to-md = 파일목록 →md 일괄 변환 (기존 hwp/hwpx→md 파이프라인).
        if (dist && toMd) {
            if (pos.size() < 2) {
                System.err.println("[오류] --dist --to-md 파일목록 변환은 <입력파일...> <출력폴더> 가 필요합니다.");
                System.exit(2);
            }
            java.util.List<String> ins = pos.subList(0, pos.size() - 1);
            boolean allOdt = true;
            for (String in : ins) {
                if (!in.toLowerCase(Locale.ROOT).endsWith(".odt")) { allOdt = false; break; }
            }
            if (allOdt) exitMdOdtDisabled();
            java.util.List<File> files = new java.util.ArrayList<>();
            for (String in : ins) files.add(new File(in));
            kr.n.nframe.HwpConverter.BatchResult br =
                    new kr.n.nframe.HwpConverter().batchFilesToMd(files, pos.get(pos.size() - 1));
            if (br.fail > 0) System.exit(3);
            return true;
        }
        if (dist) return false; // 진짜 dist(암호 배포)는 전부 기존 HwpConverter 경로 그대로

        // ── ① md↔odt 비활성 가드 ───────────────────────────────────────────
        // 단건 2-인자: <in.md> <out.odt> / <in.odt> <out.md>
        if (args.length == 2 && pos.size() == 2) {
            String a0 = pos.get(0).toLowerCase(Locale.ROOT);
            String a1 = pos.get(1).toLowerCase(Locale.ROOT);
            if ((a0.endsWith(".md") && a1.endsWith(".odt")) || (a0.endsWith(".odt") && a1.endsWith(".md"))) {
                exitMdOdtDisabled();
            }
            // 단건 동일포맷 복사 통과: <in.hwp> <out.hwp> / <in.hwpx> <out.hwpx>
            String sameExt = sameKnownExt(a0, a1);
            if (sameExt != null) {
                copyThrough(new File(pos.get(0)), new File(pos.get(1)));
                return true;
            }
        }

        if (pos.size() >= 2) {
            File first = new File(pos.get(0));
            String outArg = pos.get(pos.size() - 1);
            java.util.List<String> ins = pos.subList(0, pos.size() - 1);

            if (toOdt) {
                if (first.isDirectory()) {
                    // 폴더에 .md 만 있고 hwp/hwpx 가 없으면 = md→odt 폴더배치 → 비활성
                    if (containsExt(first, ".md") && !containsExt(first, ".hwp") && !containsExt(first, ".hwpx")) {
                        exitMdOdtDisabled();
                    }
                    return false; // 폴더 →odt 는 기존 ANY_BATCH_TO_ODT 그대로
                }
                // 파일목록 →odt
                for (String in : ins) {
                    if (in.toLowerCase(Locale.ROOT).endsWith(".md")) exitMdOdtDisabled();
                }
                return batchFilesToOdt(ins, outArg);
            }

            if (toMd) {
                if (first.isDirectory()) {
                    if (containsExt(first, ".odt") && !containsExt(first, ".hwp") && !containsExt(first, ".hwpx")) {
                        exitMdOdtDisabled();
                    }
                    return false;
                }
                boolean allOdt = true;
                for (String in : ins) {
                    if (!in.toLowerCase(Locale.ROOT).endsWith(".odt")) { allOdt = false; break; }
                }
                if (allOdt) exitMdOdtDisabled();
                return false; // hwp/hwpx →md 파일목록은 기존 경로 그대로
            }

            if (toHwp || toHwpx) {
                String target = toHwp ? "hwp" : "hwpx";
                if (first.isDirectory()) {
                    // 폴더 동일포맷 복사 통과: 폴더에 대상 확장자만 있고 변환대상(.md/.odt/반대포맷)이 없을 때만
                    String other = toHwp ? ".hwpx" : ".hwp";
                    if (containsExt(first, "." + target)
                            && !containsExt(first, other)
                            && !containsExt(first, ".md")
                            && !containsExt(first, ".odt")) {
                        return batchCopySameExt(first, new File(outArg), "." + target);
                    }
                    return false; // 그 외 폴더배치는 기존 경로 그대로
                }
                // 파일목록: 전부 .odt → OdtDirectConverter 루프 / 전부 대상 동일포맷 → 복사 통과
                boolean allOdt = true, allSame = true;
                for (String in : ins) {
                    String low = in.toLowerCase(Locale.ROOT);
                    if (!low.endsWith(".odt")) allOdt = false;
                    if (!low.endsWith("." + target)) allSame = false;
                }
                if (allOdt) return batchFilesOdtTo(ins, outArg, target);
                if (allSame) return batchFilesCopy(ins, outArg);
                return false; // 혼합/기타는 기존 경로 그대로
            }
        }
        return false;
    }

    private static void exitMdOdtDisabled() {
        System.err.println("[안내] 마크다운 ↔ ODT 변환 기능은 비활성화되어 있습니다. (md→odt, odt→md 사용 불가)");
        System.err.println("       지원: hwp↔hwpx, hwp/hwpx↔md, hwp/hwpx↔odt, --dist");
        System.exit(4);
    }

    /** 두 경로가 같은 hwp/hwpx 확장자면 그 확장자("hwp"|"hwpx"), 아니면 null. */
    private static String sameKnownExt(String low0, String low1) {
        if (low0.endsWith(".hwpx") && low1.endsWith(".hwpx")) return "hwpx";
        if (low0.endsWith(".hwp") && low1.endsWith(".hwp") && !low0.endsWith(".hwpx")) return "hwp";
        return null;
    }

    private static void copyThrough(File in, File out) throws java.io.IOException {
        if (!in.isFile()) {
            System.err.println("[오류] 입력 파일을 찾을 수 없습니다: " + in);
            System.exit(1);
        }
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        out = new File(OutputNaming.unique(out.getPath())); // v16t42 비덮어쓰기
        java.nio.file.Files.copy(in.toPath(), out.toPath());
        System.out.println("[안내] 동일 포맷 — 변환 없이 원본을 복사했습니다: " + in.getName() + " -> " + out.getPath());
    }

    /**
     * v16t42: --dist + --to-hwp(x) 파일목록 일괄 변환 (암호화 아님).
     * 입력 확장자별 디스패치: 동일포맷=복사통과, hwp↔hwpx, md→hwp(x), odt→hwp(x).
     */
    private static boolean batchFilesConvertTo(java.util.List<String> ins, String outDirArg, String target) throws Exception {
        File outDir = new File(outDirArg);
        outDir.mkdirs();
        int ok = 0, fail = 0;
        for (String in : ins) {
            File f = new File(in);
            String low = in.toLowerCase(Locale.ROOT);
            try {
                if (!f.isFile()) throw new IllegalArgumentException("입력 파일을 찾을 수 없습니다: " + in);
                if (low.endsWith("." + target)) {
                    copyThroughQuiet(f, new File(outDir, f.getName()));
                    ok++;
                    continue;
                }
                String outPath = OutputNaming.unique(new File(outDir, stem(f.getName()) + "." + target).getPath());
                if (low.endsWith(".odt")) {
                    OdtDirectConverter c = new OdtDirectConverter();
                    if ("hwpx".equals(target)) c.convertOdtToHwpx(f.getPath(), outPath);
                    else c.convertOdtToHwp(f.getPath(), outPath);
                } else if (low.endsWith(".md")) {
                    kr.n.nframe.HwpMdConverter c = new kr.n.nframe.HwpMdConverter();
                    if ("hwpx".equals(target)) c.convertMarkdownToHwpx(f.getPath(), outPath);
                    else c.convertMarkdownToHwp(f.getPath(), outPath);
                    try { MdHwpRepairPostProcessor.repair(outPath); }
                    catch (Exception ignore) { /* 후처리 실패는 변환을 막지 않음 */ }
                } else if (low.endsWith(".hwpx")) {
                    new kr.n.nframe.HwpConverter().convertHwpxToHwp(f.getPath(), outPath);
                } else if (low.endsWith(".hwp")) {
                    new kr.n.nframe.HwpConverter().convertHwpToHwpx(f.getPath(), outPath);
                } else {
                    throw new IllegalArgumentException("지원하지 않는 입력 확장자: " + in);
                }
                System.out.println("[OK] " + f.getName() + " -> " + outPath);
                ok++;
            } catch (Exception e) {
                System.err.println("[FAIL] " + in + " : " + e.getMessage());
                fail++;
            }
        }
        System.out.println("[배치완료] →" + target + " 성공 " + ok + "건 / 실패 " + fail + "건");
        if (fail > 0) System.exit(3);
        return true;
    }

    /** 파일목록 hwp/hwpx → odt (직접변환기 루프). */
    private static boolean batchFilesToOdt(java.util.List<String> ins, String outDirArg) throws Exception {
        File outDir = new File(outDirArg);
        outDir.mkdirs();
        int ok = 0, fail = 0;
        for (String in : ins) {
            File f = new File(in);
            String low = in.toLowerCase(Locale.ROOT);
            java.nio.file.Path outP = new File(outDir, stem(f.getName()) + ".odt").toPath();
            try {
                if (!f.isFile()) throw new IllegalArgumentException("입력 파일을 찾을 수 없습니다: " + in);
                outP = OutputNaming.unique(outP); // v16t42 비덮어쓰기
                if (low.endsWith(".hwpx")) {
                    kr.n.nframe.newfeature.hwp2odt.Result r =
                            kr.n.nframe.newfeature.hwpx2odt.HwpxToOdtConverter.convertOne(f.toPath(), outP, null);
                    if (!r.ok) throw new RuntimeException(r.message);
                } else if (low.endsWith(".hwp")) {
                    kr.n.nframe.newfeature.hwp2odt.Result r =
                            kr.n.nframe.newfeature.hwp2odt.HwpToOdtConverter.convertOne(f.toPath(), outP, null);
                    if (!r.ok) throw new RuntimeException(r.message);
                } else {
                    throw new IllegalArgumentException("--to-odt 입력은 .hwp/.hwpx 만 지원합니다: " + in);
                }
                System.out.println("[OK] " + f.getName() + " -> " + outP);
                ok++;
            } catch (Exception e) {
                System.err.println("[FAIL] " + in + " : " + e.getMessage());
                fail++;
            }
        }
        System.out.println("[배치완료] →odt 성공 " + ok + "건 / 실패 " + fail + "건");
        if (fail > 0) System.exit(3);
        return true;
    }

    /** 파일목록 odt → hwp/hwpx (OdtDirectConverter 루프). */
    private static boolean batchFilesOdtTo(java.util.List<String> ins, String outDirArg, String target) throws Exception {
        File outDir = new File(outDirArg);
        outDir.mkdirs();
        OdtDirectConverter conv = new OdtDirectConverter();
        int ok = 0, fail = 0;
        for (String in : ins) {
            File f = new File(in);
            File out = new File(outDir, stem(f.getName()) + "." + target);
            try {
                if (!f.isFile()) throw new IllegalArgumentException("입력 ODT 파일을 찾을 수 없습니다: " + in);
                out = new File(OutputNaming.unique(out.getPath())); // v16t42 비덮어쓰기
                if ("hwpx".equals(target)) conv.convertOdtToHwpx(f.getPath(), out.getPath());
                else conv.convertOdtToHwp(f.getPath(), out.getPath());
                System.out.println("[OK] " + f.getName() + " -> " + out.getPath());
                ok++;
            } catch (Exception e) {
                System.err.println("[FAIL] " + in + " : " + e.getMessage());
                fail++;
            }
        }
        System.out.println("[배치완료] odt→" + target + " 성공 " + ok + "건 / 실패 " + fail + "건");
        if (fail > 0) System.exit(3);
        return true;
    }

    /** 파일목록 동일포맷 복사 통과. */
    private static boolean batchFilesCopy(java.util.List<String> ins, String outDirArg) throws Exception {
        File outDir = new File(outDirArg);
        outDir.mkdirs();
        int ok = 0, fail = 0;
        for (String in : ins) {
            File f = new File(in);
            try {
                copyThroughQuiet(f, new File(outDir, f.getName()));
                ok++;
            } catch (Exception e) {
                System.err.println("[FAIL] " + in + " : " + e.getMessage());
                fail++;
            }
        }
        System.out.println("[배치완료] 동일 포맷 — 변환 없이 복사 " + ok + "건 / 실패 " + fail + "건");
        if (fail > 0) System.exit(3);
        return true;
    }

    /** 폴더 동일포맷 복사 통과. */
    private static boolean batchCopySameExt(File inDir, File outDir, String dotExt) throws Exception {
        outDir.mkdirs();
        File[] fs = inDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(dotExt));
        int ok = 0, fail = 0;
        if (fs != null) for (File f : fs) {
            try {
                copyThroughQuiet(f, new File(outDir, f.getName()));
                ok++;
            } catch (Exception e) {
                System.err.println("[FAIL] " + f.getPath() + " : " + e.getMessage());
                fail++;
            }
        }
        System.out.println("[배치완료] 동일 포맷 — 변환 없이 복사 " + ok + "건 / 실패 " + fail + "건");
        if (fail > 0) System.exit(3);
        return true;
    }

    private static void copyThroughQuiet(File in, File out) throws java.io.IOException {
        if (!in.isFile()) throw new java.io.IOException("입력 파일을 찾을 수 없습니다: " + in);
        out = new File(OutputNaming.unique(out.getPath())); // v16t42 비덮어쓰기
        java.nio.file.Files.copy(in.toPath(), out.toPath());
        System.out.println("[OK] 복사 " + in.getName() + " -> " + out.getPath());
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    enum Mode { NONE, ODT_SINGLE, ODT_BATCH_TO_HWP, ODT_BATCH_TO_HWPX, ANY_BATCH_TO_ODT }

    /**
     * 인자 패턴을 보고 ODT 관련 모드를 판별한다.
     * ODT 가 전혀 관련 없으면 {@link Mode#NONE} 을 반환해 호출자가 HwpConverter 로 위임하게 한다.
     */
    static Mode detectMode(String[] args) {
        boolean hasToOdt  = false;
        boolean hasToHwp  = false;
        boolean hasToHwpx = false;
        for (String a : args) {
            if (a == null) continue;
            String t = a.trim().toLowerCase(Locale.ROOT);
            if ("--to-odt".equals(t))   hasToOdt = true;
            else if ("--to-hwp".equals(t))  hasToHwp = true;
            else if ("--to-hwpx".equals(t)) hasToHwpx = true;
        }

        // 단건: 정확히 2개 인자이고 .odt 가 입력 또는 출력에 등장
        if (args.length == 2) {
            String a0 = args[0].toLowerCase(Locale.ROOT);
            String a1 = args[1].toLowerCase(Locale.ROOT);
            if (a0.endsWith(".odt") || a1.endsWith(".odt")) {
                return Mode.ODT_SINGLE;
            }
        }

        // 폴더 배치: 입력 디렉토리이고 (--to-odt 또는 입력 디렉토리 안에 .odt 존재)
        if (args.length >= 3) {
            File in = new File(args[0]);
            if (in.isDirectory()) {
                if (hasToOdt) return Mode.ANY_BATCH_TO_ODT;
                if (containsExt(in, ".odt")) {
                    if (hasToHwpx) return Mode.ODT_BATCH_TO_HWPX;
                    if (hasToHwp)  return Mode.ODT_BATCH_TO_HWP;
                    // 미지정 → HwpConverter 에 위임 (안내 메시지 자동 출력)
                }
            }
        }

        return Mode.NONE;
    }

    private static boolean containsExt(File dir, String dotExt) {
        File[] fs = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(dotExt));
        return fs != null && fs.length > 0;
    }

    private static void printOdtUsage() {
        System.out.println();
        System.out.println("  [추가 ODF 변환 옵션 — v15.35-newfeature]");
        System.out.println("    hwpConverter <input.odt>     <output.hwp|hwpx>");
        System.out.println("    hwpConverter <input.hwp|hwpx> <output.odt>");
        System.out.println("    hwpConverter <inputDir>      <outputDir>  --to-hwp     (폴더 .odt → .hwp)");
        System.out.println("    hwpConverter <inputDir>      <outputDir>  --to-hwpx    (폴더 .odt → .hwpx)");
        System.out.println("    hwpConverter <inputDir>      <outputDir>  --to-odt     (폴더 .hwp/.hwpx → .odt)");
        System.out.println();
    }
}
