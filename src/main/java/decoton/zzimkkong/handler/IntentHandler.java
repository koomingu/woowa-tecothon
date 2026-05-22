package decoton.zzimkkong.handler;

import decoton.zzimkkong.claude.ParsedIntent;

public interface IntentHandler {
    String supportedIntent();
    String handle(ParsedIntent parsed, String sessionKey);
}
