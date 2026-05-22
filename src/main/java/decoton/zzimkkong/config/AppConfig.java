package decoton.zzimkkong.config;

import com.slack.api.bolt.App;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AppConfig {

    @Bean
    public WebClient zzimkkongWebClient(@Value("${zzimkkong.base-url}") String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    public CommandLineRunner startSocketMode(App slackApp,
            @Value("${slack.app-token}") String appToken) {
        return args -> {
            SocketModeApp socketModeApp = new SocketModeApp(appToken, slackApp);
            socketModeApp.startAsync();
        };
    }
}
