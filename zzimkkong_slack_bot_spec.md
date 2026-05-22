# Slack Bot + Claude API 연동 찜꽁 예약 구현 명세

## 개요

Slack Bot이 사용자 자연어 메시지를 수신 → Claude API로 파싱 → 찜꽁 REST API에 직접 HTTP 요청하는 구조.  
Spring Boot 단일 애플리케이션으로 처리한다.

```
Slack User
   │  자연어 메시지
   ▼
Slack Bot (Spring Boot)
   │  파싱 요청 (system prompt + 사용자 메시지)
   ▼
Claude API  ──→  구조화된 JSON (intent + params)
   │
   ▼
찜꽁 REST API  ──→  응답
   │
   ▼
Slack User  ──→  결과 메시지
```

---

## 기술 스택

| 항목 | 선택 |
|------|------|
| 언어 / 프레임워크 | Java 17 + Spring Boot 3.x |
| Slack 연동 | Slack Bolt for Java (`com.slack.api:bolt-socket-mode`) |
| Claude API | `com.anthropic:sdk` 호출 |
| 찜꽁 HTTP 클라이언트 | `WebClient` (Spring WebFlux) |
| JSON 직렬화 | Jackson |
| 빌드 | Gradle |

---

## 파일 구조

```
slack-bot/
├── src/main/java/com/example/slackbot/
│   ├── SlackBotApplication.java          # 진입점
│   ├── config/
│   │   └── AppConfig.java                # WebClient, Slack App 빈 등록
│   ├── slack/
│   │   └── SlackEventHandler.java        # app_mention 이벤트 처리
│   ├── claude/
│   │   ├── ClaudeParserService.java      # Claude API 호출 → ParsedIntent 반환
│   │   └── ParsedIntent.java             # 파싱 결과 DTO
│   ├── zzimkkong/
│   │   ├── ZzimkkongClient.java          # 찜꽁 REST API WebClient 래퍼
│   │   └── dto/                          # 찜꽁 요청/응답 DTO
│   ├── handler/
│   │   ├── IntentHandlerRouter.java      # intent → 핸들러 위임
│   │   └── impl/
│   │       ├── CreateReservationHandler.java
│   │       ├── GetReservationsHandler.java
│   │       ├── GetAvailabilityHandler.java
│   │       ├── GetSpacesHandler.java
│   │       └── UnknownIntentHandler.java
│   └── session/
│       └── PendingSessionStore.java      # 다중 턴용 채널별 임시 상태 저장
├── src/main/resources/
│   └── application.yml
└── build.gradle
```

---

## 환경변수 (application.yml)

```yaml
slack:
  bot-token: ${SLACK_BOT_TOKEN}
  app-token: ${SLACK_APP_TOKEN}     # Socket Mode용

anthropic:
  api-key: ${ANTHROPIC_API_KEY}
  model: claude-sonnet-4-20250514

zzimkkong:
  base-url: ${ZZIMKKONG_BASE_URL:https://k8s.zzimkkong.com/api}
  sharing-map-id: ${ZZIMKKONG_SHARING_MAP_ID}
```

---

## Claude API 파싱 (ClaudeParserService.java)

### 역할

사용자 자연어 → `ParsedIntent` DTO 반환

### System Prompt

```
당신은 회의실 예약 시스템의 파서입니다.
사용자 메시지에서 의도(intent)와 파라미터를 추출해 JSON으로만 응답하세요. 마크다운 백틱(```)은 절대 사용하지 마세요.

지원 intent:
- create_reservation : 예약 신청
- get_reservations   : 예약 목록 조회
- get_availability   : 예약 가능 여부 조회
- get_spaces         : 공간 목록 조회
- unknown            : 판단 불가

파라미터 추출 및 보정 규칙:
1. date: yyyy-MM-dd
   - 언급이 없거나 "오늘"이면 {today}로 설정합니다.
   - 요청 날짜가 오늘부터 한 달({max_date})을 초과할 경우, intent는 유지하되 date를 null로 설정하고 missing 배열에 "date_out_of_range"를 추가하세요.
2. start_time / end_time: HH:mm
   - 운영 시간(09:00 ~ 22:00 KST) 기준으로 판단합니다. (예: "1시" → 13:00)
   - end_time이 없으면 start_time + 1시간으로 설정합니다.
   - 예약 시간이 1시간을 초과할 경우, intent는 유지하되 end_time을 null로 설정하고 missing 배열에 "duration_exceeded"를 추가하세요.
3. password: 4자리 숫자 문자열 (언급이 없으면 null)
4. name: 예약자 이름 (언급이 없으면 null)
5. description: 예약 목적 (언급이 없으면 null)
6. space_name: 공간 이름
7. 필수 누락 검사: create_reservation 의도일 때 password, space_name, start_time 등이 메시지에 없다면 해당 파라미터 키워드를 missing 배열에 명시하세요.

오늘 날짜(KST): {today}
예약 가능 마감일: {max_date}

출력 형식 예시:
{"intent": "create_reservation", "params": {"date": "2026-05-22", "start_time": "14:00", "end_time": "15:00", "space_name": "회의실 A", "name": null, "password": "1234", "description": "팀 회의"}, "missing": ["password"], "raw_message": "..."}
```

### 구현

```java
// ClaudeParserService.java
@Service
public class ClaudeParserService {

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;

    // application.yml 값 주입 생략

    public ParsedIntent parse(String userMessage) {
        ZoneId kst = ZoneId.of("Asia/Seoul");
        LocalDate todayKst = LocalDate.now(kst);
        String today = todayKst.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String maxDate = todayKst.plusMonths(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String systemPrompt = SYSTEM_PROMPT_TEMPLATE
            .replace("{today}", today)
            .replace("{max_date}", maxDate);

        Map<String, Object> requestBody = Map.of(
            "model", model,
            "max_tokens", 512,
            "system", systemPrompt,
            "messages", List.of(Map.of("role", "user", "content", userMessage))
        );

        String responseJson = webClient.post()
            .uri(CLAUDE_API_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .map(node -> node.at("/content/0/text").asText())
            .block();

        return objectMapper.readValue(responseJson, ParsedIntent.class);
    }

    // Claude가 파싱한 name이 없을 때만 Slack 닉네임을 fallback으로 사용
    // password가 없으면 4자리 랜덤 숫자 자동 생성
    public ParsedIntent parse(String userMessage, String slackName) {
        ParsedIntent parsed = parse(userMessage);
        Map<String, String> params = new HashMap<>(parsed.params());

        // name: 명시적 이름 없을 때만 Slack 닉네임으로 채움
        String parsedName = parsed.params().get("name");
        if (parsedName == null || parsedName.isBlank() || parsedName.equals("null")) {
            params.put("name", slackName);
        } else {
            params.put("name", parsedName);
        }

        // password: 명시되지 않았으면 4자리 랜덤 숫자 자동 생성
        String parsedPassword = parsed.params().get("password");
        if (parsedPassword == null || parsedPassword.isBlank() || parsedPassword.equals("null")) {
            String randomPassword = String.format("%04d", new java.util.Random().nextInt(10000));
            params.put("password", randomPassword);
        }

        List<String> missing = parsed.missing().stream()
            .filter(m -> !m.equals("name"))
            .filter(m -> !m.equals("password"))  // password도 항상 확보됐으므로 제거
            .toList();
        return new ParsedIntent(parsed.intent(), params, missing, parsed.rawMessage());
    }
}
```

### ParsedIntent DTO

```java
// ParsedIntent.java
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParsedIntent(
    String intent,
    Map<String, String> params,
    List<String> missing,
    String rawMessage
) {}
```

---

## 찜꽁 REST API 클라이언트 (ZzimkkongClient.java)

### 사용 API 목록

#### 맵 / 공간 조회

| 메서드 | 엔드포인트 | 주요 파라미터 |
|--------|-----------|--------------|
| GET | `/guests/maps` | `sharingMapId: String` |
| GET | `/guests/maps/{mapId}/spaces` | `mapId: int` |
| GET | `/guests/maps/{mapId}/spaces/{spaceId}` | `mapId, spaceId: int` |
| GET | `/guests/maps/{mapId}/spaces/availability` | `mapId: int`, `startDatetime, endDatetime: String` (ISO 8601) |

#### 예약 조회

| 메서드 | 엔드포인트 | 주요 파라미터 |
|--------|-----------|--------------|
| GET | `/guests/maps/{mapId}/spaces/{spaceId}/reservations` | `mapId, spaceId: int`, `date: String` (yyyy-MM-dd) |
| GET | `/guests/maps/{mapId}/spaces/reservations` | `mapId: int`, `date: String` |
| GET | `/guests/non-login/reservations` | `userName: String`, `searchStartTime: String`, `page: int = 0`, `size: int = 20` |
| POST | `/guests/maps/{mapId}/spaces/{spaceId}/reservations/{reservationId}` | `mapId, spaceId, reservationId: int`, `password: String` |

#### 예약 생성

| 메서드 | 엔드포인트 | 주요 파라미터 |
|--------|-----------|--------------|
| POST | `/guests/maps/{mapId}/spaces/{spaceId}/reservations` | `mapId, spaceId: int`, `startDatetime, endDatetime: String` (ISO 8601), `password: String` (4자리), `name: String` (1-20자), `description: String` |

### 구현

에러 시 예외 throw 없이 `Map<String, Object>` 형태로 `{"error": ..., "status": ...}` 반환.

```java
@Component
public class ZzimkkongClient {

    private final WebClient webClient;

    public Map<String, Object> getMap(String sharingMapId) {
        return webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/guests/maps")
                .queryParam("sharingMapId", sharingMapId)
                .build())
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
            .onErrorResume(WebClientResponseException.class, e ->
                Mono.just(Map.of("error", e.getResponseBodyAsString(), "status", e.getStatusCode().value())))
            .block();
    }

    public Map<String, Object> getSpaces(int mapId) { ... }

    public Map<String, Object> getSpaceAvailability(int mapId, String startDatetime, String endDatetime) { ... }

    public Map<String, Object> getSpaceReservations(int mapId, int spaceId, String date) { ... }

    public Map<String, Object> getAllSpaceReservations(int mapId, String date) { ... }

    public Map<String, Object> createReservation(
        int mapId, int spaceId,
        String startDatetime, String endDatetime,
        String password, String name, String description
    ) { ... }

    public Map<String, Object> getReservationDetail(
        int mapId, int spaceId, int reservationId, String password
    ) { ... }

    // 헬퍼: date + time → ISO 8601 KST
    public static String toIso(String date, String time) {
        return date + "T" + time + ":00+09:00";
    }
}
```

---

## Intent 처리 (handler/)

### IntentHandlerRouter.java

```java
@Component
public class IntentHandlerRouter {

    private final Map<String, IntentHandler> handlerMap;

    public IntentHandlerRouter(List<IntentHandler> handlers) {
        this.handlerMap = handlers.stream()
            .collect(Collectors.toMap(IntentHandler::supportedIntent, Function.identity()));
    }

    public String route(ParsedIntent parsed, String sessionKey) {
        IntentHandler handler = handlerMap.getOrDefault(parsed.intent(),
            handlerMap.get("unknown"));
        return handler.handle(parsed, sessionKey);
    }
}
```

### IntentHandler 인터페이스

```java
public interface IntentHandler {
    String supportedIntent();
    String handle(ParsedIntent parsed, String sessionKey);
}
```

### CreateReservationHandler.java (예시)

```java
@Component
public class CreateReservationHandler implements IntentHandler {

    private final ZzimkkongClient client;
    private final PendingSessionStore sessionStore;

    @Value("${zzimkkong.sharing-map-id}")
    private String sharingMapId;

    @Override
    public String supportedIntent() { return "create_reservation"; }

    @Override
    public String handle(ParsedIntent parsed, String sessionKey) {
        // 1. missing 파라미터 확인
        if (!parsed.missing().isEmpty()) {
            sessionStore.save(sessionKey, parsed);
            return MISSING_PROMPTS.get(parsed.missing().get(0));
        }

        // 2. mapId 조회
        Map<String, Object> mapInfo = client.getMap(sharingMapId);
        int mapId = (int) mapInfo.get("mapId");

        // 3. space_name → space_id 매핑
        Integer spaceId = resolveSpaceId(mapId, parsed.params().get("space_name"));
        if (spaceId == null) {
            return "'" + parsed.params().get("space_name") + "' 공간을 찾을 수 없습니다.";
        }

        // 4. 예약 생성
        Map<String, String> p = parsed.params();
        Map<String, Object> result = client.createReservation(
            mapId, spaceId,
            ZzimkkongClient.toIso(p.get("date"), p.get("start_time")),
            ZzimkkongClient.toIso(p.get("date"), p.get("end_time")),
            p.get("password"), p.get("name"), p.get("description")
        );

        if (result.containsKey("error")) {
            return "예약 실패: " + result.get("error");
        }
        String password = p.get("password");
        return "✅ 예약 완료 — 예약 번호: " + result.get("reservationId") + "\n"
             + "🔑 비밀번호: " + password + " (예약 취소 시 필요하니 저장해두세요)";
    }

    private Integer resolveSpaceId(int mapId, String spaceName) {
        Map<String, Object> spaces = client.getSpaces(mapId);
        List<Map<String, Object>> spaceList =
            (List<Map<String, Object>>) spaces.get("spaces");
        String nameLower = spaceName.strip().toLowerCase();
        return spaceList.stream()
            .filter(s -> ((String) s.get("name")).toLowerCase().contains(nameLower))
            .map(s -> (int) s.get("id"))
            .findFirst()
            .orElse(null);
    }
}
```

---

## Slack Bot (SlackEventHandler.java)

```java
// SlackEventHandler.java
@Component
public class SlackEventHandler {

    private final ClaudeParserService claudeParser;
    private final IntentHandlerRouter router;
    private final PendingSessionStore sessionStore;

    @Bean
    public App slackApp(@Value("${slack.bot-token}") String botToken) {
        App app = new App(AppConfig.builder().singleTeamBotToken(botToken).build());

        app.event(AppMentionEvent.class, (payload, ctx) -> {
            String channelId = payload.getEvent().getChannel();
            String userId = payload.getEvent().getUser();
            String userMessage = payload.getEvent().getText();

            // Slack 닉네임 조회 (displayName 없으면 realName fallback)
            UsersInfoResponse userInfo = ctx.client().usersInfo(r -> r.user(userId));
            String displayName = userInfo.getUser().getProfile().getDisplayName();
            String slackName = (displayName != null && !displayName.isBlank())
                ? displayName
                : userInfo.getUser().getProfile().getRealName();

            // 세션 키: 단체 채팅방 동시 예약 충돌 방지를 위해 channelId + userId 복합키 사용
            String sessionKey = channelId + "_" + userId;

            // 다중 턴: pending 상태 있으면 기존 파싱 결과에 보완
            ParsedIntent pending = sessionStore.get(sessionKey);
            ParsedIntent parsed = (pending != null)
                ? claudeParser.parseWithContext(pending, userMessage, slackName)
                : claudeParser.parse(userMessage, slackName);

            sessionStore.clear(sessionKey);

            String reply = router.route(parsed, channelId);
            ctx.say(reply);
            return ctx.ack();
        });

        return app;
    }
}
```

Slack 이벤트 수신 방식: **Socket Mode** (별도 public endpoint 불필요)

---

## 다중 턴 처리 (PendingSessionStore.java)

```java
// PendingSessionStore.java
@Component
public class PendingSessionStore {

    // 세션 키: channelId + "_" + userId 복합키 (프로덕션은 Redis로 교체)
    private final Map<String, ParsedIntent> store = new ConcurrentHashMap<>();

    public void save(String sessionKey, ParsedIntent intent) {
        store.put(sessionKey, intent);
    }

    public ParsedIntent get(String sessionKey) {
        return store.get(sessionKey);
    }

    public void clear(String sessionKey) {
        store.remove(sessionKey);
    }
}
```

---

## 에러 응답 포맷

| 상황 | Slack 응답 |
|------|-----------|
| Claude 파싱 실패 (JSON 파싱 오류) | "요청 파싱에 실패했습니다. 다시 시도해주세요." |
| 공간 이름 매핑 실패 | "'{space_name}' 공간을 찾을 수 없습니다." |
| 날짜가 한 달 초과 (`date_out_of_range`) | "예약은 오늘부터 {max_date}까지만 가능합니다." |
| 예약 시간 1시간 초과 (`duration_exceeded`) | "예약은 최대 1시간까지 가능합니다." |
| 찜꽁 API 4xx | 응답 `error` 메시지 그대로 전달 |
| 찜꽁 API 5xx | "서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요." |
| 네트워크 타임아웃 | "요청 시간이 초과됐습니다." |

---

## build.gradle

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'com.slack.api:bolt-socket-mode:1.40.0'
    implementation 'com.slack.api:slack-api-client:1.40.0'
    implementation 'com.fasterxml.jackson.core:jackson-databind'
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
```

---

## 검증 시나리오

1. **정상 예약**: "내일 오후 2시~3시 회의실 A 예약해줘. 이름 김희영, 비밀번호 1234, 팀 회의"
   - Claude 파싱 → intent: create_reservation, missing: []
   - space_name "회의실 A" → space_id 매핑
   - 찜꽁 POST 성공 → "✅ 예약 완료 — 예약 번호: 42"

2. **파라미터 누락**: "내일 2시~3시 회의실 A 예약"
   - Claude 파싱 → missing: ["password", "name"]
   - Bot: "🔑 비밀번호 4자리를 입력해주세요."
   - 사용자: "1234" → 재파싱 → 예약 진행

3. **조회**: "오늘 회의실 A 예약 현황 알려줘"
   - intent: get_reservations → 목록 반환

4. **unknown**: "날씨 어때?"
   - intent: unknown → 안내 메시지 반환

5. **공간 이름 불일치**: "회의실Z 예약해줘"
   - resolveSpaceId → null → 공간 없음 안내
