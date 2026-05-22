package decoton.zzimkkong.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ClaudeParserService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeParserService.class);
    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";

    private static final String SYSTEM_PROMPT_TEMPLATE = """
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
            {"intent": "create_reservation", "params": {"date": "2026-05-22", "start_time": "14:00", "end_time": "15:00", "space_name": "회의실 A", "name": null, "password": "1234", "description": "팀 회의"}, "missing": [], "raw_message": "..."}
            """;

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;

    public ClaudeParserService(
            @Value("${anthropic.api-key}") String apiKey,
            @Value("${anthropic.model}") String model,
            ObjectMapper objectMapper
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.create();
    }

    public ParsedIntent parse(String userMessage) {
        String systemPrompt = buildSystemPrompt();

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 512,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userMessage))
        );

        try {
            String responseJson = webClient.post()
                    .uri(CLAUDE_API_URL)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .map(node -> node.at("/content/0/text").asText())
                    .map(raw -> {
                        log.info("Claude 원본 응답: {}", raw);
                        return extractJson(raw);
                    })
                    .block();

            log.info("Claude 파싱 결과: {}", responseJson);
            return objectMapper.readValue(responseJson, ParsedIntent.class);
        } catch (WebClientResponseException e) {
            log.error("Claude API 오류 [{}]: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return new ParsedIntent("unknown", Map.of(), List.of("parse_error"), userMessage);
        } catch (Exception e) {
            log.error("Claude 파싱 실패: {}", e.getMessage(), e);
            return new ParsedIntent("unknown", Map.of(), List.of("parse_error"), userMessage);
        }
    }

    public ParsedIntent parse(String userMessage, String slackName) {
        ParsedIntent parsed = parse(userMessage);
        return applyFallbacks(parsed, slackName);
    }

    public ParsedIntent parseWithContext(ParsedIntent pending, String supplement, String slackName) {
        ParsedIntent supplementParsed = parse(supplement);

        Map<String, String> mergedParams = new HashMap<>(pending.params());
        if (supplementParsed.params() != null) {
            supplementParsed.params().forEach((k, v) -> {
                if (v != null && !v.isBlank() && !v.equals("null")) {
                    mergedParams.put(k, v);
                }
            });
        }

        List<String> remainingMissing = pending.missing().stream()
                .filter(m -> {
                    String val = mergedParams.get(m);
                    return val == null || val.isBlank() || val.equals("null");
                })
                .toList();

        ParsedIntent merged = new ParsedIntent(
                pending.intent(),
                mergedParams,
                remainingMissing,
                pending.rawMessage()
        );
        return applyFallbacks(merged, slackName);
    }

    private ParsedIntent applyFallbacks(ParsedIntent parsed, String slackName) {
        Map<String, String> params = new HashMap<>(parsed.params());

        String parsedName = params.get("name");
        if (parsedName == null || parsedName.isBlank() || parsedName.equals("null")) {
            params.put("name", slackName);
        }

        String parsedPassword = params.get("password");
        if (parsedPassword == null || parsedPassword.isBlank() || parsedPassword.equals("null")) {
            String generated = String.format("%04d", (int) (Math.random() * 10000));
            params.put("password", generated);
            log.info("비밀번호 자동 생성: {}", generated);
        } else {
            log.info("비밀번호 사용자 입력: {}", parsedPassword);
        }

        List<String> missing = parsed.missing().stream()
                .filter(m -> !m.equals("name"))
                .filter(m -> !m.equals("password"))
                .toList();

        return new ParsedIntent(parsed.intent(), params, missing, parsed.rawMessage());
    }

    private static String extractJson(String raw) {
        if (raw == null) return "{}";
        String text = raw.strip();
        // ```json ... ``` 또는 ``` ... ``` 형태 제거
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start >= 0 && end > start) {
                text = text.substring(start + 1, end).strip();
            }
        }
        // { ... } 범위만 추출
        int jsonStart = text.indexOf('{');
        int jsonEnd = text.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            text = text.substring(jsonStart, jsonEnd + 1);
        }
        return text;
    }

    private String buildSystemPrompt() {
        ZoneId kst = ZoneId.of("Asia/Seoul");
        LocalDate todayKst = LocalDate.now(kst);
        String today = todayKst.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String maxDate = todayKst.plusMonths(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        return SYSTEM_PROMPT_TEMPLATE
                .replace("{today}", today)
                .replace("{max_date}", maxDate);
    }
}
