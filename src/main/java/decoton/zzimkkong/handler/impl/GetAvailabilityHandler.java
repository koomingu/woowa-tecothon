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
public class GetAvailabilityHandler implements IntentHandler {

    private final ZzimkkongClient client;

    @Value("${zzimkkong.sharing-map-id}")
    private String sharingMapId;

    public GetAvailabilityHandler(ZzimkkongClient client) {
        this.client = client;
    }

    @Override
    public String supportedIntent() {
        return "get_availability";
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
        String startTime = parsed.params().getOrDefault("start_time", "09:00");
        String endTime = parsed.params().getOrDefault("end_time", "22:00");

        String startDatetime = ZzimkkongClient.toIso(date, startTime);
        String endDatetime = ZzimkkongClient.toIso(date, endTime);

        Map<String, Object> result = client.getSpaceAvailability(mapId, startDatetime, endDatetime);
        if (result.containsKey("error")) {
            return "가용성 조회에 실패했습니다: " + result.get("error");
        }

        List<Map<String, Object>> spaces = (List<Map<String, Object>>) result.get("spaces");
        if (spaces == null || spaces.isEmpty()) {
            return date + " " + startTime + "~" + endTime + " 에 예약 가능한 공간이 없습니다.";
        }

        StringBuilder sb = new StringBuilder(date + " " + startTime + "~" + endTime + " 예약 가능 공간:\n");
        for (Map<String, Object> s : spaces) {
            sb.append("• ").append(s.get("name")).append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
