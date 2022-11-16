package it.auties.whatsapp.model.info;

import com.fasterxml.jackson.annotation.JsonCreator;
import it.auties.protobuf.base.ProtobufProperty;
import it.auties.whatsapp.model.business.BusinessPrivacyStatus;
import it.auties.whatsapp.model.chat.Chat;
import it.auties.whatsapp.model.contact.Contact;
import it.auties.whatsapp.model.contact.ContactJid;
import it.auties.whatsapp.model.media.MediaData;
import it.auties.whatsapp.model.message.model.*;
import it.auties.whatsapp.model.message.server.ProtocolMessage;
import it.auties.whatsapp.model.message.standard.LiveLocationMessage;
import it.auties.whatsapp.model.message.standard.ReactionMessage;
import it.auties.whatsapp.model.sync.PhotoChange;
import it.auties.whatsapp.util.Clock;
import it.auties.whatsapp.util.JacksonProvider;
import lombok.*;
import lombok.Builder.Default;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static it.auties.protobuf.base.ProtobufType.*;
import static java.util.Objects.requireNonNullElseGet;

/**
 * A model class that holds the information related to a {@link Message}.
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder(builderMethodName = "newMessageInfo")
@Jacksonized
@Accessors(fluent = true)
public final class MessageInfo implements Info, MessageMetadataProvider, JacksonProvider {
    /**
     * The MessageKey of this message
     */
    @ProtobufProperty(index = 1, type = MESSAGE, implementation = MessageKey.class)
    @NonNull
    private MessageKey key;

    /**
     * The container of this message
     */
    @ProtobufProperty(index = 2, type = MESSAGE, implementation = MessageContainer.class)
    @NonNull
    @Default
    private MessageContainer message = new MessageContainer();

    /**
     * The timestamp, that is the seconds since {@link java.time.Instant#EPOCH}, when this message was sent
     */
    @ProtobufProperty(index = 3, type = UINT64)
    private long timestamp;

    /**
     * The global status of this message.
     * If the chat associated with this message is a group it is guaranteed that this field is equal or lower hierarchically then every value stored by {@link MessageInfo#individualStatus()}.
     * Otherwise, this field is guaranteed to be equal to the single value stored by {@link MessageInfo#individualStatus()} for the contact associated with the chat associated with this message.
     */
    @ProtobufProperty(index = 4, type = MESSAGE, implementation = MessageStatus.class)
    @NonNull
    @Default
    private MessageStatus status = MessageStatus.PENDING;

    /**
     * A map that holds the read status of this message for each participant.
     * If the chat associated with this chat is not a group, this map's size will always be 1.
     * In this case it is guaranteed that the value stored in this map for the contact associated with this chat equals {@link MessageInfo#status()}.
     * Otherwise, it is guaranteed to have a size of participants - 1.
     * In this case it is guaranteed that every value stored in this map for each participant of this chat is equal or higher hierarchically then {@link MessageInfo#status()}.
     * It is important to remember that it is guaranteed that every participant will be present as a key.
     */
    @NonNull
    @Default
    private Map<ContactJid, MessageStatus> individualStatus = new ConcurrentHashMap<>();

    /**
     * The jid of the sender
     */
    @ProtobufProperty(index = 5, type = STRING, implementation = ContactJid.class)
    @Setter(AccessLevel.NONE)
    private ContactJid senderJid;

    private Contact sender;

    /**
     * Message C2 timestamp
     */
    @ProtobufProperty(index = 6, type = UINT64)
    private long messageC2STimestamp;

    /**
     * Whether this message should be ignored or counted as an unread message
     */
    @ProtobufProperty(index = 16, type = BOOL)
    private boolean ignore;

    /**
     * Whether this message is starred
     */
    @ProtobufProperty(index = 17, type = BOOL)
    private boolean starred;

    /**
     * Whether this message was sent using a broadcast list
     */
    @ProtobufProperty(index = 18, type = BOOL)
    private boolean broadcast;

    /**
     * Push name
     */
    @ProtobufProperty(index = 19, type = STRING)
    private String pushName;

    /**
     * Media Cipher Text SHA256
     */
    @ProtobufProperty(index = 20, type = BYTES)
    private byte[] mediaCiphertextSha256;

    /**
     * Multicast
     */
    @ProtobufProperty(index = 21, type = BOOL)
    private boolean multicast;

    /**
     * Url text
     */
    @ProtobufProperty(index = 22, type = BOOL)
    private boolean urlText;

    /**
     * Url number
     */
    @ProtobufProperty(index = 23, type = BOOL)
    private boolean urlNumber;

    /**
     * The stub type of this message.
     * This property is populated only if the message that {@link MessageInfo#message} wraps is a {@link ProtocolMessage}.
     */
    @ProtobufProperty(index = 24, type = MESSAGE, implementation = StubType.class)
    private StubType stubType;

    /**
     * Clear media
     */
    @ProtobufProperty(index = 25, type = BOOL)
    private boolean clearMedia;

    /**
     * Message stub parameters
     */
    @ProtobufProperty(index = 26, type = STRING, repeated = true)
    private List<String> stubParameters;

    /**
     * Duration
     */
    @ProtobufProperty(index = 27, type = UINT32)
    private int duration;

    /**
     * Labels
     */
    @ProtobufProperty(index = 28, type = STRING, repeated = true)
    private List<String> labels;

    /**
     * PaymentInfo
     */
    @ProtobufProperty(index = 29, type = MESSAGE, implementation = PaymentInfo.class)
    private PaymentInfo paymentInfo;

    /**
     * Final live location
     */
    @ProtobufProperty(index = 30, type = MESSAGE, implementation = LiveLocationMessage.class)
    private LiveLocationMessage finalLiveLocation;

    /**
     * Quoted payment info
     */
    @ProtobufProperty(index = 31, type = MESSAGE, implementation = PaymentInfo.class)
    private PaymentInfo quotedPaymentInfo;

    /**
     * Ephemeral start timestamp
     */
    @ProtobufProperty(index = 32, type = UINT64)
    private long ephemeralStartTimestamp;

    /**
     * Ephemeral duration
     */
    @ProtobufProperty(index = 33, type = UINT32)
    private int ephemeralDuration;

    /**
     * Enable ephemeral
     */
    @ProtobufProperty(index = 34, type = BOOL)
    private boolean enableEphemeral;

    /**
     * Ephemeral out of sync
     */
    @ProtobufProperty(index = 35, type = BOOL)
    private boolean ephemeralOutOfSync;

    /**
     * Business privacy status
     */
    @ProtobufProperty(index = 36, type = MESSAGE, implementation = BusinessPrivacyStatus.class)
    private BusinessPrivacyStatus businessPrivacyStatus;

    /**
     * Business verified name
     */
    @ProtobufProperty(index = 37, type = STRING)
    private String businessVerifiedName;

    /**
     * Media data
     */
    @ProtobufProperty(index = 38, type = MESSAGE, implementation = MediaData.class)
    private MediaData mediaData;

    /**
     * Photo change
     */
    @ProtobufProperty(index = 39, type = MESSAGE, implementation = PhotoChange.class)
    private PhotoChange photoChange;

    /**
     * Message receipt
     */
    @ProtobufProperty(index = 40, type = MESSAGE, implementation = MessageReceipt.class)
    private MessageReceipt receipt;

    /**
     * Reactions
     */
    @ProtobufProperty(index = 41, type = MESSAGE, implementation = ReactionMessage.class, repeated = true)
    private List<ReactionMessage> reactions;

    /**
     * Media data
     */
    @ProtobufProperty(index = 42, type = MESSAGE, implementation = MediaData.class)
    private MediaData quotedStickerData;

    /**
     * Upcoming data
     */
    @ProtobufProperty(index = 43, type = BYTES)
    private String futureProofData;

    /**
     * Public service announcement status
     */
    @ProtobufProperty(index = 44, type = MESSAGE, implementation = PublicServiceAnnouncementStatus.class)
    private PublicServiceAnnouncementStatus psaStatus;

    /**
     * Constructs a new MessageInfo from a MessageKey and a MessageContainer
     *
     * @param key       the key of the message
     * @param container the container of the message
     */
    public MessageInfo(@NonNull MessageKey key, @NonNull MessageContainer container) {
        this.key = key;
        this.timestamp = Clock.now();
        this.status = MessageStatus.PENDING;
        this.message = container;
        this.individualStatus = new ConcurrentHashMap<>();
    }

    /**
     * Returns the jid of the contact or group that sent the message.
     *
     * @return a non-null ContactJid
     */
    public ContactJid chatJid() {
        return key.chatJid();
    }

    /**
     * Determines whether the message was sent by you or by someone else
     *
     * @return a boolean
     */
    public boolean fromMe() {
        return key.fromMe();
    }

    /**
     * Returns the id of the message
     *
     * @return a non-null String
     */
    public String id() {
        return key.id();
    }

    /**
     * Returns the jid of the sender
     *
     * @return a non-null ContactJid
     */
    public ContactJid senderJid() {
        return requireNonNullElseGet(senderJid, () -> key.senderJid()
                .orElseGet(key::chatJid));
    }

    /**
     * Returns the name of the chat where this message is or its pretty jid
     *
     * @return a non-null String
     */
    public String chatName() {
        return chat().name();
    }

    /**
     * Returns the name of the person that sent this message or its pretty jid
     *
     * @return a non-null String
     */
    public String senderName() {
        return sender().map(Contact::name)
                .orElseGet(senderJid()::user);
    }

    /**
     * Returns the chat where the message was sent
     *
     * @return a chat
     */
    public Chat chat() {
        return key.chat();
    }

    /**
     * Returns the contact that sent the message
     *
     * @return an optional
     */
    public Optional<Contact> sender() {
        return Optional.ofNullable(sender)
                .or(key::sender);
    }

    /**
     * Returns the message quoted by this message
     *
     * @return a non-empty optional {@link MessageInfo} if this message quotes a message in memory
     */
    public Optional<QuotedMessage> quotedMessage() {
        return Optional.of(message)
                .flatMap(MessageContainer::contentWithContext)
                .map(ContextualMessage::contextInfo)
                .flatMap(QuotedMessage::of);
    }

    /**
     * Converts this message to a json.
     * Useful when debugging.
     *
     * @return a non-null string
     */
    public String toJson(){
        try {
            return JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(this);
        }catch (IOException exception){
            throw new UncheckedIOException("Cannot convert message to json", exception);
        }
    }

    /**
     * Checks whether this message wraps a stub type
     *
     * @return true if this message wraps a stub type
     */
    public boolean hasStub() {
        return stubType != null;
    }

    public boolean equals(Object object) {
        return object instanceof MessageInfo that && Objects.equals(this.id(), that.id());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id());
    }

    /**
     * The constants of this enumerated type describe the various types of server message that a {@link MessageInfo} can describe
     */
    @AllArgsConstructor
    @Accessors(fluent = true)
    public enum StubType {
        UNKNOWN(0, List.of("unknown")),
        REVOKE(1, List.of("revoked")),
        CIPHERTEXT(2, List.of("ciphertext")),
        FUTUREPROOF(3, List.of("phone")),
        NON_VERIFIED_TRANSITION(4, List.of("non_verified_transition")),
        UNVERIFIED_TRANSITION(5, List.of("unverified_transition")),
        VERIFIED_TRANSITION(6, List.of("verified_transition")),
        VERIFIED_LOW_UNKNOWN(7, List.of("verified_low_unknown")),
        VERIFIED_HIGH(8, List.of("verified_high")),
        VERIFIED_INITIAL_UNKNOWN(9, List.of("verified_initial_unknown")),
        VERIFIED_INITIAL_LOW(10, List.of("verified_initial_low")),
        VERIFIED_INITIAL_HIGH(11, List.of("verified_initial_high")),
        VERIFIED_TRANSITION_ANY_TO_NONE(12, List.of("verified_transition_any_to_none")),
        VERIFIED_TRANSITION_ANY_TO_HIGH(13, List.of("verified_transition_any_to_high")),
        VERIFIED_TRANSITION_HIGH_TO_LOW(14, List.of("verified_transition_high_to_low")),
        VERIFIED_TRANSITION_HIGH_TO_UNKNOWN(15, List.of("verified_transition_high_to_unknown")),
        VERIFIED_TRANSITION_UNKNOWN_TO_LOW(16, List.of("verified_transition_unknown_to_low")),
        VERIFIED_TRANSITION_LOW_TO_UNKNOWN(17, List.of("verified_transition_low_to_unknown")),
        VERIFIED_TRANSITION_NONE_TO_LOW(18, List.of("verified_transition_none_to_low")),
        VERIFIED_TRANSITION_NONE_TO_UNKNOWN(19, List.of("verified_transition_none_to_unknown")),
        GROUP_CREATE(20, List.of("create")),
        GROUP_CHANGE_SUBJECT(21, List.of("subject")),
        GROUP_CHANGE_ICON(22, List.of("picture")),
        GROUP_CHANGE_INVITE_LINK(23, List.of("revoke_invite")),
        GROUP_CHANGE_DESCRIPTION(24, List.of("description")),
        GROUP_CHANGE_RESTRICT(25, List.of("restrict", "locked", "unlocked")),
        GROUP_CHANGE_ANNOUNCE(26, List.of("announce", "announcement", "not_announcement")),
        GROUP_PARTICIPANT_ADD(27, List.of("add")),
        GROUP_PARTICIPANT_REMOVE(28, List.of("remove")),
        GROUP_PARTICIPANT_PROMOTE(29, List.of("promote")),
        GROUP_PARTICIPANT_DEMOTE(30, List.of("demote")),
        GROUP_PARTICIPANT_INVITE(31, List.of("invite")),
        GROUP_PARTICIPANT_LEAVE(32, List.of("leave")),
        GROUP_PARTICIPANT_CHANGE_NUMBER(33, List.of("modify")),
        BROADCAST_CREATE(34, List.of("create")),
        BROADCAST_ADD(35, List.of("add")),
        BROADCAST_REMOVE(36, List.of("remove")),
        GENERIC_NOTIFICATION(37, List.of("notification")),
        E2E_IDENTITY_CHANGED(38, List.of("identity")),
        E2E_ENCRYPTED(39, List.of("encrypt")),
        CALL_MISSED_VOICE(40, List.of("miss")),
        CALL_MISSED_VIDEO(41, List.of("miss_video")),
        INDIVIDUAL_CHANGE_NUMBER(42, List.of("change_number")),
        GROUP_DELETE(43, List.of("delete")),
        GROUP_ANNOUNCE_MODE_MESSAGE_BOUNCE(44, List.of("announce_msg_bounce")),
        CALL_MISSED_GROUP_VOICE(45, List.of("miss_group")),
        CALL_MISSED_GROUP_VIDEO(46, List.of("miss_group_video")),
        PAYMENT_CIPHERTEXT(47, List.of("ciphertext")),
        PAYMENT_FUTUREPROOF(48, List.of("futureproof")),
        PAYMENT_TRANSACTION_STATUS_UPDATE_FAILED(49, List.of("payment_transaction_status_update_failed")),
        PAYMENT_TRANSACTION_STATUS_UPDATE_REFUNDED(50, List.of("payment_transaction_status_update_refunded")),
        PAYMENT_TRANSACTION_STATUS_UPDATE_REFUND_FAILED(51, List.of("payment_transaction_status_update_refund_failed")),
        PAYMENT_TRANSACTION_STATUS_RECEIVER_PENDING_SETUP(52,
                List.of("payment_transaction_status_receiver_pending_setup")),
        PAYMENT_TRANSACTION_STATUS_RECEIVER_SUCCESS_AFTER_HICCUP(53,
                List.of("payment_transaction_status_receiver_success_after_hiccup")),
        PAYMENT_ACTION_ACCOUNT_SETUP_REMINDER(54, List.of("payment_action_account_setup_reminder")),
        PAYMENT_ACTION_SEND_PAYMENT_REMINDER(55, List.of("payment_action_send_payment_reminder")),
        PAYMENT_ACTION_SEND_PAYMENT_INVITATION(56, List.of("payment_action_send_payment_invitation")),
        PAYMENT_ACTION_REQUEST_DECLINED(57, List.of("payment_action_request_declined")),
        PAYMENT_ACTION_REQUEST_EXPIRED(58, List.of("payment_action_request_expired")),
        PAYMENT_ACTION_REQUEST_CANCELLED(59, List.of("payment_transaction_request_cancelled")),
        BIZ_VERIFIED_TRANSITION_TOP_TO_BOTTOM(60, List.of("biz_verified_transition_top_to_bottom")),
        BIZ_VERIFIED_TRANSITION_BOTTOM_TO_TOP(61, List.of("biz_verified_transition_bottom_to_top")),
        BIZ_INTRO_TOP(62, List.of("biz_intro_top")),
        BIZ_INTRO_BOTTOM(63, List.of("biz_intro_bottom")),
        BIZ_NAME_CHANGE(64, List.of("biz_name_change")),
        BIZ_MOVE_TO_CONSUMER_APP(65, List.of("biz_move_to_consumer_app")),
        BIZ_TWO_TIER_MIGRATION_TOP(66, List.of("biz_two_tier_migration_top")),
        BIZ_TWO_TIER_MIGRATION_BOTTOM(67, List.of("biz_two_tier_migration_bottom")),
        OVERSIZED(68, List.of("oversized")),
        GROUP_CHANGE_NO_FREQUENTLY_FORWARDED(69, List.of("frequently_forwarded_ok", "no_frequently_forwarded")),
        GROUP_V4_ADD_INVITE_SENT(70, List.of("v4_add_invite_sent")),
        GROUP_PARTICIPANT_ADD_REQUEST_JOIN(71, List.of("v4_add_invite_join")),
        CHANGE_EPHEMERAL_SETTING(72, List.of("ephemeral", "not_ephemeral")),
        E2E_DEVICE_CHANGED(73, List.of("device")),
        VIEWED_ONCE(74, List.of()),
        E2E_ENCRYPTED_NOW(75, List.of("encrypt_now")),
        BLUE_MSG_BSP_FB_TO_BSP_PREMISE(76, List.of("blue_msg_bsp_fb_to_bsp_premise")),
        BLUE_MSG_BSP_FB_TO_SELF_FB(77, List.of("blue_msg_bsp_fb_to_self_fb")),
        BLUE_MSG_BSP_FB_TO_SELF_PREMISE(78, List.of("blue_msg_bsp_fb_to_self_premise")),
        BLUE_MSG_BSP_FB_UNVERIFIED(79, List.of("blue_msg_bsp_fb_unverified")),
        BLUE_MSG_BSP_FB_UNVERIFIED_TO_SELF_PREMISE_VERIFIED(80,
                List.of("blue_msg_bsp_fb_unverified_to_self_premise_verified")),
        BLUE_MSG_BSP_FB_VERIFIED(81, List.of("blue_msg_bsp_fb_verified")),
        BLUE_MSG_BSP_FB_VERIFIED_TO_SELF_PREMISE_UNVERIFIED(82,
                List.of("blue_msg_bsp_fb_verified_to_self_premise_unverified")),
        BLUE_MSG_BSP_PREMISE_TO_SELF_PREMISE(83, List.of("blue_msg_bsp_premise_to_self_premise")),
        BLUE_MSG_BSP_PREMISE_UNVERIFIED(84, List.of("blue_msg_bsp_premise_unverified")),
        BLUE_MSG_BSP_PREMISE_UNVERIFIED_TO_SELF_PREMISE_VERIFIED(85,
                List.of("blue_msg_bsp_premise_unverified_to_self_premise_verified")),
        BLUE_MSG_BSP_PREMISE_VERIFIED(86, List.of("blue_msg_bsp_premise_verified")),
        BLUE_MSG_BSP_PREMISE_VERIFIED_TO_SELF_PREMISE_UNVERIFIED(87,
                List.of("blue_msg_bsp_premise_verified_to_self_premise_unverified")),
        BLUE_MSG_CONSUMER_TO_BSP_FB_UNVERIFIED(88, List.of("blue_msg_consumer_to_bsp_fb_unverified")),
        BLUE_MSG_CONSUMER_TO_BSP_PREMISE_UNVERIFIED(89, List.of("blue_msg_consumer_to_bsp_premise_unverified")),
        BLUE_MSG_CONSUMER_TO_SELF_FB_UNVERIFIED(90, List.of("blue_msg_consumer_to_self_fb_unverified")),
        BLUE_MSG_CONSUMER_TO_SELF_PREMISE_UNVERIFIED(91, List.of("blue_msg_consumer_to_self_premise_unverified")),
        BLUE_MSG_SELF_FB_TO_BSP_PREMISE(92, List.of("blue_msg_self_fb_to_bsp_premise")),
        BLUE_MSG_SELF_FB_TO_SELF_PREMISE(93, List.of("blue_msg_self_fb_to_self_premise")),
        BLUE_MSG_SELF_FB_UNVERIFIED(94, List.of("blue_msg_self_fb_unverified")),
        BLUE_MSG_SELF_FB_UNVERIFIED_TO_SELF_PREMISE_VERIFIED(95,
                List.of("blue_msg_self_fb_unverified_to_self_premise_verified")),
        BLUE_MSG_SELF_FB_VERIFIED(96, List.of("blue_msg_self_fb_verified")),
        BLUE_MSG_SELF_FB_VERIFIED_TO_SELF_PREMISE_UNVERIFIED(97,
                List.of("blue_msg_self_fb_verified_to_self_premise_unverified")),
        BLUE_MSG_SELF_PREMISE_TO_BSP_PREMISE(98, List.of("blue_msg_self_premise_to_bsp_premise")),
        BLUE_MSG_SELF_PREMISE_UNVERIFIED(99, List.of("blue_msg_self_premise_unverified")),
        BLUE_MSG_SELF_PREMISE_VERIFIED(100, List.of("blue_msg_self_premise_verified")),
        BLUE_MSG_TO_BSP_FB(101, List.of("blue_msg_to_bsp_fb")),
        BLUE_MSG_TO_CONSUMER(102, List.of("blue_msg_to_consumer")),
        BLUE_MSG_TO_SELF_FB(103, List.of("blue_msg_to_self_fb")),
        BLUE_MSG_UNVERIFIED_TO_BSP_FB_VERIFIED(104, List.of("blue_msg_unverified_to_bsp_fb_verified")),
        BLUE_MSG_UNVERIFIED_TO_BSP_PREMISE_VERIFIED(105, List.of("blue_msg_unverified_to_bsp_premise_verified")),
        BLUE_MSG_UNVERIFIED_TO_SELF_FB_VERIFIED(106, List.of("blue_msg_unverified_to_self_fb_verified")),
        BLUE_MSG_UNVERIFIED_TO_VERIFIED(107, List.of("blue_msg_unverified_to_verified")),
        BLUE_MSG_VERIFIED_TO_BSP_FB_UNVERIFIED(108, List.of("blue_msg_verified_to_bsp_fb_unverified")),
        BLUE_MSG_VERIFIED_TO_BSP_PREMISE_UNVERIFIED(109, List.of("blue_msg_verified_to_bsp_premise_unverified")),
        BLUE_MSG_VERIFIED_TO_SELF_FB_UNVERIFIED(110, List.of("blue_msg_verified_to_self_fb_unverified")),
        BLUE_MSG_VERIFIED_TO_UNVERIFIED(111, List.of("blue_msg_verified_to_unverified")),
        BLUE_MSG_BSP_FB_UNVERIFIED_TO_BSP_PREMISE_VERIFIED(112,
                List.of("blue_msg_bsp_fb_unverified_to_bsp_premise_verified")),
        BLUE_MSG_BSP_FB_UNVERIFIED_TO_SELF_FB_VERIFIED(113, List.of("blue_msg_bsp_fb_unverified_to_self_fb_verified")),
        BLUE_MSG_BSP_FB_VERIFIED_TO_BSP_PREMISE_UNVERIFIED(114,
                List.of("blue_msg_bsp_fb_verified_to_bsp_premise_unverified")),
        BLUE_MSG_BSP_FB_VERIFIED_TO_SELF_FB_UNVERIFIED(115, List.of("blue_msg_bsp_fb_verified_to_self_fb_unverified")),
        BLUE_MSG_SELF_FB_UNVERIFIED_TO_BSP_PREMISE_VERIFIED(116,
                List.of("blue_msg_self_fb_unverified_to_bsp_premise_verified")),
        BLUE_MSG_SELF_FB_VERIFIED_TO_BSP_PREMISE_UNVERIFIED(117,
                List.of("blue_msg_self_fb_verified_to_bsp_premise_unverified")),
        E2E_IDENTITY_UNAVAILABLE(118, List.of("e2e_identity_unavailable")),
        GROUP_CREATING(119, List.of()),
        GROUP_CREATE_FAILED(120, List.of()),
        GROUP_BOUNCED(121, List.of()),
        BLOCK_CONTACT(122, List.of("block_contact")),
        EPHEMERAL_SETTING_NOT_APPLIED(123, List.of()),
        SYNC_FAILED(124, List.of()),
        SYNCING(125, List.of()),
        BIZ_PRIVACY_MODE_INIT_FB(126, List.of("biz_privacy_mode_init_fb")),
        BIZ_PRIVACY_MODE_INIT_BSP(127, List.of("biz_privacy_mode_init_bsp")),
        BIZ_PRIVACY_MODE_TO_FB(128, List.of("biz_privacy_mode_to_fb")),
        BIZ_PRIVACY_MODE_TO_BSP(129, List.of("biz_privacy_mode_to_bsp")),
        DISAPPEARING_MODE(130, List.of("disappearing_mode")),
        E2E_DEVICE_FETCH_FAILED(131, List.of()),
        ADMIN_REVOKE(132, List.of("admin")),
        GROUP_INVITE_LINK_GROWTH_LOCKED(133, List.of("growth_locked", "growth_unlocked")),
        COMMUNITY_LINK_PARENT_GROUP(134, List.of("parent_group_link")),
        COMMUNITY_LINK_SIBLING_GROUP(135, List.of("sibling_group_link")),
        COMMUNITY_LINK_SUB_GROUP(136, List.of("sub_group_link")),
        COMMUNITY_UNLINK_PARENT_GROUP(137, List.of("parent_group_unlink")),
        COMMUNITY_UNLINK_SIBLING_GROUP(138, List.of("sibling_group_unlink")),
        COMMUNITY_UNLINK_SUB_GROUP(139, List.of("sub_group_unlink")),
        GROUP_PARTICIPANT_ACCEPT(140, List.of()),
        GROUP_PARTICIPANT_LINKED_GROUP_JOIN(141, List.of("linked_group_join")),
        COMMUNITY_CREATE(142, List.of("community_create"));

        // LINK: "link",
        // UNLINK: "unlink",
        // LINKED_GROUP_PROMOTE: "linked_group_promote",
        // LINKED_GROUP_DEMOTE: "linked_group_demote",
        // MEMBERSHIP_APPROVAL_MODE: "membership_approval_mode",
        // MEMBERSHIP_APPROVAL_REQUEST: "membership_approval_request"
        // SUSPENDED: "suspended",
        // UNSUSPENDED: "unsuspended"

        @Getter
        private final int index;

        @Getter
        private final List<String> symbols;

        @JsonCreator
        public static StubType of(int index) {
            return Arrays.stream(values())
                    .filter(entry -> entry.index() == index)
                    .findFirst()
                    .orElse(null);
        }

        public static Optional<StubType> of(String symbol) {
            return Arrays.stream(values())
                    .filter(entry -> entry.symbols()
                            .contains(symbol))
                    .findFirst();
        }
    }
}
