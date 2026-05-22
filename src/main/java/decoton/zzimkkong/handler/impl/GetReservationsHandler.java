package decoton.zzimkkong.handler.impl;

import decoton.zzimkkong.claude.ParsedIntent;
import decoton.zzimkkong.handler.IntentHandler;
import decoton.zzimkkong.zzimkkong.ZzimkkongClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class GetReservationsHandler implements IntentHandler {

    private final ZzimkkongClient client;

    @Value("${zzimkkong.sharing-map-id}")
    private String sharingMapId;

    public GetReservationsHandler(ZzimkkongClient client) {
        this.client = client;
    }

    @Override
    public String supportedIntent() {
        return "get_reservations";
    }

    @Override
    @SuppressWarnings("unchecked")
    public String handle(ParsedIntent parsed, String sessionKey) {
        Map<String, Object> mapInfo = client.getMap(sharingMapId);
        if (mapInfo.containsKey("error")) {
            return "맵 정보를 불러오는 데 실패했습니다.";
        }
        int mapId = ((Number) mapInfo.get("mapId")).intValue();

        String date = parsed.params().getOrDefault("date",
                LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ISO_LOCAL_DATE));

        String spaceName = parsed.params().get("space_name");
        Map<String, Object> result;

        if (spaceName != null && !spaceName.isBlank() && !spaceName.equals("null")) {
            Integer spaceId = resolveSpaceId(mapId, spaceName);
            if (spaceId == null) {
                return "'" + spaceName + "' 공간을 찾을 수 없습니다.";
            }
            result = client.getSpaceReservations(mapId, spaceId, date);
        } else {
            result = client.getAllSpaceReservations(mapId, date);
        }

        if (result.containsKey("error")) {
            return "예약 목록 조회에 실패했습니다: " + result.get("error");
        }

        List<Map<String, Object>> reservations = (List<Map<String, Object>>) result.get("reservations");
        if (reservations == null || reservations.isEmpty()) {
            return date + " 예약 내역이 없습니다.";
        }

        StringBuilder sb = new StringBuilder(date + " 예약 목록:\n");
        for (Map<String, Object> r : reservations) {
            sb.append("• ").append(r.get("name"))
              .append(" | ").append(r.get("startDateTime"))
              .append(" ~ ").append(r.get("endDateTime"))
              .append("\n");
        }
        return sb.toString().stripTrailing();
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
