package it.auties.whatsapp.protobuf.beta;

import com.fasterxml.jackson.annotation.*;
import java.util.*;
import lombok.*;
import lombok.experimental.Accessors;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@Accessors(fluent = true)
public class AppStateSyncKeyRequest {

  @JsonProperty(value = "1", required = false)
  @JsonPropertyDescription("AppStateSyncKeyId")
  @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
  private List<AppStateSyncKeyId> keyIds;
}
