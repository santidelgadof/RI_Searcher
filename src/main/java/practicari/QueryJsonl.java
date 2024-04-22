package practicari;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

public record QueryJsonl(
        @JsonProperty("_id") int id,
        String text,
        Metadata metadata
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Metadata(String query, String narrative) {}
}