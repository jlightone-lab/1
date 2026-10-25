# 케케우 뉴스 앱

[kekewo.net](https://kekewo.net) 글을 휴대폰 앱처럼 볼 수 있는 웹 앱(PWA)입니다.
글은 WordPress.com 공개 API(`public-api.wordpress.com`, 사이트 ID `219683211`)에서 바로 불러오므로
사이트에 글을 올리면 앱에도 자동으로 나옵니다. 별도 서버가 필요 없습니다.

## 기능
- 최신 글 / 카테고리별(이슈·시사·연예·IT·경제·Tip·영화·드라마·스포츠·게임·부동산) 목록, 아래로 내리면 계속 불러오기
- 검색
- 앱 안에서 기사 읽기, 기사 속 다른 케케우 글 링크도 앱 안에서 열기
- 공유하기(휴대폰 공유창 / 링크 복사), 사이트에서 보기
- 홈 화면에 설치(안드로이드: `앱 설치` 버튼, 아이폰: Safari 공유 → 홈 화면에 추가)
- 오프라인일 때 마지막으로 본 글 보여 주기, 다크 모드

## 실행해 보기
```sh
cd kekewo-app
python3 -m http.server 8000
# 브라우저에서 http://localhost:8000
```

## 배포
정적 파일뿐이라 GitHub Pages, Netlify, 또는 사이트의 하위 경로(예: `kekewo.net/app/`) 어디에 올려도 됩니다.
설치(PWA)는 HTTPS 주소에서만 됩니다.

## 구성
| 파일 | 내용 |
|---|---|
| `index.html` | 화면 틀 |
| `app.js` | 글 불러오기, 화면 전환, 검색, 공유, 설치 |
| `style.css` | 디자인 (밝은/어두운 모드) |
| `sw.js` | 오프라인 저장 (서비스 워커) |
| `manifest.webmanifest`, `icons/` | 앱 이름·아이콘 |

카테고리를 추가·변경하면 `app.js`의 `CATEGORIES` 목록도 같이 고쳐 주세요.
