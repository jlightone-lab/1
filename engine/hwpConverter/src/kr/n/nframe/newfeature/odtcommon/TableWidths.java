package kr.n.nframe.newfeature.odtcommon;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 표 열폭 분배(v16t34 문제2).
 *
 * <p>증상: 병합전용 열(span==1 시작 셀이 한 행도 없는 열)은 폭이 비어 LibreOffice 가
 * 잔여폭을 균등 분배 → 원본 비율 붕괴. 해결: 병합셀(colSpan&gt;1)의 폭을 그 셀이 덮는
 * 미상 열들에 분배해 <b>모든 열</b>에 폭을 부여한다. 소스 폭(HWPUNIT)에서만 산출 —
 * 파일별 하드코딩 없음. hwp·hwpx 가 동일 알고리즘을 공유한다.
 */
public final class TableWidths {

    private TableWidths() {}

    /**
     * @param colCount 열 수
     * @param cells    전 셀 정보. 각 원소 = {시작열 c, colSpan, 폭(HWPUNIT)}.
     *                 HWP listHeader.getWidth() / HWPX cellSz().width() 는 병합 포함 총폭.
     * @return 길이 colCount 의 열별 폭(HWPUNIT). 끝내 확정 못 한 열은 0.
     */
    public static long[] distribute(int colCount, List<long[]> cells) {
        long[] col = new long[colCount];
        boolean[] known = new boolean[colCount];
        if (colCount <= 0) return col;

        // pass1: span==1 셀로 열폭 직접 확정(전 행 스캔).
        for (long[] cell : cells) {
            int c = (int) cell[0];
            int span = (int) cell[1];
            long w = cell[2];
            if (span == 1 && c >= 0 && c < colCount && !known[c] && w > 0) {
                col[c] = w;
                known[c] = true;
            }
        }

        // pass2: 병합셀(span>1) 총폭에서 이미 아는 열 폭을 빼고 미상 열에 균등 분배.
        // 작은 span 부터 처리(중첩 병합 수렴), 변화 없을 때까지 반복.
        long[][] spanCells = cells.stream()
                .filter(x -> (int) x[1] > 1)
                .sorted(Comparator.comparingInt(x -> (int) x[1]))
                .toArray(long[][]::new);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (long[] cell : spanCells) {
                int c = (int) cell[0];
                int span = (int) cell[1];
                long w = cell[2];
                if (c < 0 || c >= colCount || w <= 0) continue;
                int end = Math.min(colCount, c + span);
                long knownSum = 0;
                int unknown = 0;
                for (int k = c; k < end; k++) {
                    if (known[k]) knownSum += col[k]; else unknown++;
                }
                if (unknown == 0) continue;
                long remain = w - knownSum;
                if (remain <= 0) continue;
                long each = remain / unknown;
                if (each <= 0) continue;
                int assigned = 0;
                for (int k = c; k < end; k++) {
                    if (known[k]) continue;
                    assigned++;
                    // 마지막 미상 열에 나머지를 몰아 합계 보존.
                    col[k] = (assigned == unknown) ? (remain - each * (unknown - 1)) : each;
                    known[k] = true;
                    changed = true;
                }
            }
        }

        // pass3: 그래도 미상인 열은 확정 열들의 평균으로(최후 안전망). 확정 열이 없으면 0 유지.
        long sum = 0; int cnt = 0;
        for (int k = 0; k < colCount; k++) if (known[k]) { sum += col[k]; cnt++; }
        if (cnt > 0) {
            long avg = sum / cnt;
            for (int k = 0; k < colCount; k++) if (!known[k]) col[k] = avg;
        }
        return col;
    }

    /**
     * 인쇄 본문폭(HWPUNIT). A4 21cm − 좌우 margin 2cm×2 = 17cm.
     * OdtWriter 페이지 레이아웃(21cm/margin 2cm)과 일치 — 파일별 하드코딩 아님.
     */
    public static final long PRINT_WIDTH_HU = Math.round(17.0 / 2.54 * 7200.0); // ≈48189

    /**
     * task7/8: 열폭 합이 인쇄 본문폭(17cm)을 넘으면 비례 축소해 합을 PRINT_WIDTH_HU 로 캡.
     * 17cm 이하 표는 그대로 반환(무회귀). 반올림 잔차는 마지막 양수 열에 몰아 합을 정확히 보존.
     */
    public static long[] capToPrintWidth(long[] colHU) {
        if (colHU == null || colHU.length == 0) return colHU;
        long total = sum(colHU);
        if (total <= PRINT_WIDTH_HU || total <= 0) return colHU;
        long[] out = new long[colHU.length];
        long assigned = 0;
        int lastPos = -1;
        for (int i = 0; i < colHU.length; i++) {
            if (colHU[i] > 0) {
                out[i] = Math.round((double) colHU[i] * PRINT_WIDTH_HU / total);
                if (out[i] <= 0) out[i] = 1;
                assigned += out[i];
                lastPos = i;
            } else {
                out[i] = colHU[i];
            }
        }
        // 잔차 보정: 합을 PRINT_WIDTH_HU 로 정확히 맞춤.
        if (lastPos >= 0) {
            long diff = PRINT_WIDTH_HU - assigned;
            long adj = out[lastPos] + diff;
            if (adj > 0) out[lastPos] = adj;
        }
        return out;
    }

    /** 열폭(HWPUNIT) → cm 문자열 배열. 0 이하는 null(폭 생략). */
    public static String[] toCm(long[] hu) {
        String[] s = new String[hu.length];
        for (int i = 0; i < hu.length; i++) s[i] = (hu[i] > 0) ? Units.hwpToCm(hu[i]) : null;
        return s;
    }

    /** 열폭 합(HWPUNIT). 표 총폭 발화용. */
    public static long sum(long[] hu) {
        long t = 0;
        for (long v : hu) if (v > 0) t += v;
        return t;
    }
}
