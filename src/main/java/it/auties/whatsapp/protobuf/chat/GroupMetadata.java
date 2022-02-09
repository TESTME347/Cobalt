package it.auties.whatsapp.protobuf.chat;

import it.auties.whatsapp.protobuf.contact.ContactJid;
import it.auties.whatsapp.socket.Node;
import it.auties.whatsapp.util.WhatsappUtils;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.Value;
import lombok.experimental.Accessors;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.*;

import static it.auties.whatsapp.protobuf.chat.GroupPolicy.forData;
import static it.auties.whatsapp.protobuf.chat.GroupSetting.EDIT_GROUP_INFO;
import static it.auties.whatsapp.protobuf.chat.GroupSetting.SEND_MESSAGES;

@AllArgsConstructor
@Value
@Accessors(fluent = true)
public class GroupMetadata {
    @NonNull
    ContactJid jid;

    @NonNull
    String subject;

    @NonNull
    ZonedDateTime subjectTimestamp;

    @NonNull
    ZonedDateTime foundationTimestamp;

    @NonNull
    ContactJid founder;

    String description;

    String descriptionId;

    @NonNull
    Map<GroupSetting, GroupPolicy> policies;

    @NonNull
    List<GroupParticipant> participants;

    ZonedDateTime ephemeralExpiration;

    public static GroupMetadata of(@NonNull Node node){
        var groupId = node.attributes()
                .getOptionalString("id")
                .map(id -> ContactJid.ofUser(id, ContactJid.Server.GROUP))
                .orElseThrow(() -> new NoSuchElementException("Missing group jid"));
        var subject = node.attributes().getString("subject");
        var subjectTimestamp = WhatsappUtils.parseWhatsappTime(node.attributes().getLong("s_t"))
                .orElse(ZonedDateTime.now());
        var foundationTimestamp = WhatsappUtils.parseWhatsappTime(node.attributes().getLong("creation"))
                .orElse(ZonedDateTime.now());
        var founder = node.attributes()
                .getJid("creator")
                .orElseThrow(() -> new NoSuchElementException("Missing founder"));
        var policies = new HashMap<GroupSetting, GroupPolicy>();
        policies.put(SEND_MESSAGES, forData(node.hasNode("announce")));
        policies.put(EDIT_GROUP_INFO, forData(node.hasNode("locked")));
        var descWrapper = node.findNode("description");
        var description = Optional.ofNullable(descWrapper)
                .map(parent -> parent.findNode("body"))
                .map(GroupMetadata::parseDescription)
                .orElse(null);
        var descriptionId = Optional.ofNullable(descWrapper)
                .map(Node::attributes)
                .flatMap(attributes -> attributes.getOptionalString("id"))
                .orElse(null);
        var ephemeral = Optional.ofNullable(node.findNode("ephemeral"))
                .map(Node::attributes)
                .map(attributes -> attributes.getLong("expiration"))
                .flatMap(WhatsappUtils::parseWhatsappTime)
                .orElse(null);
        var participants = node.findNodes("participant")
                .stream()
                .map(GroupParticipant::of)
                .toList();
        return new GroupMetadata(groupId, subject, subjectTimestamp, foundationTimestamp, founder, description, descriptionId, policies, participants, ephemeral);
    }

    private static String parseDescription(Node wrapper) {
        return switch (wrapper.content()){
            case null -> null;
            case String string -> string;
            case byte[] bytes -> new String(bytes, StandardCharsets.UTF_8);
            default -> throw new IllegalArgumentException("Illegal body type: %s".formatted(wrapper.content().getClass().getName()));
        };
    }

    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    public Optional<ZonedDateTime> ephemeralExpiration() {
        return Optional.ofNullable(ephemeralExpiration);
    }
}