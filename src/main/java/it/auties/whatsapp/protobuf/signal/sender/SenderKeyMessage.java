package it.auties.whatsapp.protobuf.signal.sender;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import it.auties.protobuf.decoder.ProtobufDecoder;
import it.auties.whatsapp.binary.BinaryArray;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.IOException;

import static it.auties.protobuf.encoder.ProtobufEncoder.encode;
import static java.util.Arrays.copyOfRange;


@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@Accessors(fluent = true)
public class SenderKeyMessage {
  private static final int SIGNATURE_LENGTH = 64;
  private static final int CURRENT_VERSION = 3;

  private int version;

  @JsonProperty("1")
  @JsonPropertyDescription("uint32")
  private int id;

  @JsonProperty("2")
  @JsonPropertyDescription("uint32")
  private int iteration;

  @JsonProperty("3")
  @JsonPropertyDescription("bytes")
  private byte[] cipherText;

  @JsonProperty("4")
  @JsonPropertyDescription("bytes")
  private byte[] signingKey;
  
  private byte[] serialized;

  public SenderKeyMessage(int id, int iteration, byte[] ciphertext, byte[] signingKey) {
    this(CURRENT_VERSION, id, iteration, ciphertext, signingKey, null);
  }

  public static SenderKeyMessage ofEncoded(byte[] serialized) {
    try {
      return ProtobufDecoder.forType(SenderKeyMessage.class)
              .decode(copyOfRange(serialized, 1, serialized.length - SIGNATURE_LENGTH))
              .version(Byte.toUnsignedInt(serialized[0]) >> 4)
              .serialized(serialized);
    } catch (IOException exception) {
      throw new RuntimeException("Cannot decode SenderKeyMessage", exception);
    }
  }

  public byte[] serialized(){
    if(serialized == null){
      var versionBytes = (byte) (version() << 4 | CURRENT_VERSION);
      var messageBytes = encode(this);
      this.serialized = BinaryArray.of(versionBytes)
              .append(messageBytes)
              .data();
    }

    return serialized;
  }
}