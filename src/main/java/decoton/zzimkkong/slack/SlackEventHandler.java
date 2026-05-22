package decoton.zzimkkong.slack;

import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.model.event.AppMentionEvent;
import decoton.zzimkkong.claude.ClaudeParserService;
import decoton.zzimkkong.claude.ParsedIntent;
import decoton.zzimkkong.handler.IntentHandlerRouter;
import decoton.zzimkkong.session.PendingSessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class SlackEventHandler {

    private final ClaudeParserService claudeParser;
    private final IntentHandlerRouter router;
    private final PendingSessionStore sessionStore;

    public SlackEventHandler(
            ClaudeParserService claudeParser,
            IntentHandlerRouter router,
            PendingSessionStore sessionStore
    ) {
        this.claudeParser = claudeParser;
        this.router = router;
        this.sessionStore = sessionStore;
    }

    @Bean
    public App slackApp(@Value("${slack.bot-token}") String botToken) {
        App app = new App(AppConfig.builder().singleTeamBotToken(botToken).build());

        // @멘션 처리
        app.event(AppMentionEvent.class, (payload, ctx) -> {
            String channelId = payload.getEvent().getChannel();
            String userId = payload.getEvent().getUser();
            String userMessage = payload.getEvent().getText();
            String sessionKey = channelId + "_" + userId;

            String slackName = resolveSlackName(ctx.client(), userId);
            String reply = processMessage(userMessage, slackName, sessionKey);

            ctx.say(reply);
            return ctx.ack();
        });

        // /찜꽁 슬래시 커맨드 처리
        app.command("/찜꽁", (req, ctx) -> {
            String userMessage = req.getPayload().getText();
            String userId = req.getPayload().getUserId();
            String channelId = req.getPayload().getChannelId();
            String sessionKey = channelId + "_" + userId;

            String slackName = resolveSlackName(ctx.client(), userId);

            String reply = processMessage(userMessage, slackName, sessionKey);
            return ctx.ack(reply);
        });

        return app;
    }

    private String processMessage(String userMessage, String slackName, String sessionKey) {
        ParsedIntent pending = sessionStore.get(sessionKey);
        ParsedIntent parsed = (pending != null)
                ? claudeParser.parseWithContext(pending, userMessage, slackName)
                : claudeParser.parse(userMessage, slackName);

        sessionStore.clear(sessionKey);
        return router.route(parsed, sessionKey);
    }

    private String resolveSlackName(MethodsClient client, String userId) {
        try {
            UsersInfoResponse userInfo = client.usersInfo(r -> r.user(userId));
            String displayName = userInfo.getUser().getProfile().getDisplayName();
            return (displayName != null && !displayName.isBlank())
                    ? displayName
                    : userInfo.getUser().getProfile().getRealName();
        } catch (Exception e) {
            return "사용자";
        }
    }
}
