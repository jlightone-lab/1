package kr.n.nframe.test;

import java.lang.reflect.Method;

/**
 * neolord0의 hwplib을 이용해 HWP 파일을 검증한다.
 * hwplib이 예외 없이 읽을 수 있으면 구조적으로 유효한 것으로 본다.
 */
public class HwpLibValidator {
    public static void main(String[] args) throws Exception {
        String[] files = args.length > 0 ? args : new String[]{
            "hwp/test_hwpx2_conv.hwp",
            "output/test_hwpx2_conv.hwp",
            "output/isolate_D_fixed.hwp",
            "output/cloned_section.hwp"
        };

        // 컴파일 타임 의존성 없이 hwplib을 호출하기 위해 reflection 사용
        Class<?> readerClass = Class.forName("kr.dogfoot.hwplib.reader.HWPReader");
        Method readMethod = readerClass.getMethod("fromFile", String.class);

        for (String file : files) {
            System.out.print(file + ": ");
            try {
                Object hwpFile = readMethod.invoke(null, file);
                System.out.println("OK (hwplib read success)");
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                System.out.println("FAILED: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                // 상위 3개 스택 프레임 출력
                StackTraceElement[] st = cause.getStackTrace();
                for (int i = 0; i < Math.min(5, st.length); i++) {
                    System.out.println("  at " + st[i]);
                }
            }
        }
    }
}
