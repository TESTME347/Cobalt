package it.auties.whatsapp.model.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import it.auties.protobuf.api.model.ProtobufProperty;
import it.auties.whatsapp.model.sync.ActionMessageRangeSync;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import static it.auties.protobuf.api.model.ProtobufProperty.Type.*;

@AllArgsConstructor
@Data
@Builder
@Jacksonized
@Accessors(fluent = true)
public final class MarkChatAsReadAction implements Action {
  @ProtobufProperty(index = 1, type = BOOLEAN)
  private boolean read;

  @ProtobufProperty(index = 2, type = MESSAGE, concreteType = ActionMessageRangeSync.class)
  private ActionMessageRangeSync messageRange;

  @Override
  public String indexName() {
    return "markChatAsRead";
  }
}