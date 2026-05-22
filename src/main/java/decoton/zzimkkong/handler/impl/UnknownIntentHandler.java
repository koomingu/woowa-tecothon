package decoton.zzimkkong.handler.impl;

import decoton.zzimkkong.claude.ParsedIntent;
import decoton.zzimkkong.handler.IntentHandler;
import org.springframework.stereotype.Component;

@Component
public class UnknownIntentHandler implements IntentHandler {

    @Override
    public String supportedIntent() {
        return "unknown";
    }

    @Override
    public String handle(ParsedIntent parsed, String sessionKey) {
        return """
                죄송해요, 이해하지 못했습니다. 다음 기능을 지원합니다:
                • 예약 신청: "내일 오후 2시~3시 회의실 A 예약해줘"
                • 예약 조회: "오늘 회의실 A 예약 현황 알려줘"
                • 가용성 조회: "내일 오전 공간 비어있는 곳 알려줘"
                • 공간 목록: "예약 가능한 공간 목록 보여줘"
                """.stripTrailing();
    }
}
