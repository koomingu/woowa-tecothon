package decoton.zzimkkong.handler.impl;

import decoton.zzimkkong.claude.ParsedIntent;
import decoton.zzimkkong.handler.IntentHandler;
import decoton.zzimkkong.session.PendingSessionStore;
import decoton.zzimkkong.zzimkkong.ZzimkkongClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class CreateReservationHandler implements IntentHandler {

    private static final Map<String, String> MISSING_PROMPTS = Map.of(
            "space_name", "어느 공간을 예약하시겠어요? (공간 이름을 입력해주세요)",
            "start_time", "예약 시작 시간을 입력해주세요. (예: 오후 2시)",
            "date_out_of_range", "예약은 오늘부터 %s까지만 가능합니다.",
            "duration_exceeded", "예약은 최대 1시간까지 가능합니다."
    );

    private final ZzimkkongClient client;
    private final PendingSessionStore sessionStore;

    @Value("${zzimkkong.sharing-map-id}")
    private String sharingMapId;

    public CreateReservationHandler(ZzimkkongClient client, PendingSessionStore sessionStore) {
        this.client = client;
        this.sessionStore = sessionStore;
    }

    @Override
    public String supportedIntent() {
        return "create_reservation";
    }

    @Override
    public String handle(ParsedIntent parsed, String sessionKey) {
        List<String> missing = parsed.missing();

        if (missing.contains("date_out_of_range")) {
            String maxDate = LocalDate.now(ZoneId.of("Asia/Seoul"))
                    .plusMonths(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
            return String.format("예약은 오늘부터 %s까지만 가능합니다.", maxDate);
        }

        if (missing.contains("duration_exceeded")) {
            return "예약은 최대 1시간까지 가능합니다.";
        }

        if (!missing.isEmpty()) {
            sessionStore.save(sessionKey, parsed);
            String firstMissing = missing.get(0);
            return MISSING_PROMPTS.getOrDefault(firstMissing,
                    "'" + firstMissing + "' 정보를 입력해주세요.");
        }

        Map<String, Object> mapInfo = client.getMap(sharingMapId);
        if (mapInfo.containsKey("error")) {
            return "맵 정보를 불러오는 데 실패했습니다.";
        }
        int mapId = ((Number) mapInfo.get("mapId")).intValue();

        String spaceName = parsed.params().get("space_name");
        Integer spaceId = resolveSpaceId(mapId, spaceName);
        if (spaceId == null) {
            return "'" + spaceName + "' 공간을 찾을 수 없습니다.";
        }

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

        return "✅ 예약 완료 — 예약 번호: " + result.get("reservationId") + "\n"
                + "🔑 비밀번호: " + p.get("password") + " (예약 취소 시 필요하니 저장해두세요)";
    }

    @SuppressWarnings("unchecked")
    private Integer resolveSpaceId(int mapId, String spaceName) {
        Map<String, Object> spaces = client.getSpaces(mapId);
        if (spaces.containsKey("error")) return null;

        List<Map<String, Object>> spaceList = (List<Map<String, Object>>) spaces.get("spaces");
        if (spaceList == null) return null;

        String nameLower = spaceName.strip().toLowerCase();
        return spaceList.stream()
                .filter(s -> {
                    String sName = ((String) s.get("name")).toLowerCase();
                    return sName.contains(nameLower) || nameLower.contains(sName);
                })
                .map(s -> ((Number) s.get("id")).intValue())
                .findFirst()
                .orElse(null);
    }
}
