package decoton.zzimkkong.zzimkkong;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class ZzimkkongClient {

    private final WebClient webClient;

    public ZzimkkongClient(WebClient zzimkkongWebClient) {
        this.webClient = zzimkkongWebClient;
    }

    public Map<String, Object> getMap(String sharingMapId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/guests/maps")
                        .queryParam("sharingMapId", sharingMapId)
                        .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(Map.of("error", e.getResponseBodyAsString(), "status", e.getStatusCode().value())))
                .block();
    }

    public Map<String, Object> getSpaces(int mapId) {
        return webClient.get()
                .uri("/guests/maps/{mapId}/spaces", mapId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(Map.of("error", e.getResponseBodyAsString(), "status", e.getStatusCode().value())))
                .block();
    }

    public Map<String, Object> getSpaceAvailability(int mapId, String startDatetime, String endDatetime) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/guests/maps/{mapId}/spaces/availability")
                        .queryParam("startDatetime", startDatetime)
                        .queryParam("endDatetime", endDatetime)
                        .build(mapId))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(Map.of("error", e.getResponseBodyAsString(), "status", e.getStatusCode().value())))
                .block();
    }

    public Map<String, Object> getSpaceReservations(int mapId, int spaceId, String date) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/guests/maps/{mapId}/spaces/{spaceId}/reservations")
                        .queryParam("date", date)
                        .build(mapId, spaceId))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(Map.of("error", e.getResponseBodyAsString(), "status", e.getStatusCode().value())))
                .block();
    }

    public Map<String, Object> getAllSpaceReservations(int mapId, String date) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/guests/maps/{mapId}/spaces/reservations")
                        .queryParam("date", date)
                        .build(mapId))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(Map.of("error", e.getResponseBodyAsString(), "status", e.getStatusCode().value())))
                .block();
    }

    public Map<String, Object> createReservation(
            int mapId, int spaceId,
            String startDatetime, String endDatetime,
            String password, String name, String description
    ) {
        Map<String, Object> body = Map.of(
                "startDatetime", startDatetime,
                "endDatetime", endDatetime,
                "password", password,
                "name", name,
                "description", description != null ? description : ""
        );

        return webClient.post()
                .uri("/guests/maps/{mapId}/spaces/{spaceId}/reservations", mapId, spaceId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(Map.of("error", e.getResponseBodyAsString(), "status", e.getStatusCode().value())))
                .block();
    }

    public Map<String, Object> getReservationDetail(int mapId, int spaceId, int reservationId, String password) {
        return webClient.post()
                .uri("/guests/maps/{mapId}/spaces/{spaceId}/reservations/{reservationId}",
                        mapId, spaceId, reservationId)
                .bodyValue(Map.of("password", password))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(Map.of("error", e.getResponseBodyAsString(), "status", e.getStatusCode().value())))
                .block();
    }

    public static String toIso(String date, String time) {
        return date + "T" + time + ":00+09:00";
    }
}
