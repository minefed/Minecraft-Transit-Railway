# 공개 교통 API 설정 안내

Minecraft 1.20.4 / Fabric / MTR 4.0.5에서 기존 공개 설정으로 선별한 교통 데이터를
`minefed-api`에 제공한다. BlueMap 웹 오버레이도 같은 공개 API를 조회한다.
BlueMap이 설치되지 않았거나 비활성화된 상태에서도 데이터를 담당하는 Core
시뮬레이터의 작업 큐에서 수집을 수행한다. Minecraft 클라이언트 클래스나 새로운
런타임 의존성은 필요하지 않다.

## 설정

[API 명세 및 배포 안내](https://github.com/minefed/minefed-api/blob/main/docs/TRANSIT_API.md)에
따라 API의 선택적 교통 데이터 HTTP 리스너를 구성한다.
이후 Minecraft 서버의 `config/mtr/transit-api.json`을 다음과 같이 설정한다.

```json
{
  "apiBaseUrl": "https://api.example.com/v1/transit/minefed",
  "publicApiBaseUrl": "https://api.example.com/v1/transit/minefed",
  "tokenEnvironment": "MINEFED_TRANSIT_TOKEN"
}
```

예시의 호스트 이름은 실제 주소로 바꿔야 한다. `apiBaseUrl`은 서버 간 전송에
사용하는 주소이고, `publicApiBaseUrl`은 브라우저에서 접근하는 공개 주소다.
`publicApiBaseUrl`을 비워 두면 `apiBaseUrl`을 사용한다. 두 주소의
`/v1/transit/{dataset}` 경로는 같아야 한다.
수신용 내부 리스너는 `http://127.0.0.1:8088/v1/transit/minefed`처럼 구성하고,
공개 주소에는 별도의 HTTPS 호스트를 사용할 수 있다. HTTP는 명시적인 루프백
주소에서만 허용한다. URL에 인증 정보, 쿼리 문자열, 프래그먼트 또는 상위 경로로
이동하는 경로를 포함하면 거부하며, 리다이렉트는 따라가지 않는다.

API의 `TRANSIT_SERVER_TOKEN`과 같은 값을 Minecraft 프로세스의 환경 변수에
설정한다. 이 환경 변수의 이름은 `tokenEnvironment`로 지정한다. 토큰은 공백과
쉼표를 제외한 출력 가능한 ASCII 문자로 이루어져야 하며, 길이는 32~4096자다.
토큰을 JSON, 클라이언트 팩, 공개 URL, BlueMap 파일 또는 Git에 저장하지 않는다.
웹 루트에는 공개 API 기본 주소와 BlueMap 지도/MTR 월드의 대응 정보만 기록한다.

자동 생성되는 기본 설정은 URL이 비어 있어 API 전송이 비활성화된다. 인증 정보가
없거나 유효하지 않은 경우에도 전송하지 않는다. API 설정이나 환경 변수를
변경한 뒤에는 서버를 재시작해야 한다. `mtr.bluemapLive.enabled`는 기존과 같이
BlueMap 표시 연동만 제어하며, API 전송을 끄지 않는다. 기존 공개 설정 파일은
다음 위치를 그대로 사용한다.

- `config/mtr/bluemap-public-routes.json`: 관리자가 지정하는 공개 대상 설정이다.
  2초마다 확인하며, 기존 노선·그룹·운행 계통·운영사·간접환승 설정을 유지한다.
  설정이 유효하지 않으면 아무 노선도 공개하지 않는다.
- `config/mtr/bluemap-routes.json`: 자동 갱신되는 비공개 노선·역 목록이다.
  API로 전송하거나 공개 웹 디렉터리에 기록하지 않는다.
- `config/mtr/bluemap-vehicles.json`: 선택적으로 사용하는 공개 차량 표시 이름과
  규격 목록이다. `name`, `length`, `width`만 공개한다.

MTR의 `hidden` 플래그와 공개 대상 지정은 별개다. 기존과 같이 숨겨진 노선도
공개 설정에서 명시적으로 선택할 수 있다.

## 데이터 흐름과 갱신 주기

1. 공개 차량은 500ms마다, 도착 정보는 2초마다 수집한다. 선로·노선·역 정보와
   선택적 차량 메타데이터는 30초마다 갱신한다.
2. 별도의 HTTP 전송 작업이 비공개 Bearer 토큰으로
   `PUT {apiBaseUrl}/snapshot`을 호출한다. 대기 중인 전체 스냅샷 1개와 진행 중인
   요청 1개만 유지한다. 요청이 느리거나 실패해도 시뮬레이션과 공개 설정 확인을
   막지 않는다. 응답 헤더와 본문을 모두 받기까지 최대 2초를 허용하며,
   실패한 요청은 최소 500ms 뒤에 재시도한다. 선로·노선·역 정보는 해당 정보를
   새로 수집했거나 복구를 위해 전체 스냅샷을 보내야 할 때 전송한다.
3. API는 정해진 공개 데이터 스키마를 검증하고 인증 없이 조회할 수 있는 GET
   경로를 제공한다. 경로는 `/worlds`, `/worlds/{id}/vehicles`,
   `/worlds/{id}/infrastructure`, `/catalog`다. 경로에 쓰이는 월드 ID는 MTR 차원
   문자열에서 생성한 고정 UUID다. `/worlds` 응답에는 `minecraft/overworld`와 같은
   원래의 `worldId`도 포함된다.
4. BlueMap은 로컬에서 `mtr-live/index.json`(초기 연결 설정, 스키마 2)과 웹
   스크립트만 읽는다. 모든 교통 데이터는 설정한 API에서 가져온다. 조회 간격
   500ms, 보간 버퍼 650ms, 정적 데이터 갱신 간격 30초를 유지한다. 브라우저는
   인증 정보를 보내지 않으며, API의 CORS 헤더를 통해 다른 출처에서도 조회한다.

원본 데이터 수집 시각, 도착 정보 수집 시각, 공개 정책 버전을 그대로 유지한다.
API는 6초를 기준으로 데이터의 유효성을 판단하고 원본 수집 후 경과 시간을
응답에 포함한다. 브라우저는 이 경과 시간과 요청 지연을 함께 반영해 실시간
데이터인지 판단한다. Minecraft 서버와 API 서버의 시스템 시각을 동기화해야 하며,
API는 원본 수집 시각이 API 서버 시각보다 최대 5초 앞서는 경우까지 허용한다.
선택적 메타데이터 조회가 실패해도 정상 갱신 중인 월드의 운행 표시는 유지한다.

공개 설정이 바뀌면 이전 설정으로 만들어진 대기 데이터를 버리고, API의 이전
공개 버전 데이터를 한 번에 교체한다. API가 재시작되거나 전송을 거부하면 전체
스냅샷을 다시 보낸다. MTR 종료 시에는 빈 월드 목록 전송을 시도하며, 비정상
종료 시에도 6초 유효기간이 지나면 데이터를 만료 처리한다. 이전 로컬 JSON으로
되돌아가서 조회하지 않는다. 전환 시 기존 월드 파일과 차량 목록 파일을 비워,
브라우저에 캐시된 구버전 스크립트가 이전 공개 데이터를 계속 사용하지 못하게 한다.

## 검증과 적용 순서

Java 테스트는 인증 정보·URL 검증, 최신 스냅샷만 전송하는 동작, API 캐시 유실 후
복구, 리다이렉트 거부, 응답 본문 지연, 실제 Core 공개 스냅샷 생성을 확인한다.
`TransitContractFixtureTest`가 생성한 `fabric/build/transit-contract.json`을
API 테스트에서 `TRANSIT_CONTRACT_FIXTURE`로 지정해 실제 MTR 출력과 공개 API
응답의 계약을 검증한다. 기존 공개 설정·형상·도착 정보·웹/Three.js 회귀 테스트도
계속 적용한다.

먼저 API 리스너와 공개 HTTPS 경로를 배포한 뒤, MTR 설정을 활성화하고 MTR 서버
JAR를 교체한다. 기존 공개 노선 설정은 보존해야 한다. 이번 전환에는 새 Java
전송 코드와 스키마 2 웹 스크립트가 모두 필요하므로 웹 스크립트만 교체해서는
동작하지 않는다. 코드 빌드와 로컬 테스트 통과는 운영 배포나 실제 게임 환경
검증을 의미하지 않는다.
