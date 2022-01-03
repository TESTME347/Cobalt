package it.auties.whatsapp.protobuf.signal.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.*;
import lombok.experimental.Accessors;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@Accessors(fluent = true)
public class ClientHello {
  @JsonProperty("1")
  @JsonPropertyDescription("bytes")
  private byte[] ephemeral;

  @JsonProperty("2")
  @JsonPropertyDescription("bytes")
  private byte[] staticText;

  @JsonProperty("3")
  @JsonPropertyDescription("bytes")
  private byte[] payload;

  public ClientHello(byte @NonNull [] ephemeral){
    this.ephemeral = ephemeral;
  }
}