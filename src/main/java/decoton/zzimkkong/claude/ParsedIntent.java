package decoton.zzimkkong.claude;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ParsedIntent(
        String intent,
        Map<String, String> params,
        List<String> missing,
        @JsonProperty("raw_message") String rawMessage
) {}
