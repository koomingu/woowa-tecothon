# 찜꽁 Slack Bot 구현 단계

스펙 문서(`zzimkkong_slack_bot_spec.md`) 기반 구현 순서.  
패키지 루트: `decoton.zzimkkong` / Spring Boot 4.0.6 / Java 21

---

## Step 1: build.gradle 의존성 추가

**파일:** `build.gradle`

**변경 사항:**
- `spring-boot-starter-webmvc` → `spring-boot-starter-webflux` 교체 (WebClient 사용)
- Slack Bolt Socket Mode 추가
- 테스트 의존성도 webflux-test로 교체

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'com.slack.api:bolt-socket-mode:1.40.0'
    implementation 'com.slack.api:slack-api-client:1.40.0'
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testCompileOnly 'org.projectlombok:lombok'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    testAnnotationProcessor 'org.projectlombok:lombok'
}
```

---

## Step 2: application.yaml 환경변수 설정

**파일:** `src/main/resources/application.yaml`

```yaml
slack:
  bot-token: ${SLACK_BOT_TOKEN}
  app-token: ${SLACK_APP_TOKEN}

anthropic:
  api-key: ${ANTHROPIC_API_KEY}
  model: claude-sonnet-4-20250514

zzimkkong:
  base-url: ${ZZIMKKONG_BASE_URL:https://k8s.zzimkkong.com/api}
  sharing-map-id: ${ZZIMKKONG_SHARING_MAP_ID}
```

**핵심:** `app-token`은 Socket Mode용 (xapp-으로 시작하는 토큰).

---

## Step 3: ParsedIntent DTO

**파일:** `src/main/java/decoton/zzimkkong/claude/ParsedIntent.java`

Claude API가 반환하는 JSON을 역직렬화하는 record.

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParsedIntent(
    String intent,
    Map<String, String> params,
    List<String> missing,
    @JsonProperty("raw_message") String rawMessage
) {}
```

---

## Step 4: ClaudeParserService

**파일:** `src/main/java/decoton/zzimkkong/claude/ClaudeParserService.java`

**구현 메서드 3개:**

| 메서드 | 역할 |
|--------|------|
| `parse(String userMessage)` | Claude API 호출 → ParsedIntent 반환 |
| `parse(String userMessage, String slackName)` | name/password fallback 처리 후 반환 |
| `parseWithContext(ParsedIntent pending, String supplement, String slackName)` | 다중 턴: pending 파라미터에 보완 메시지 병합 |

**핵심 구현 포인트:**
- System Prompt 내 `{today}`, `{max_date}` → KST 기준 날짜로 치환
- Claude API 요청: `POST https://api.anthropic.com/v1/messages`
  - 헤더: `x-api-key`, `anthropic-version: 2023-06-01`
  - 응답: `content[0].text` 추출 → Jackson으로 ParsedIntent 역직렬화
- `parse(userMessage, slackName)`: Claude가 name을 null로 파싱한 경우만 Slack 닉네임 사용, password가 null이면 4자리 랜덤 생성
- `parseWithContext`: pending의 params에 새 메시지 파싱 결과를 덮어쓰기 병합, missing 재계산

---

## Step 5: ZzimkkongClient

**파일:** `src/main/java/decoton/zzimkkong/zzimkkong/ZzimkkongClient.java`

**구현 메서드:**

| 메서드 | HTTP | 엔드포인트 |
|--------|------|-----------|
| `getMap(sharingMapId)` | GET | `/guests/maps?sharingMapId=` |
| `getSpaces(mapId)` | GET | `/guests/maps/{mapId}/spaces` |
| `getSpaceAvailability(mapId, startDatetime, endDatetime)` | GET | `/guests/maps/{mapId}/spaces/availability` |
| `getSpaceReservations(mapId, spaceId, date)` | GET | `/guests/maps/{mapId}/spaces/{spaceId}/reservations` |
| `getAllSpaceReservations(mapId, date)` | GET | `/guests/maps/{mapId}/spaces/reservations` |
| `createReservation(mapId, spaceId, startDatetime, endDatetime, password, name, description)` | POST | `/guests/maps/{mapId}/spaces/{spaceId}/reservations` |
| `getReservationDetail(mapId, spaceId, reservationId, password)` | POST | `/guests/maps/{mapId}/spaces/{spaceId}/reservations/{reservationId}` |

**핵심 구현 포인트:**
- 모든 메서드: `WebClientResponseException` → `{"error": ..., "status": ...}` Map으로 반환 (예외 throw 없음)
- static 헬퍼: `toIso(String date, String time)` → `date + "T" + time + ":00+09:00"`
- `createReservation` 요청 바디: `startDatetime`, `endDatetime`은 ISO 8601 KST 형식

---

## Step 6: PendingSessionStore

**파일:** `src/main/java/decoton/zzimkkong/session/PendingSessionStore.java`

다중 턴 대화에서 누락 파라미터를 보완받을 때까지 파싱 결과를 임시 저장.

```java
@Component
public class PendingSessionStore {
    private final Map<String, ParsedIntent> store = new ConcurrentHashMap<>();

    public void save(String sessionKey, ParsedIntent intent) { ... }
    public ParsedIntent get(String sessionKey) { ... }
    public void clear(String sessionKey) { ... }
}
```

**세션 키:** `channelId + "_" + userId` (단체 채팅방 동시 예약 충돌 방지)

---

## Step 7: IntentHandler 인터페이스 + 라우터

**파일 2개:**

**`IntentHandler.java`** (`decoton.zzimkkong.handler`)
```java
public interface IntentHandler {
    String supportedIntent();
    String handle(ParsedIntent parsed, String sessionKey);
}
```

**`IntentHandlerRouter.java`** (`decoton.zzimkkong.handler`)
- 생성자에서 `List<IntentHandler>` 주입 → `Map<String, IntentHandler>` 변환
- `route(ParsedIntent, sessionKey)`: intent로 핸들러 조회, 없으면 `unknown` 핸들러로 위임

---

## Step 8: Intent Handler 구현체 5개

**디렉터리:** `src/main/java/decoton/zzimkkong/handler/impl/`

### 8-1. CreateReservationHandler
- `supportedIntent()` → `"create_reservation"`
- 처리 흐름:
  1. `missing` 목록 확인 → 비어있지 않으면 sessionStore에 저장 후 첫 번째 누락 파라미터 안내 메시지 반환
  2. `date_out_of_range` 확인 → 날짜 범위 오류 메시지 반환
  3. `duration_exceeded` 확인 → 시간 초과 오류 메시지 반환
  4. `getMap(sharingMapId)` → mapId 추출
  5. `getSpaces(mapId)` → space_name으로 spaceId 검색 (부분 일치, 소문자 비교)
  6. spaceId 없으면 공간 없음 안내
  7. `createReservation(...)` 호출 → 성공 시 예약 번호 + 비밀번호 응답, 실패 시 에러 메시지

### 8-2. GetReservationsHandler
- `supportedIntent()` → `"get_reservations"`
- space_name이 있으면 `getSpaceReservations`, 없으면 `getAllSpaceReservations` 호출
- 예약 목록을 사람이 읽기 좋은 형태로 포맷해 반환

### 8-3. GetAvailabilityHandler
- `supportedIntent()` → `"get_availability"`
- date + start_time + end_time → ISO 8601 변환 후 `getSpaceAvailability` 호출
- 가용 공간 목록 반환

### 8-4. GetSpacesHandler
- `supportedIntent()` → `"get_spaces"`
- `getMap(sharingMapId)` → `getSpaces(mapId)` → 공간 목록 포맷해 반환

### 8-5. UnknownIntentHandler
- `supportedIntent()` → `"unknown"`
- 고정 안내 메시지 반환 (예: 지원 기능 목록)

---

## Step 9: AppConfig

**파일:** `src/main/java/decoton/zzimkkong/config/AppConfig.java`

```java
@Configuration
public class AppConfig {

    @Bean
    public WebClient zzimkkongWebClient(@Value("${zzimkkong.base-url}") String baseUrl) {
        return WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }
}
```

**주의:** `ClaudeParserService`용 WebClient는 baseUrl 없이 별도 생성하거나, `WebClient.create()`로 직접 사용.

---

## Step 10: SlackEventHandler

**파일:** `src/main/java/decoton/zzimkkong/slack/SlackEventHandler.java`

Socket Mode 기반 `app_mention` 이벤트 처리.

```java
@Component
public class SlackEventHandler {

    // ClaudeParserService, IntentHandlerRouter, PendingSessionStore 주입

    @Bean
    public App slackApp(@Value("${slack.bot-token}") String botToken) {
        App app = new App(AppConfig.builder().singleTeamBotToken(botToken).build());

        app.event(AppMentionEvent.class, (payload, ctx) -> {
            // 1. channelId, userId, userMessage 추출
            // 2. ctx.client().usersInfo() → displayName or realName
            // 3. sessionKey = channelId + "_" + userId
            // 4. pending 확인 → parseWithContext or parse
            // 5. sessionStore.clear(sessionKey)
            // 6. router.route(parsed, sessionKey) → reply
            // 7. ctx.say(reply)
            return ctx.ack();
        });
        return app;
    }
}
```

---

## Step 11: Socket Mode 앱 실행 설정

**파일:** `src/main/java/decoton/zzimkkong/config/AppConfig.java` (또는 별도 Runner)

Socket Mode는 WebSocket으로 Slack 서버에 연결하므로 별도 실행 필요.

```java
@Bean
public CommandLineRunner startSocketMode(App slackApp,
        @Value("${slack.app-token}") String appToken) {
    return args -> {
        SocketModeApp socketModeApp = new SocketModeApp(appToken, slackApp);
        socketModeApp.startAsync();
    };
}
```

**의존성 추가 확인:** `bolt-socket-mode`가 `SocketModeApp` 포함.

---

## 에러 응답 매핑 (공통)

| 상황 | Slack 응답 |
|------|-----------|
| Claude JSON 파싱 오류 | `"요청 파싱에 실패했습니다. 다시 시도해주세요."` |
| 공간 이름 매핑 실패 | `"'{space_name}' 공간을 찾을 수 없습니다."` |
| `date_out_of_range` | `"예약은 오늘부터 {max_date}까지만 가능합니다."` |
| `duration_exceeded` | `"예약은 최대 1시간까지 가능합니다."` |
| 찜꽁 4xx | 응답 error 메시지 그대로 전달 |
| 찜꽁 5xx | `"서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요."` |
| 네트워크 타임아웃 | `"요청 시간이 초과됐습니다."` |

---

## 구현 완료 후 검증

```bash
# 1. 컴파일 확인
./gradlew build

# 2. 환경변수 세팅 후 실행
export SLACK_BOT_TOKEN=xoxb-...
export SLACK_APP_TOKEN=xapp-...
export ANTHROPIC_API_KEY=sk-ant-...
export ZZIMKKONG_SHARING_MAP_ID=<sharingMapId>
./gradlew bootRun
```

**시나리오 테스트:**
1. 정상 예약: `@봇 내일 오후 2시~3시 회의실 A 예약해줘. 이름 김희영, 비밀번호 1234, 팀 회의`
2. 파라미터 누락 (다중 턴): `@봇 내일 2시~3시 회의실 A 예약` → 비밀번호 요청 → `1234` 입력
3. 조회: `@봇 오늘 회의실 A 예약 현황 알려줘`
4. unknown: `@봇 날씨 어때?`
5. 공간 불일치: `@봇 회의실Z 예약해줘`
