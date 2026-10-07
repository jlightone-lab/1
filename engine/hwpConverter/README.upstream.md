# hwpConverter

github.com/neolord0님의 kr.dogfoot:hwplib 공유에 감사드립니다.

HWP · HWPX · ODT 변환기와 Markdown 변환, 한글 배포용(DRM) 문서 변환기.

- **HWPX → HWP** : 직접 바이너리 구현 (자체 writer)
- **HWP → HWPX** : `kr.dogfoot:hwplib` / `hwpxlib` / `hwp2hwpx` 기반 + 후처리
- **HWP → 배포용 HWP** : AES-128 암호화 ViewText 스트림 생성 (DRM 플래그 · 복사/인쇄 방지)
- **ODT ↔ HWP/HWPX** : OpenDocument Text 양방향 변환 (ODT → HWP/HWPX 는 MD 미경유 직접변환, HWP/HWPX → ODT 지원)
- **Markdown ↔ HWP/HWPX** : MD → HWP/HWPX 구조 변환(4개 전략), HWP/HWPX → MD 추출

CLI로도 지원하고 라이브러리로도 지원합니다. CLI 진입점은 확장자 없이도 파일 시그니처로
포맷을 자동 판별하는 `HwpConverterCli` 로 승격되었습니다.

문의 혹은 버그 리포트는 cowork@nsoftware.kr 로 전달바랍니다.

## 요구사항

- **Java 8 이상** (`javac --release 8` 타겟)
- Apache POI 5.2.5 (OLE2 컨테이너)
- 외부 JAR 은 `lib/` 폴더에 포함되어 있음

## 빌드

```bash
# 소스 목록 갱신 (필요 시)
find src -name "*.java" > build/sources.txt

# 컴파일 + 패키징
javac --release 8 -d build/classes -cp "lib/*" -encoding UTF-8 @build/sources.txt
jar cfm build/hwpConverter.jar build/MANIFEST.MF -C build/classes kr
```

Eclipse 사용자는 `.classpath` · `.project` 가 포함되어 바로 임포트 가능.

## 실행

### Windows
```cmd
hwpConverter.bat <input.hwpx> <output.hwp>
```

### Linux / macOS
```bash
chmod +x release/hwpConverter.sh   # 최초 1회
./release/hwpConverter.sh <input.hwpx> <output.hwp>
```

### 사용 예

| 작업 | 명령 |
|------|------|
| HWPX → HWP | `hwpConverter.bat <in.hwpx> <out.hwp>` |
| HWP → HWPX | `hwpConverter.bat <in.hwp> <out.hwpx>` |
| ODT → HWP/HWPX | `hwpConverter.bat <in.odt> <out.hwp>` (또는 `<out.hwpx>`) |
| HWP/HWPX → ODT | `hwpConverter.bat <in.hwp> <out.odt>` |
| HWP/HWPX → MD | `hwpConverter.bat <in.hwp> <out.md>` |
| MD → HWP/HWPX | `hwpConverter.bat <in.md> <out.hwp>` (또는 `<out.hwpx>`) |
| 배포용(DRM) | `hwpConverter.bat --dist <in.hwp> <out.hwp> <password> [--no-copy] [--no-print]` |
| 폴더 배치 | `hwpConverter.bat <inputDir> <outputDir> [--to-hwpx\|--to-hwp\|--to-md\|--to-odt]` |
| 파일 지정 | `hwpConverter.bat <f1> <f2> ... <outputDir> --to-hwpx\|--to-hwp\|--to-md\|--to-odt` |
| 배포용 다건변경 (DRM) | `hwpConverter.bat --dist <f1> <f2> ... <outputDir> <password> [--no-copy] [--no-print] [--out-hwpx\|--out-hwp]` |

CLI 는 입력 확장자가 없거나 실제 포맷과 다를 때 파일 시그니처(`FormatSniffer`)로 자동 판별합니다.
방향은 입력·출력 확장자(`.hwp` / `.hwpx` / `.odt` / `.md`)로 결정됩니다.
옵션 표기는 대시 2개(`--to-odt` 등)를 사용하세요 — 대시 1개(`-to-odt`)는 자동 보정됩니다.

> 참고: Markdown ↔ ODT 직접 변환(`md→odt`, `odt→md`)은 현재 비활성화되어 있습니다.
> ODT 는 HWP/HWPX 를 거쳐, MD 는 HWP/HWPX 를 거쳐 변환하세요.

경로·암호에 공백이나 셸 특수문자(`< > & |`) 가 있으면 반드시 따옴표로 감싸세요.


## 라이브러리로 사용하기

외부 Java 프로젝트에서 변환 메서드를 직접 호출할 수 있습니다.

### 1. 의존성 추가

**Maven / Gradle 없이 (release 배포본 사용)**

`hwpConverter-2606.zip` 압축을 풀어 `hwpConverter.jar` + `lib/*.jar` 를 classpath 에 추가.

```bash
# 컴파일
javac -cp "hwpConverter.jar;lib/*" MyApp.java

# 실행 (Windows: ';', Linux/macOS: ':')
java  -cp "hwpConverter.jar;lib/*;." MyApp
java  -cp "hwpConverter.jar:lib/*:." MyApp
```

**Maven (로컬 설치)**

```bash
mvn install:install-file -Dfile=hwpConverter.jar \
    -DgroupId=kr.n.nframe -DartifactId=hwpConverter \
    -Dversion=2606 -Dpackaging=jar
```

```xml
<dependency>
    <groupId>kr.n.nframe</groupId>
    <artifactId>hwpConverter</artifactId>
    <version>2606</version>
</dependency>

<!-- 외부 의존성 (lib/ 에 포함된 것들) -->
<dependency><groupId>org.apache.poi</groupId><artifactId>poi</artifactId><version>5.2.5</version></dependency>
<dependency><groupId>kr.dogfoot</groupId><artifactId>hwplib</artifactId><version>1.1.10</version></dependency>
<dependency><groupId>kr.dogfoot</groupId><artifactId>hwpxlib</artifactId><version>1.0.9</version></dependency>
<dependency><groupId>kr.dogfoot</groupId><artifactId>hwp2hwpx</artifactId><version>1.0.0</version></dependency>
```

**Gradle**

```groovy
dependencies {
    implementation files('libs/hwpConverter.jar')
    implementation fileTree(dir: 'libs/lib', include: ['*.jar'])
}
```

### 2. 기본 변환 API

```java
import kr.n.nframe.HwpConverter;

public class Example {
    public static void main(String[] args) throws Exception {
        HwpConverter converter = new HwpConverter();

        // HWPX → HWP (직접 바이너리 변환)
        converter.convertHwpxToHwp("input.hwpx", "output.hwp");

        // HWP → HWPX (hwp2hwpx 기반)
        converter.convertHwpToHwpx("input.hwp", "output.hwpx");

        // HWP → 배포용(DRM) HWP — AES-128 암호화 + 복사/인쇄 방지
        String password = "mySecret";
        boolean noCopy  = true;   // 복사 방지 활성화
        boolean noPrint = false;  // 인쇄 허용
        converter.makeHwpDist("input.hwp", "output_dist.hwp",
                              password, noCopy, noPrint);
    }
}
```

### 3. 배치 변환 API

```java
import kr.n.nframe.HwpConverter;
import java.io.File;
import java.util.Arrays;
import java.util.List;

HwpConverter c = new HwpConverter();

// 폴더 전체 변환 (재귀 아님, 해당 디렉터리의 .hwpx 만)
c.batchHwpxToHwp("in_dir", "out_dir");
c.batchHwpToHwpx("in_dir", "out_dir");

// 배포용 폴더 배치
c.batchDist("in_dir", "out_dir", "pw", true, false, null);
//  ↑ forceOutExt: null=입력 확장자 보존, ".hwp" 또는 ".hwpx" 강제 지정 가능

// 개별 파일 N 개 지정 (List<File>)
List<File> files = Arrays.asList(
    new File("a.hwpx"),
    new File("b.hwpx"),
    new File("c.hwpx"));
c.batchFiles(files, "out_dir", ".hwp");

// 개별 파일 배포용 배치
c.batchDistFiles(files, "out_dir", "pw", true, false, null);
```

### 4. 예외 처리

```java
try {
    converter.convertHwpxToHwp(src, dst);
} catch (java.io.IOException e) {
    // 파일 입출력 오류, ZIP 파싱 실패, XXE/zip bomb 차단 등
    System.err.println("변환 실패: " + e.getMessage());
} catch (IllegalArgumentException e) {
    // 입력 == 출력 자기 덮어쓰기 / payload 상한 초과 등 보안 차단
    System.err.println("입력 오류: " + e.getMessage());
} catch (IllegalStateException e) {
    // HWPX 중첩 깊이 상한 초과, 문단 텍스트 상한 초과 등
    System.err.println("악성 입력 의심: " + e.getMessage());
}

// HWP → HWPX 는 hwp2hwpx 내부 예외도 발생 가능 (Exception 포괄)
try {
    converter.convertHwpToHwpx(src, dst);
} catch (IllegalStateException e) {
    // 배포용(DRM) HWP 를 입력한 경우 — 복호화 불가, 원본 HWP 필요
    System.err.println(e.getMessage());
} catch (Exception e) {
    // 기타 변환 오류
    e.printStackTrace();
}
```

### 5. 전체 메서드 시그니처

```java
package kr.n.nframe;

public class HwpConverter {
    // 단건
    public void convertHwpxToHwp(String hwpx, String hwp) throws IOException;
    public void convertHwpToHwpx(String hwp, String hwpx) throws Exception;
    public void makeHwpDist(String in, String out, String password,
                            boolean noCopy, boolean noPrint) throws Exception;

    // 배치 (폴더)
    public BatchResult batchHwpxToHwp(String inDir, String outDir) throws IOException;
    public BatchResult batchHwpToHwpx(String inDir, String outDir) throws IOException;
    public BatchResult batchDist(String inDir, String outDir, String password,
                                 boolean noCopy, boolean noPrint,
                                 String forceOutExt /* null 허용 */) throws IOException;

    // 배치 (파일 리스트)
    public BatchResult batchFiles(List<File> inputs, String outDir,
                                  String toExt /* ".hwp" 또는 ".hwpx" */) throws IOException;
    public BatchResult batchDistFiles(List<File> inputs, String outDir,
                                      String password, boolean noCopy, boolean noPrint,
                                      String forceOutExt) throws IOException;
}
```

`BatchResult` 는 public static 내부 클래스로 다음 필드·getter 를 제공합니다 (v13.11 부터):

```java
HwpConverter.BatchResult r = converter.batchHwpxToHwp("in_dir", "out_dir");
System.out.println("성공: " + r.ok + " / 실패: " + r.fail);
if (r.fail > 0) {
    for (String msg : r.failDetails) {
        System.out.println("  실패: " + msg);
    }
}
```

## ODT · Markdown 변환 API (신규)

`HwpConverter` 의 HWP↔HWPX 변환에 더해, 이번 버전은 **ODT 양방향 변환**과
**Markdown 변환**을 추가했습니다. CLI 에서는 위 [사용 예](#사용-예) 표대로 확장자만
지정하면 자동 라우팅되며, 라이브러리에서는 아래 진입점을 직접 호출할 수 있습니다.

### 6. ODT 변환 API

**`OdfConverter`** — HWP·HWPX·ODT 통합 파사드. 입출력 확장자로 방향을 자동 결정합니다.

```java
import kr.n.nframe.newfeature.OdfConverter;

OdfConverter odf = new OdfConverter();

// 확장자로 방향 자동 결정 (odt→hwp, odt→hwpx, hwp→odt, hwpx→odt)
odf.convert("in.odt",  "out.hwp");
odf.convert("in.hwp",  "out.odt");

// 방향 명시 호출
odf.convertOdtToHwp ("in.odt",  "out.hwp");
odf.convertOdtToHwpx("in.odt",  "out.hwpx");
odf.convertHwpToOdt ("in.hwp",  "out.odt");
odf.convertHwpxToOdt("in.hwpx", "out.odt");

// 폴더 배치
odf.batchAnyToOdt("inDir", "outDir");             // 폴더 내 .hwp/.hwpx → .odt
odf.batchOdtToHwp("inDir", "outDir", ".hwp");      // 폴더 내 .odt → .hwp/.hwpx
```

**`OdtDirectConverter`** — ODT → HWP/HWPX **직접 변환기**(MD 중간파일 미경유).
자체 빌더로 만든 뒤 표준 hwplib writer 로 한 번 더 통과시켜 한/글 호환을 보장하며,
임시 파일은 OS temp 에만 생성·즉시 삭제됩니다.

```java
import kr.n.nframe.newfeature.OdtDirectConverter;

OdtDirectConverter odt = new OdtDirectConverter();
odt.convert("in.odt", "out.hwp");                  // 출력 확장자(.hwp/.hwpx)로 결정
odt.convertOdtToHwp ("in.odt", "out.hwp");
odt.convertOdtToHwpx("in.odt", "out.hwpx");
odt.batchOdtTo("inDir", "outDir", "hwp");          // "hwp" 또는 "hwpx"
```

**저수준 변환기** — HWP→ODT / HWPX→ODT 단건 변환(정적 메서드, `Path` 기반):

```java
import java.nio.file.Paths;
import kr.n.nframe.newfeature.hwp2odt.HwpToOdtConverter;
import kr.n.nframe.newfeature.hwp2odt.Options;
import kr.n.nframe.newfeature.hwp2odt.Result;

// HWP → ODT
Result r = HwpToOdtConverter.convertOne(
        Paths.get("in.hwp"), Paths.get("out.odt"),
        Options.defaults());                              // Options.verbose 로 로깅 토글

// HWPX → ODT 는 hwpx2odt 패키지의 동일 형태 메서드 사용
kr.n.nframe.newfeature.hwpx2odt.HwpxToOdtConverter.convertOne(
        Paths.get("in.hwpx"), Paths.get("out.odt"),
        kr.n.nframe.newfeature.hwpx2odt.Options.defaults());
```

> `hwp2odt` 와 `hwpx2odt` 는 각각 자신의 `Options` · `Result` 타입을 가집니다.
> 한 파일에서 둘 다 쓸 때는 한쪽만 `import` 하고 다른 쪽은 위처럼 완전수식명으로 호출하세요.

### 7. Markdown 변환 API

**`HwpMdConverter`** — HWP/HWPX ↔ Markdown 변환 파사드.

```java
import kr.n.nframe.HwpMdConverter;

HwpMdConverter md = new HwpMdConverter();

// 추출: HWP/HWPX → Markdown
md.convertHwpToMarkdown ("in.hwp",  "out.md");
md.convertHwpxToMarkdown("in.hwpx", "out.md");

// 생성: Markdown → HWP/HWPX (구조 변환)
md.convertMarkdownToHwp ("in.md", "out.hwp");
md.convertMarkdownToHwpx("in.md", "out.hwpx");

// 사이드카(.md 동시 출력) · 원본 임베드 토글 (기본값 정책은 클래스 참조)
HwpMdConverter.setSidecarEnabled(false);
HwpMdConverter.setEmbedOriginEnabled(false);
```

**`MdStructureConverter`** — MD → HWP/HWPX 구조 변환 오케스트레이터.
내부적으로 `mdlib` 의 **4개 변환 전략**을 상황에 따라 조합·폴백합니다:

| 전략 클래스 | 역할 |
|---|---|
| `MdToHwpRich`     | heading 폰트 + 풍부한 템플릿 기반 MD → HWP (기본 경로) |
| `MdToHwpDirect`   | Rich 실패 시 폴백하는 경량 MD → HWP |
| `MdToHwpxNative`  | 네이티브 HWPX 빌더 (라운드트립 실패 시 폴백) |
| `MdToHwpxImage`   | `data:` URI 이미지를 HWPX picture control 로 주입 |

```java
import kr.n.nframe.mdlib.MdStructureConverter;

MdStructureConverter sc = new MdStructureConverter();
sc.convertMarkdownToHwpStructure ("in.md", "out.hwp");    // Rich → (실패 시) Direct 폴백
sc.convertMarkdownToHwpxStructure("in.md", "out.hwpx");   // MD→HWP→HWPX 라운드트립 → Native 폴백
```

### 8. 배치 러너 · 포맷 판별

**`FormatSniffer`** — 확장자와 무관하게 파일 시그니처로 실제 포맷을 판별합니다.
CLI 의 자동 라우팅이 이 클래스를 사용합니다.

```java
import kr.n.nframe.newfeature.FormatSniffer;
import kr.n.nframe.newfeature.FormatSniffer.Format;
import java.io.File;

Format fmt = FormatSniffer.sniff(new File("mystery.bin"));  // HWP5 / HWPX / ODT / MD / UNKNOWN
String ext = FormatSniffer.canonicalExt(fmt);               // 정규 확장자 문자열
```

**`BatchRunner`** — 배치 변환 실행기. `OdtConversionRouter` 가 ODT 대상 라우팅을 담당합니다.

```java
import kr.n.nframe.newfeature.batch.BatchRunner;

int exitCode = BatchRunner.run(args);   // CLI 인자 그대로 전달, 종료코드 반환
```

### 9. 승격된 CLI 진입점 (`HwpConverterCli`)

`build/MANIFEST.MF` 의 `Main-Class` 는 `kr.n.nframe.newfeature.HwpConverterCli` 입니다.
이 CLI 는 기존 `HwpConverter` 의 모든 기능(hwp↔hwpx, →md, md→, `--dist`, 다건 파일)을
그대로 위임하면서, **ODT 라우팅**과 **시그니처 자동 판별**을 앞단에 추가한 상위 래퍼입니다.

```bash
# jar 실행 (Main-Class = HwpConverterCli)
java -cp "build/hwpConverter.jar:lib/*" kr.n.nframe.newfeature.HwpConverterCli <in> <out>

# 라이브러리에서 직접 호출도 가능
# kr.n.nframe.newfeature.HwpConverterCli.main(new String[]{ "in.odt", "out.hwp" });
```

## 프로젝트 구조

```
src/kr/n/nframe/
├── HwpConverter.java              엔트리포인트(HWP↔HWPX) + CLI + 배치
├── HwpMdConverter.java            HWP/HWPX ↔ Markdown 파사드
├── hwplib/
│   ├── binary/    (RecordWriter, ZlibCompressor, HwpBinaryWriter)
│   ├── constants/ (HwpTagId, CtrlId, HwpxNs)
│   ├── model/     (HwpDocument 등 순수 값 객체)
│   ├── reader/    (HwpxReader, HeaderParser, SectionParser,
│   │              XmlHelper, TocDotLeaderMapper)
│   └── writer/    (HwpWriter, SectionWriter, DocInfoWriter,
│                  DistributionWriter, HwpxPostProcessor, HwpxXmlRewriter)
├── mdlib/                         Markdown 변환 전략 4종 + 파서/주입기
│   (MdStructureConverter, MdToHwpRich, MdToHwpDirect,
│    MdToHwpxNative, MdToHwpxImage, MdRichParser, MdImageInjector ...)
└── newfeature/                    ODT 변환 · CLI · 포맷 판별
    ├── HwpConverterCli.java       승격된 CLI 진입점(MANIFEST Main-Class)
    ├── OdfConverter.java          HWP/HWPX/ODT 통합 파사드
    ├── OdtDirectConverter.java    ODT → HWP/HWPX 직접 변환(MD 미경유)
    ├── FormatSniffer.java         시그니처 기반 포맷 자동 판별
    ├── hwp2odt/     (HwpToOdtConverter, HwpDocumentTraverser, *Mapper)
    ├── hwpx2odt/    (HwpxToOdtConverter, HwpxTraverser, *Mapper)
    ├── odtcommon/   (OdtWriter, OdtZipPackager, StyleRegistry ...)
    └── batch/       (BatchRunner, BatchReport, OdtConversionRouter)
```

## 라이선스
Apache License 2.0 — [LICENSE](LICENSE) 참조.

Copyright © 2026 NSoftware INC.

외부 의존 라이브러리:
- Apache POI (Apache-2.0)
- `kr.dogfoot:hwplib` / `hwpxlib` / `hwp2hwpx` (Apache-2.0 — [github.com/neolord0](https://github.com/neolord0))

## Credits
NSoftware INC. 제공

- **작성** : citadel
- **QA** : 유영진 대리
