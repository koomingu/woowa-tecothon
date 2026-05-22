package decoton.zzimkkong.handler.impl;

import decoton.zzimkkong.claude.ParsedIntent;
import decoton.zzimkkong.handler.IntentHandler;
import decoton.zzimkkong.zzimkkong.ZzimkkongClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GetSpacesHandler implements IntentHandler {

    private final ZzimkkongClient client;

    @Value("${zzimkkong.sharing-map-id}")
    private String sharingMapId;

    public GetSpacesHandler(ZzimkkongClient client) {
        this.client = client;
    }

    @Override
    public String supportedIntent() {
        return "get_spaces";
    }

    @Override
    @SuppressWarnings("unchecked")
    public String handle(ParsedIntent parsed, String sessionKey) {
        Map<String, Object> mapInfo = client.getMap(sharingMapId);
        if (mapInfo.containsKey("error")) {
            return "맵 정보를 불러오는 데 실패했습니다.";
        }
        int mapId = ((Number) mapInfo.get("mapId")).intValue();

        Map<String, Object> result = client.getSpaces(mapId);
        if (result.containsKey("error")) {
            return "공간 목록 조회에 실패했습니다: " + result.get("error");
        }

        List<Map<String, Object>> spaces = (List<Map<String, Object>>) result.get("spaces");
        if (spaces == null || spaces.isEmpty()) {
            return "등록된 공간이 없습니다.";
        }

        StringBuilder sb = new StringBuilder("예약 가능한 공간 목록:\n");
        for (Map<String, Object> s : spaces) {
            sb.append("• ").append(s.get("name")).append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
