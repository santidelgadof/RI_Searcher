package practicari;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record Doc(
    @JsonProperty("_id") String id,
    String title,
    String text,
    Metadata metadata
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Metadata(String url, @JsonProperty("pubmed_id") String pubmed_id) {}
}
