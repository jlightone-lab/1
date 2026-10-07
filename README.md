# HWPX → HWP 일괄 변환 도구 (v2.0)

**한글 프로그램이 없어도** HWPX 문서를 HWP 5.0 문서로 바꿔 주는 Windows 프로그램입니다.
한글 2010처럼 HWPX를 열 수 없는 환경에서 쓸 HWP 파일을 만들 때 사용합니다.

## 기능
- HWPX 파일 여러 개 선택 / 폴더(하위 폴더 포함)에서 자동 수집
- 원본과 같은 폴더에 같은 이름의 `.hwp` 생성 (같은 이름 HWP가 있으면 덮어쓰기)
- 변환 후 결과 HWP를 다시 읽어 **본문 글자가 원본과 같은지 자동 검증**
  - 일치 → `완료`, 다르거나 다시 읽기 실패 → `확인 필요`, 변환 불가 → `실패`
- (선택) `완료`인 경우에만 원본 HWPX 삭제 — 기본값은 원본 유지

## 사용법 (배포본)
1. `HWPX_to_HWP` 폴더 전체를 원하는 위치에 둡니다. (폴더 안의 `_internal`을 지우면 동작하지 않습니다.)
2. `HWPX_to_HWP.exe` 실행 → 파일/폴더 선택 → `변환 실행`
3. 화면 없이 쓰기: `HWPX_to_HWP.exe --convert 입력.hwpx [출력.hwp]`

## 알아둘 점
- HWPX와 HWP 5.0은 지원 기능이 완전히 같지 않습니다. 텍스트·표·이미지·글꼴·문단 서식은 최대한
  보존하지만 차트, OLE 개체 등 최신 기능은 달라질 수 있습니다. 중요한 문서는 결과를 직접 확인하세요.
- 자동 검증은 **본문 글자**를 비교합니다. 서식·배치까지 같다는 보장은 아닙니다.

## 구성
| 경로 | 내용 |
|---|---|
| `hwpx_to_hwp_converter.py` | 화면 프로그램 (Tkinter) |
| `engine/hwpConverter/` | 변환 엔진 [vsdn/hwpConverter](https://github.com/vsdn/hwpConverter) (Apache-2.0, 일부 수정 — `engine/PATCHES.md`) |
| `engine/tool/HwpxToHwpTool.java` | 변환 + 검증 도구 |
| `scripts/build_engine.sh` | 엔진 빌드 |
| `tests/sample.hwpx`, `tests/MakeSample.java` | 자동 점검용 샘플과 생성기 |

## 빌드
GitHub Actions **Build Windows EXE** 워크플로가 push마다 자동으로 빌드·점검하고,
결과 폴더를 Artifacts(`HWPX_to_HWP`)로 올립니다. 내장 Java 런타임을 포함하므로 사용자 PC에 Java가 필요 없습니다.

로컬 빌드(Windows, JDK 17+와 Python 필요):
```bash
./scripts/build_engine.sh
jlink --add-modules java.base,java.xml,java.desktop,java.logging,java.management,java.naming,java.sql,java.scripting,jdk.unsupported,jdk.zipfs,jdk.charsets --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output build/runtime
python -m PyInstaller --noconfirm --onedir --windowed --name HWPX_to_HWP --add-data "build/engine;engine" --add-data "build/runtime;runtime" hwpx_to_hwp_converter.py
```

오픈소스 고지는 `THIRD_PARTY_NOTICES.md`를 참고하세요.
