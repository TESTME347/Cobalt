package it.auties.whatsapp.model.signal.sender;

import it.auties.bytes.Bytes;
import it.auties.curve25519.Curve25519;
import it.auties.protobuf.api.model.ProtobufMessage;
import it.auties.protobuf.api.model.ProtobufProperty;
import it.auties.protobuf.api.model.ProtobufSchema;
import it.auties.whatsapp.util.BytesHelper;
import it.auties.whatsapp.util.JacksonProvider;
import it.auties.whatsapp.util.SignalSpecification;
import lombok.*;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import java.io.IOException;

import static it.auties.protobuf.api.model.ProtobufProperty.Type.BYTES;
import static it.auties.protobuf.api.model.ProtobufProperty.Type.UINT32;

@AllArgsConstructor
@Data
@Builder
@Jacksonized
@Accessors(fluent = true)
public class SenderKeyMessage implements ProtobufMessage, JacksonProvider, SignalSpecification {
  private int version;

  @ProtobufProperty(index = 1, type = UINT32)
  private int id;

  @ProtobufProperty(index = 2, type = UINT32)
  private int iteration;

  @ProtobufProperty(index = 3, type = BYTES)
  private byte @NonNull [] cipherText;

  @ProtobufProperty(index = 4, type = BYTES)
  private byte[] signingKey;

  private byte[] signature;

  private byte[] serialized;

  @SneakyThrows
  public SenderKeyMessage(int id, int iteration, byte[] cipherText, byte[] signingKey) {
    this.version = CURRENT_VERSION;
    this.id = id;
    this.iteration = iteration;
    this.cipherText = cipherText;
    var encodedVersion = BytesHelper.versionToBytes(version);
    var encoded =  PROTOBUF.writeValueAsBytes(this);
    var encodedMessage = Bytes.of(encodedVersion)
            .append(encoded);
    this.signature = Curve25519.calculateSignature(signingKey, encodedMessage.toByteArray());
    this.serialized = encodedMessage.append(signature).toByteArray();
  }

  public static SenderKeyMessage ofSerialized(byte[] serialized) {
    try {
      var buffer = Bytes.of(serialized);
      return PROTOBUF.reader()
              .with(ProtobufSchema.of(SenderKeyMessage.class))
              .readValue(buffer.slice(1, -SIGNATURE_LENGTH).toByteArray(), SenderKeyMessage.class)
              .version(BytesHelper.bytesToVersion(serialized[0]))
              .signature(buffer.slice(-SIGNATURE_LENGTH).toByteArray())
              .serialized(serialized);
    } catch (IOException exception) {
      throw new RuntimeException("Cannot decode SenderKeyMessage", exception);
    }
  }
}