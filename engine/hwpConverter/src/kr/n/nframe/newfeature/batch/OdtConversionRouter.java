package kr.n.nframe.newfeature.batch;

import java.nio.file.Path;
import java.util.Locale;

import kr.n.nframe.newfeature.hwp2odt.HwpToOdtConverter;
import kr.n.nframe.newfeature.hwp2odt.Options;
import kr.n.nframe.newfeature.hwp2odt.Result;
import kr.n.nframe.newfeature.hwpx2odt.HwpxToOdtConverter;

/**
 * 단건/배치/임의경로 공통 변환 진입점. 입력 확장자로 HWP/HWPX 엔진을 선택해 ODT 를 발화한다.
 *
 * <p>엔진(hwp2odt, hwpx2odt)은 무수정 — 본 라우터는 {@code convertOne(Path,Path,Options)}만 위임 호출한다.
 * 같은 입력 폴더에 .hwp 와 .hwpx 가 섞여 있어도 파일별로 알맞은 엔진이 자동 선택되므로
 * 배치 측은 입력 종류를 구분할 필요가 없다(민준 설계 §라우팅).
 */
public final class OdtConversionRouter {
    private OdtConversionRouter() {}

    /** 출력 확장자(현재 ODT 단일). */
    public static String outExtFor(Path in) { return ".odt"; }

    /** 지원 입력 확장자 여부(.hwp / .hwpx). */
    public static boolean isSupported(Path in) {
        String n = in.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".hwp") || n.endsWith(".hwpx");
    }

    /** 확장자로 엔진을 선택해 변환. 미지원 확장자는 실패 Result. */
    public static Result convertOne(Path in, Path out, Options opts) {
        String n = in.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".hwpx")) {
            return HwpxToOdtConverter.convertOne(in, out, opts);
        }
        if (n.endsWith(".hwp")) {
            return HwpToOdtConverter.convertOne(in, out, opts);
        }
        return Result.failure(in, out, "지원하지 않는 입력 확장자: " + in.getFileName());
    }
}
