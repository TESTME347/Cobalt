package it.auties.whatsapp.model.signal.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import it.auties.protobuf.api.model.ProtobufMessage;
import lombok.*;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import java.util.Arrays;

@AllArgsConstructor
@Data
@Builder
@Jacksonized
@Accessors(fluent = true)
public class WebInfo implements ProtobufMessage {
  @JsonProperty("4")
  @JsonPropertyDescription("WebInfoWebSubPlatform")
  private WebInfoWebSubPlatform webSubPlatform;

  @JsonProperty("3")
  @JsonPropertyDescription("WebdPayload")
  private WebPayload webdPayload;

  @JsonProperty("2")
  @JsonPropertyDescription("string")
  private String version;

  @JsonProperty("1")
  @JsonPropertyDescription("string")
  private String refToken;

  public WebInfo(@NonNull WebInfoWebSubPlatform webSubPlatform){
    this.webSubPlatform = webSubPlatform;
  }

  @AllArgsConstructor
  @Accessors(fluent = true)
  public enum WebInfoWebSubPlatform implements ProtobufMessage {
    WEB_BROWSER(0),
    APP_STORE(1),
    WIN_STORE(2),
    DARWIN(3),
    WIN32(4);

    @Getter
    private final int index;

    public static WebInfoWebSubPlatform forIndex(int index) {
      return Arrays.stream(values())
          .filter(entry -> entry.index() == index)
          .findFirst()
          .orElse(null);
    }
  }
}