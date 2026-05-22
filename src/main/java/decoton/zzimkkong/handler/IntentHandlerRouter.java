package decoton.zzimkkong.handler;

import decoton.zzimkkong.claude.ParsedIntent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class IntentHandlerRouter {

    private final Map<String, IntentHandler> handlerMap;

    public IntentHandlerRouter(List<IntentHandler> handlers) {
        this.handlerMap = handlers.stream()
                .collect(Collectors.toMap(IntentHandler::supportedIntent, Function.identity()));
    }

    public String route(ParsedIntent parsed, String sessionKey) {
        IntentHandler handler = handlerMap.getOrDefault(
                parsed.intent(),
                handlerMap.get("unknown")
        );
        return handler.handle(parsed, sessionKey);
    }
}
