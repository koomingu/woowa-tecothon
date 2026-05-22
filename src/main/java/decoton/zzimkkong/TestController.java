package decoton.zzimkkong;

import decoton.zzimkkong.claude.ClaudeParserService;
import decoton.zzimkkong.claude.ParsedIntent;
import decoton.zzimkkong.handler.IntentHandlerRouter;
import decoton.zzimkkong.session.PendingSessionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/test")
public class TestController {

    private final ClaudeParserService claudeParser;
    private final IntentHandlerRouter router;
    private final PendingSessionStore sessionStore;

    public TestController(ClaudeParserService claudeParser,
                          IntentHandlerRouter router,
                          PendingSessionStore sessionStore) {
        this.claudeParser = claudeParser;
        this.router = router;
        this.sessionStore = sessionStore;
    }

    @PostMapping("/parse")
    public Mono<ParsedIntent> parse(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String name = body.getOrDefault("name", "테스트유저");
        return Mono.fromCallable(() -> claudeParser.parse(message, name))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/chat")
    public Mono<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String name = body.getOrDefault("name", "테스트유저");
        String sessionKey = body.getOrDefault("sessionKey", "test_session");

        return Mono.fromCallable(() -> {
            ParsedIntent pending = sessionStore.get(sessionKey);
            ParsedIntent parsed = (pending != null)
                    ? claudeParser.parseWithContext(pending, message, name)
                    : claudeParser.parse(message, name);
            sessionStore.clear(sessionKey);
            String reply = router.route(parsed, sessionKey);
            return Map.of("reply", reply);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
