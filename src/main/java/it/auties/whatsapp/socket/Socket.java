package it.auties.whatsapp.socket;

import it.auties.protobuf.decoder.ProtobufDecoder;
import it.auties.protobuf.encoder.ProtobufEncoder;
import it.auties.whatsapp.api.WhatsappListener;
import it.auties.whatsapp.api.WhatsappOptions;
import it.auties.whatsapp.binary.BinaryArray;
import it.auties.whatsapp.binary.BinaryMessage;
import it.auties.whatsapp.crypto.*;
import it.auties.whatsapp.manager.WhatsappKeys;
import it.auties.whatsapp.manager.WhatsappStore;
import it.auties.whatsapp.protobuf.action.*;
import it.auties.whatsapp.protobuf.chat.Chat;
import it.auties.whatsapp.protobuf.chat.ChatMute;
import it.auties.whatsapp.protobuf.contact.Contact;
import it.auties.whatsapp.protobuf.contact.ContactJid;
import it.auties.whatsapp.protobuf.info.MessageInfo;
import it.auties.whatsapp.protobuf.media.MediaConnection;
import it.auties.whatsapp.protobuf.message.device.DeviceSentMessage;
import it.auties.whatsapp.protobuf.message.model.MediaMessage;
import it.auties.whatsapp.protobuf.message.model.MessageContainer;
import it.auties.whatsapp.protobuf.message.model.MessageKey;
import it.auties.whatsapp.protobuf.message.server.ProtocolMessage;
import it.auties.whatsapp.protobuf.message.server.SenderKeyDistributionMessage;
import it.auties.whatsapp.protobuf.setting.EphemeralSetting;
import it.auties.whatsapp.protobuf.signal.auth.*;
import it.auties.whatsapp.protobuf.signal.keypair.SignalPreKeyPair;
import it.auties.whatsapp.protobuf.signal.message.SignalDistributionMessage;
import it.auties.whatsapp.protobuf.signal.message.SignalMessage;
import it.auties.whatsapp.protobuf.signal.message.SignalPreKeyMessage;
import it.auties.whatsapp.protobuf.signal.sender.SenderKeyName;
import it.auties.whatsapp.protobuf.sync.*;
import it.auties.whatsapp.util.*;
import jakarta.websocket.*;
import jakarta.websocket.ClientEndpointConfig.Configurator;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;
import lombok.extern.java.Log;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static it.auties.protobuf.encoder.ProtobufEncoder.encode;
import static it.auties.whatsapp.binary.BinaryArray.ofBase64;
import static it.auties.whatsapp.socket.Node.*;
import static it.auties.whatsapp.util.QrHandler.TERMINAL;
import static jakarta.websocket.ContainerProvider.getWebSocketContainer;
import static java.lang.Long.parseLong;
import static java.time.Instant.now;
import static java.util.Arrays.copyOfRange;
import static java.util.Map.of;
import static java.util.Objects.requireNonNullElse;

@Accessors(fluent = true)
@ClientEndpoint(configurator = Socket.OriginPatcher.class)
@Log
public class Socket {
    private static final String BUILD_HASH = "S9Kdc4pc4EJryo21snc5cg==";
    private static final int KEY_TYPE = 5;

    @Getter(onMethod = @__(@NonNull))
    @Setter(onParam = @__(@NonNull))
    private Session session;

    private boolean loggedIn;

    private ScheduledExecutorService pingService;

    @NonNull
    private final Handshake handshake;

    @NonNull
    private final WebSocketContainer container;

    @NonNull
    private final WhatsappOptions options;

    @NonNull
    private final AuthHandler authHandler;

    @NonNull
    private final StreamHandler streamHandler;

    @NonNull
    private final MessageHandler messageHandler;

    @NonNull
    private final AppStateHandler appStateHandler;

    @Getter
    @NonNull
    private WhatsappKeys keys;

    @Getter
    @NonNull
    private WhatsappStore store;

    @NonNull
    private CountDownLatch lock;

    static {
        getWebSocketContainer().setDefaultMaxSessionIdleTimeout(0);
    }

    public Socket(@NonNull WhatsappOptions options, @NonNull WhatsappStore store, @NonNull WhatsappKeys keys) {
        this.pingService = Executors.newSingleThreadScheduledExecutor();
        this.handshake = new Handshake();
        this.container = getWebSocketContainer();
        this.options = options;
        this.store = store;
        this.keys = keys;
        this.authHandler = new AuthHandler();
        this.streamHandler = new StreamHandler();
        this.messageHandler = new MessageHandler();
        this.appStateHandler = new AppStateHandler();
        this.lock = new CountDownLatch(1);
    }

    @OnOpen
    @SneakyThrows
    public void onOpen(@NonNull Session session) {
        session(session);
        if(loggedIn){
            return;
        }

        handshake.start(keys);
        handshake.updateHash(keys.ephemeralKeyPair().publicKey());
        var clientHello = new ClientHello(keys.ephemeralKeyPair().publicKey());
        var handshakeMessage = new HandshakeMessage(clientHello);
        Request.with(handshakeMessage)
                .sendWithPrologue(session(), keys, store);
    }

    @OnMessage
    @SneakyThrows
    public void onBinary(byte @NonNull [] raw) {
        var message = new BinaryMessage(raw);
        if(message.decoded().isEmpty()){
            return;
        }

        var header = message.decoded().getFirst();
        if(!loggedIn){
            authHandler.sendUserPayload(header.data());
            return;
        }

        var nodes = message.toNodes(keys);
        System.out.printf("Received %s nodes%n", nodes.size());
        nodes.forEach(this::handleNode);
    }

    private void handleNode(Node deciphered) {
        System.out.printf("Received: %s%n", deciphered);
        if(store.resolvePendingRequest(deciphered, false)){
            return;
        }

        streamHandler.digest(deciphered);
    }

    public void connect() {
        try{
            container.connectToServer(this, URI.create(options.whatsappUrl()));
            lock.await();
        }catch (IOException | DeploymentException | InterruptedException exception){
            throw new RuntimeException("Cannot connect to WhatsappWeb's WebServer", exception);
        }
    }

    public void reconnect(){
        disconnect();
        connect();
    }

    public void disconnect(){
        try{
            changeState(false);
            session.close();
        }catch (IOException exception){
            throw new RuntimeException("Cannot close connection to WhatsappWeb's WebServer", exception);
        }
    }

    public void logout(){
        if (keys.hasCompanion()) {
            var metadata = of("jid", keys.companion(), "reason", "user_initiated");
            var device = withAttributes("remove-companion-device", metadata);
            sendQuery("set", "md", device);
        }

        changeKeys();
    }

    private void changeState(boolean loggedIn){
        this.loggedIn = loggedIn;
        this.lock = new CountDownLatch(1);
        keys.clear();
    }

    @OnClose
    public void onClose(){
        System.out.println("Closed");
        if(loggedIn) {
            store.callListeners(listener -> listener.onDisconnected(true));
            reconnect();
            return;
        }

        store.callListeners(listener -> listener.onDisconnected(false));
        store.dispose();
        dispose();
    }

    @OnError
    public void onError(Throwable throwable){
        throwable.printStackTrace();
    }

    public CompletableFuture<Node> send(Node node){
        return node.toRequest(store.nextTag())
                .send(session(), keys, store);
    }

    public CompletableFuture<Node> sendWithNoResponse(Node node){
        return node.toRequest(store.nextTag())
                .sendWithNoResponse(session(), keys, store);
    }

    public CompletableFuture<Node> sendQuery(String method, String category, Node... body){
        return sendQuery(null, ContactJid.SOCKET, method, category, null, body);
    }

    public CompletableFuture<Node> sendQuery(String method, String category, Map<String, Object> metadata, Node... body){
        return sendQuery(null, ContactJid.SOCKET, method, category, metadata, body);
    }

    public CompletableFuture<Node> sendQuery(ContactJid to, String method, String category, Node... body){
        return sendQuery(null, to, method, category, null, body);
    }

    public CompletableFuture<Node> sendQuery(String id, ContactJid to, String method, String category, Map<String, Object> metadata, Node... body){
        var attributes = new HashMap<String, Object>();
        if(id != null){
            attributes.put("id", id);
        }

        attributes.put("type", method);
        attributes.put("to", to);
        if(category != null) {
            attributes.put("xmlns", category);
        }

        if(metadata != null){
            attributes.putAll(metadata);
        }

        return send(withChildren("iq", attributes, body));
    }

    public CompletableFuture<List<Node>> sendQuery(Node queryNode, Node... queryBody) {
        var query = withChildren("query", queryNode);
        var list = withChildren("list", queryBody);
        var sync = withChildren("usync",
                of("sid", store.nextTag(), "mode", "query", "last", "true", "index", "0", "context", "interactive"),
                query, list);
        return sendQuery("get", "usync", sync)
                .thenApplyAsync(this::parseQueryResult);
    }

    private List<Node> parseQueryResult(Node result) {
        return result.findNodes("usync")
                .stream()
                .map(node -> node.findNode("list"))
                .map(node -> node.findNodes("user"))
                .flatMap(Collection::stream)
                .toList();
    }

    private void sendReceipt(ContactJid jid, ContactJid participant, List<String> messages, String type) {
        if(messages.isEmpty()){
            return;
        }

        var receipt = withChildren("receipt",
                of("id", messages.get(0), "t", now().toEpochMilli(), "to", jid),
                toMessagesNode(messages));
        if(type != null){
            receipt.attributes().put("type", type);
        }

        if(participant != null && !Objects.equals(jid, participant)){
            receipt.attributes().put("participant", participant);
        }

        send(receipt);
    }

    private Node[] toMessagesNode(List<String> messages) {
        if (messages.size() <= 1) {
            return null;
        }

        return messages.subList(1, messages.size())
                .stream()
                .map(id -> withAttributes("item", of("id", id)))
                .toArray(Node[]::new);
    }

    private void sendMessageAck(Node node, Map<String, Object> attributes){
        var to = node.attributes().getJid("from")
                .orElseThrow();
        var receipt = withAttributes("ack",
                of("id", node.id(), "to", to));
        receipt.attributes().putAll(attributes);
        var participant = node.attributes()
                .getString("participant", null);
        if(participant != null){
            node.attributes().put("participant", participant);
        }

        send(receipt);
    }

    private void changeKeys() {
        keys.delete();
        var newId = SignalHelper.randomRegistrationId();
        this.keys = WhatsappKeys.newKeys(newId);
        var newStore = WhatsappStore.newStore(newId);
        store.listeners().addAll(store.listeners());
        this.store = newStore;
    }

    private void dispose(){
        pingService.shutdownNow();
        store.save();
        keys.save();
    }

    public static class OriginPatcher extends Configurator{
        @Override
        public void beforeRequest(@NonNull Map<String, List<String>> headers) {
            headers.put("Origin", List.of("https://web.whatsapp.com"));
            headers.put("Host", List.of("web.whatsapp.com"));
        }
    }

    private class AuthHandler {
        @SneakyThrows
        private void sendUserPayload(byte[] message) {
            var serverHello = ProtobufDecoder.forType(HandshakeMessage.class)
                    .decode(message)
                    .serverHello();
            handshake.updateHash(serverHello.ephemeral());
            var sharedEphemeral = Curve.calculateAgreement(serverHello.ephemeral(), keys.ephemeralKeyPair().privateKey());
            handshake.mixIntoKey(sharedEphemeral.data());

            var decodedStaticText = handshake.cipher(serverHello.staticText(), false);
            var sharedStatic = Curve.calculateAgreement(decodedStaticText, keys.ephemeralKeyPair().privateKey());
            handshake.mixIntoKey(sharedStatic.data());
            handshake.cipher(serverHello.payload(), false);

            var encodedKey = handshake.cipher(keys.companionKeyPair().publicKey(), true);
            var sharedPrivate = Curve.calculateAgreement(serverHello.ephemeral(), keys.companionKeyPair().privateKey());
            handshake.mixIntoKey(sharedPrivate.data());

            var encodedPayload = handshake.cipher(createUserPayload(), true);
            var clientFinish = new ClientFinish(encodedKey, encodedPayload);
            var handshakeMessage = new HandshakeMessage(clientFinish);
            Request.with(handshakeMessage)
                    .sendWithNoResponse(session(), keys, store)
                    .thenRunAsync(() -> changeState(true))
                    .thenRunAsync(handshake::finish);
        }

        private byte[] createUserPayload() {
            var builder = ClientPayload.builder()
                    .connectReason(ClientPayload.ClientPayloadConnectReason.USER_ACTIVATED)
                    .connectType(ClientPayload.ClientPayloadConnectType.WIFI_UNKNOWN)
                    .userAgent(createUserAgent())
                    .passive(keys.hasCompanion())
                    .webInfo(new WebInfo(WebInfo.WebInfoWebSubPlatform.WEB_BROWSER));
            return encode(keys.hasCompanion() ? builder.username(parseLong(keys.companion().user())).device(keys.companion().device()).build()
                    : builder.regData(createRegisterData()).build());
        }

        private UserAgent createUserAgent() {
            return UserAgent.builder()
                    .appVersion(new Version(options.whatsappVersion()))
                    .platform(UserAgent.UserAgentPlatform.WEB)
                    .releaseChannel(UserAgent.UserAgentReleaseChannel.RELEASE)
                    .build();
        }

        private CompanionData createRegisterData() {
            return CompanionData.builder()
                    .buildHash(ofBase64(BUILD_HASH).data())
                    .companion(encode(createCompanionProps()))
                    .id(SignalHelper.toBytes(keys.id(), 4))
                    .keyType(SignalHelper.toBytes(KEY_TYPE, 1))
                    .identifier(keys.identityKeyPair().publicKey())
                    .signatureId(keys.signedKeyPair().encodedId())
                    .signaturePublicKey(keys.signedKeyPair().keyPair().publicKey())
                    .signature(keys.signedKeyPair().signature())
                    .build();
        }

        private Companion createCompanionProps() {
            return Companion.builder()
                    .os(options.description())
                    .platformType(Companion.CompanionPropsPlatformType.DESKTOP)
                    .build();
        }
    }

    private class StreamHandler {
        private void digest(@NonNull Node node) {
            switch (node.description()) {
                case "ack" -> digestAck(node);
                case "call" -> digestCall(node);
                case "failure" -> digestFailure(node);
                case "ib" -> digestIb(node);
                case "iq" -> digestIq(node);
                case "receipt" -> digestReceipt(node);
                case "stream:error" -> digestError(node);
                case "success" -> digestSuccess();
                case "message" -> digestMessage(node);
                case "notification" -> digestNotification(node);
                case "xmlstreamend" -> disconnect();
            }
        }

        private void digestReceipt(Node node) {
            var attributes = new HashMap<String, Object>();
            attributes.put("class", "receipt");
            var type = node.attributes()
                    .getString("type", null);
            if(type != null){
                attributes.put("type", type);
            }

            sendMessageAck(node, attributes);
        }

        private void digestCall(Node node) {
            var call = node.children()
                    .peekFirst();
            if(call == null){
                return;
            }

            sendMessageAck(node, of("class", "call", "type", call.description()));
        }

        private void digestAck(Node node) {
            var clazz = node.attributes().getString("class");
            if (!Objects.equals(clazz, "message")) {
                return;
            }

            var from = node.attributes().getJid("from")
                    .orElseThrow(() -> new NoSuchElementException("Cannot digest ack: missing from"));
            var receipt = with("ack",
                    of("class", "receipt", "id", node.id(), "from", from));
            send(receipt);
        }

        private void digestMessage(Node node) {
            var messages = messageHandler.decode(node);
            messages.forEach(message -> {
                sendMessageAck(node, of("class", "receipt"));
                sendReceipt(message.chatJid(), message.senderJid(),
                        List.of(message.key().id()), null);
                if (message.ignore()) {
                    return;
                }

                store.callListeners(listener -> listener.onNewMessage(message));
            });
        }

        private void digestNotification(Node node) {
            var type = node.attributes().getString("type", null);
            sendMessageAck(node, of("class", "notification", "type", type));
            if (!Objects.equals(type, "server_sync")) {
                return;
            }

            var update = node.findNode("collection");
            if (update == null) {
                return;
            }

            var patchName = update.attributes().getRequiredString("name");
            appStateHandler.sync(patchName);
        }

        private void digestIb(Node node) {
            var dirty = node.findNode("dirty");
            if(dirty == null){
                Validate.isTrue(!node.hasNode("downgrade_webclient"),
                        "Multi device beta is not enabled. Please enable it from Whatsapp");
                return;
            }

            var type = dirty.attributes().getString("type");
            if(!Objects.equals(type, "account_sync")){
                return;
            }

            var timestamp = dirty.attributes().getString("timestamp");
            sendQuery("set", "urn:xmpp:whatsapp:dirty",
                    withAttributes("clean", of("type", type, "timestamp", timestamp)));
        }

        private void digestFailure(Node node) {
            var statusCode = node.attributes().getLong("reason");
            var reason = node.attributes().getString("location");
            Validate.isTrue(handleFailure(statusCode, reason),
                    "Invalid or expired credentials: socket failed with status code %s at %s",
                    statusCode, reason);
            changeKeys();
            reconnect();
        }

        private boolean handleFailure(long statusCode, String reason) {
            return store.listeners()
                    .stream()
                    .allMatch(listener -> listener.onFailure(statusCode, reason));
        }

        private void digestError(Node node) {
            var statusCode = node.attributes().getInt("code");
            switch (statusCode) {
                case 515 -> reconnect();
                case 401 -> handleFailure(node, statusCode);
                default -> {
                    Validate.isTrue(node.findNode("xml-not-well-formed") == null, "An invalid node was sent to Whatsapp");
                    node.children().forEach(error -> store.resolvePendingRequest(error, true));
                }
            }
        }

        private void handleFailure(Node node, int statusCode) {
            var child = node.children().getFirst();
            var type = child.attributes().getString("type");
            var reason = child.attributes().getString("reason", null);
            Validate.isTrue(handleFailure(statusCode, requireNonNullElse(reason, type)),
                    "Invalid or expired credentials: socket failed with status code %s at %s", statusCode, requireNonNullElse(reason, type));
            changeKeys();
            reconnect();
        }

        private void digestSuccess() {
            sendPreKeys();
            confirmConnection();
            createPingService();
            sendStatusUpdate();
            store.callListeners(WhatsappListener::onLoggedIn);
        }

        private void sendStatusUpdate() {
            var presence = withAttributes("presence", of("type", "available"));
            sendWithNoResponse(presence);
            sendQuery("get", "blocklist");
            sendQuery("get", "privacy", with("privacy"));
            sendQuery("get", "abt", withAttributes("props", of("protocol", "1")));
            sendQuery("get", "w", with("props"))
                    .thenAcceptAsync(this::parseProps);
        }

        private void parseProps(Node result) {
            var properties = result.findNode("props")
                    .findNodes("prop")
                    .stream()
                    .map(node -> Map.entry(node.attributes().getString("name"), node.attributes().getString("value")))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            store.callListeners(listener -> listener.onProps(properties));
        }

        private void createPingService() {
            if(pingService.isShutdown()){
                pingService = Executors.newSingleThreadScheduledExecutor();
            }

            pingService.scheduleAtFixedRate(this::sendPing,
                    0L, 20L, TimeUnit.SECONDS);
        }

        private void sendPing() {
            if(!loggedIn){
                pingService.shutdownNow();
                return;
            }

            sendQuery("get", "w:p", with("ping"));
        }

        private void createMediaConnection(){
            if(!loggedIn){
                return;
            }

            sendQuery("set", "w:m", with("media_conn"))
                    .thenApplyAsync(MediaConnection::ofNode)
                    .thenApplyAsync(this::scheduleMediaConnection)
                    .thenApplyAsync(store::mediaConnection);
        }

        private MediaConnection scheduleMediaConnection(MediaConnection connection) {
            CompletableFuture.delayedExecutor(connection.ttl(), TimeUnit.SECONDS)
                    .execute(this::createMediaConnection);
            return connection;
        }

        private void digestIq(Node node) {
            var children = node.children();
            if(children.isEmpty()){
                return;
            }

            var container = children.getFirst();
            switch (container.description()){
                case "pair-device" -> generateQrCode(node, container);
                case "pair-success" -> confirmQrCode(node, container);
                default -> throw new IllegalArgumentException("Cannot handle iq request, unknown description. %s%n".formatted(container.description()));
            }
        }

        private void confirmConnection() {
            sendQuery("set", "passive", with("active"))
                    .thenRunAsync(this::createMediaConnection);
        }

        private void sendPreKeys() {
            if(keys.hasPreKeys()){
                return;
            }

            sendQuery("set", "encrypt", createPreKeysContent());
        }

        private Node[] createPreKeysContent() {
            return new Node[]{createPreKeysRegistration(), createPreKeysType(),
                    createPreKeysIdentity(), createPreKeys(), keys.signedKeyPair().toNode()};
        }

        private Node createPreKeysIdentity() {
            return with("identity", keys.identityKeyPair().publicKey());
        }

        private Node createPreKeysType() {
            return with("type", "");
        }

        private Node createPreKeysRegistration() {
            return with("registration", SignalHelper.toBytes(keys.id(), 4));
        }

        private Node createPreKeys() {
            var nodes = IntStream.range(0, 30)
                    .mapToObj(SignalPreKeyPair::ofIndex)
                    .peek(keys.preKeys()::add)
                    .map(SignalPreKeyPair::toNode)
                    .toList();

            return with("list", nodes);
        }

        private void generateQrCode(Node node, Node container) {
            printQrCode(container);
            sendConfirmNode(node, null);
        }

        private void printQrCode(Node container) {
            var ref = container.findNode("ref");
            var qr = new String(ref.bytes(), StandardCharsets.UTF_8);
            var matrix = Qr.generate(keys, qr);
            if (!store.listeners().isEmpty()) {
                store.callListeners(listener -> listener.onQRCode(matrix).accept(matrix));
                return;
            }

            TERMINAL.accept(matrix);
        }

        @SneakyThrows
        private void confirmQrCode(Node node, Node container) {
            lock.countDown();
            saveCompanion(container);

            var deviceIdentity = Objects.requireNonNull(container.findNode("device-identity"), "Missing device identity");
            var advIdentity = ProtobufDecoder.forType(SignedDeviceIdentityHMAC.class)
                    .decode(deviceIdentity.bytes());
            var advSign = Hmac.calculateSha256(advIdentity.details(), keys.companionKey());
            Validate.isTrue(Arrays.equals(advIdentity.hmac(), advSign.data()), "Cannot login: Hmac validation failed!", SecurityException.class);

            var account = ProtobufDecoder.forType(SignedDeviceIdentity.class)
                    .decode(advIdentity.details());
            var message = BinaryArray.of(new byte[]{6, 0})
                    .append(account.details())
                    .append(keys.identityKeyPair().publicKey())
                    .data();
            Validate.isTrue(Curve.verifySignature(account.accountSignatureKey(), message, account.accountSignature()),
                    "Cannot login: Hmac validation failed!", SecurityException.class);

            var deviceSignatureMessage = BinaryArray.of(new byte[]{6, 1})
                    .append(account.details())
                    .append(keys.identityKeyPair().publicKey())
                    .append(account.accountSignature())
                    .data();
            var deviceSignature = Curve.calculateSignature(keys.identityKeyPair().privateKey(), deviceSignatureMessage);

            var keyIndex = ProtobufDecoder.forType(DeviceIdentity.class)
                    .decode(account.details())
                    .keyIndex();
            var identity = ProtobufEncoder.encode(account.deviceSignature(deviceSignature).accountSignature(null));
            var identityNode = with("device-identity", of("key-index", String.valueOf(keyIndex)), identity);
            var content = withChildren("pair-device-sign", identityNode);

            sendConfirmNode(node, content);
        }

        private void sendConfirmNode(Node node, Node content) {
            sendQuery(node.id(), ContactJid.SOCKET,
                    "result", null, of(), content);
        }

        private void saveCompanion(Node container) {
            var node = Objects.requireNonNull(container.findNode("device"), "Missing device");
            var companion = node.attributes().getJid("jid")
                    .orElseThrow(() -> new NoSuchElementException("Missing companion"));
            keys.companion(companion)
                    .save();
        }
    }

    private class MessageHandler {
        public List<MessageInfo> decode(Node node) {
            var timestamp = node.attributes().getLong("t");
            var id = node.attributes().getRequiredString("id");
            var from = node.attributes().getJid("from")
                    .orElseThrow(() -> new NoSuchElementException("Missing from"));
            var recipient = node.attributes().getJid("recipient")
                    .orElse(from);
            var participant = node.attributes().getJid("participant")
                    .orElse(null);
            var messageBuilder = MessageInfo.newMessageInfo();
            var keyBuilder = MessageKey.newMessageKey();
            switch (from.type()){
                case USER, OFFICIAL_BUSINESS_ACCOUNT, STATUS, ANNOUNCEMENT -> {
                    keyBuilder.chatJid(recipient);
                    messageBuilder.senderJid(from);
                }

                case GROUP, GROUP_CALL, BROADCAST -> {
                    var sender = Objects.requireNonNull(participant, "Missing participant in group message");
                    keyBuilder.chatJid(from);
                    messageBuilder.senderJid(sender);
                }

                default -> throw new IllegalArgumentException("Cannot decode message, unsupported type: %s".formatted(from.type().name()));
            }

            var key = keyBuilder.id(id).create();
            var info = messageBuilder.store(store)
                    .key(key)
                    .timestamp(timestamp)
                    .create();

            var messages = node.findNodes("enc");
            return messages.stream()
                    .map(messageNode -> decodeMessage(info, messageNode, from))
                    .filter(Objects::nonNull)
                    .toList();
        }

        private MessageInfo decodeMessage(MessageInfo info, Node node, ContactJid from) {
            try {
                var encodedMessage = node.bytes();
                var messageType = node.attributes().getString("type");
                var buffer = decodeCipheredMessage(info, encodedMessage, messageType);
                info.message(decodeMessageContainer(buffer));
                handleSenderKeyMessage(from, info);
                return info;
            }catch (Throwable throwable){
                log.warning("An exception occurred while processing a message: " + throwable.getMessage());
                log.warning("The application will continue running normally, but submit an issue on GitHub");
                return null;
            }
        }

        private byte[] decodeCipheredMessage(MessageInfo info, byte[] message, String type) {
            return switch (type) {
                case "skmsg" -> {
                    var senderName = new SenderKeyName(info.chatJid().toString(), info.senderJid().toSignalAddress());
                    var signalGroup = new GroupCipher(senderName, keys);
                    yield signalGroup.decrypt(message);
                }

                case "pkmsg" -> {
                    var session = new SessionCipher(info.chatJid().toSignalAddress(), keys);
                    var preKey = SignalPreKeyMessage.ofSerialized(message);
                    yield session.decrypt(preKey);
                }

                case "msg" -> {
                    var session = new SessionCipher(info.chatJid().toSignalAddress(), keys);
                    var signalMessage = SignalMessage.ofSerialized(message);
                    yield session.decrypt(signalMessage);
                }

                default -> throw new IllegalArgumentException("Unsupported encoded message type: %s".formatted(type));
            };
        }

        private MessageContainer decodeMessageContainer(byte[] buffer) {
            try {
                var bufferWithNoPadding = copyOfRange(buffer, 0, buffer.length - buffer[buffer.length - 1]);
                return ProtobufDecoder.forType(MessageContainer.class)
                        .decode(bufferWithNoPadding);
            }catch (IOException exception){
                throw new IllegalArgumentException("Cannot decode message", exception);
            }
        }

        @SneakyThrows
        private void handleSenderKeyMessage(ContactJid from, MessageInfo info) {
            switch (info.message().content()){
                case SenderKeyDistributionMessage distributionMessage -> handleDistributionMessage(distributionMessage, from);
                case ProtocolMessage protocolMessage -> handleProtocolMessage(info, protocolMessage);
                case DeviceSentMessage deviceSentMessage -> saveMessage(info.message(deviceSentMessage.message()));
                case MediaMessage mediaMessage -> {
                    mediaMessage.store(store);
                    saveMessage(info);
                }
                default -> saveMessage(info);
            }
        }

        private void saveMessage(MessageInfo info) {
            info.chat()
                    .orElseThrow(() -> new NoSuchElementException("Missing chat: %s".formatted(info.chatJid())))
                    .messages()
                    .add(info);
        }

        private void handleDistributionMessage(SenderKeyDistributionMessage distributionMessage, ContactJid from) {
            var groupName = new SenderKeyName(distributionMessage.groupId(), from.toSignalAddress());
            var builder = new GroupSessionBuilder(keys);
            var message = SignalDistributionMessage.ofSerialized(distributionMessage.data());
            builder.process(groupName, message);
        }

        @SneakyThrows
        private void handleProtocolMessage(MessageInfo info, ProtocolMessage protocolMessage){
            switch(protocolMessage.type()) {
                case HISTORY_SYNC_NOTIFICATION -> {
                    var compressed = Medias.download(protocolMessage.historySyncNotification(), store);
                    var decompressed = SignalHelper.deflate(compressed);
                    var history = ProtobufDecoder.forType(HistorySync.class)
                            .decode(decompressed);
                    switch(history.syncType()) {
                        case FULL, INITIAL_BOOTSTRAP -> {
                            store.chats().addAll(history.conversations());
                            store.callListeners(WhatsappListener::onChats);
                        }
                        case INITIAL_STATUS_V3 -> {
                            history.statusV3Messages()
                                    .stream()
                                    .peek(message -> message.store(store))
                                    .forEach(store.status()::add);
                            store.callListeners(WhatsappListener::onStatus);
                        }
                        case RECENT -> history.conversations()
                                .forEach(this::handleRecentMessage);
                        case PUSH_NAME -> history.pushNames()
                                .forEach(this::handNewPushName);
                    }

                    var receipt = withAttributes("receipt",
                            of("id", info.key().id(), "type", "hist_sync", "to","%s@c.us".formatted(keys.companion().user())));
                    send(receipt);
                }

                case APP_STATE_SYNC_KEY_SHARE -> {
                    var newKeys = protocolMessage.appStateSyncKeyShare()
                            .keys();
                    keys.appStateKeys().addAll(newKeys);
                    CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS) // FIXME: 26/01/2022 transaction
                            .execute(appStateHandler::sync);
                }

                case REVOKE -> {
                    var chat = info.chat()
                            .orElseThrow(() -> new NoSuchElementException("Missing chat"));
                    var message = store.findMessageById(chat, protocolMessage.key().id())
                            .orElseThrow(() -> new NoSuchElementException("Missing message"));
                    chat.messages().add(message);
                    store.callListeners(listener -> listener.onMessageDeleted(message, true));
                }

                case EPHEMERAL_SETTING -> {
                    var chat = info.chat()
                            .orElseThrow(() -> new NoSuchElementException("Missing chat"));
                    chat.ephemeralMessagesToggleTime(info.timestamp())
                            .ephemeralMessageDuration(protocolMessage.ephemeralExpiration());
                    var setting = new EphemeralSetting(info.ephemeralDuration(), info.timestamp());
                    store.callListeners(listener -> listener.onSetting(setting));
                }
            }
        }

        private void handNewPushName(PushName pushName) {
            var oldContact = store.findContactByJid(pushName.id())
                    .orElseGet(() -> createContact(pushName.id()));
            oldContact.chosenName(pushName.pushname());
            var name = oldContact.bestName("unknown");
            store.findChatByJid(oldContact.jid().toString())
                    .ifPresentOrElse(chat -> chat.name(name), () -> createChat(oldContact));
            var firstName = pushName.pushname().contains(" ")  ? pushName.pushname().split(" ")[0]
                    : null;
            var action = new ContactAction(pushName.pushname(), firstName);
            store.callListeners(listener -> listener.onAction(action));
        }

        private void createChat(Contact oldContact){
            var newChat = Chat.builder()
                    .jid(oldContact.jid())
                    .name(oldContact.bestName("missing"))
                    .build();
            store.addChat(newChat);
        }

        private Contact createContact(String id) {
            var jid = ContactJid.ofUser(id);
            var newContact = Contact.ofJid(jid);
            store.addContact(newContact);
            return newContact;
        }

        private void handleRecentMessage(Chat recent) {
            var oldChat = store.findChatByJid(recent.jid().toString())
                    .orElseThrow(() -> new NoSuchElementException("Cannot handle recent messages: missing chat"));
            recent.messages()
                    .stream()
                    .peek(message -> message.store(store))
                    .forEach(oldChat.messages()::add);
            store.callListeners(listener -> listener.onChatRecentMessages(oldChat));
        }
    }

    private class AppStateHandler {
        private static final int MAX_SYNC_ATTEMPTS = 5;

        public void sync(){
            sync("critical_block", "critical_unblock_low",
                    "regular_high", "regular_low", "regular");
        }

        @SneakyThrows
        public void sync(String... requests) {
            var states = Arrays.stream(requests)
                    .map(name -> keys.findHashStateByName(name))
                    .toList();
            var nodes = states.stream()
                    .map(LTHashState::toNode)
                    .toArray(Node[]::new);

            sendQuery("set", "w:sync:app:state", withChildren("sync", nodes))
                    .thenApplyAsync(this::parseSyncRequest)
                    .thenApplyAsync(this::parsePatches)
                    .thenAcceptAsync(actions -> actions.forEach(this::processSyncActions))
                    .exceptionallyAsync(this::handleSyncFailure);
        }

        private Void handleSyncFailure(Throwable throwable){
            throwable.printStackTrace();
            return null;
        }

        private List<ActionDataSync> parsePatches(List<SnapshotSyncRecord> patches) {
            return patches.stream()
                    .map(patch -> parsePatch(patch, 0))
                    .flatMap(Collection::stream)
                    .toList();
        }

        private List<ActionDataSync> parsePatch(SnapshotSyncRecord patch, int tries) {
            var results = new ArrayList<ActionDataSync>();
            var name = patch.name();
            var state = keys.findHashStateByName(name);
            try {
                if (patch.hasSnapshot()) {
                    var decodedSnapshot = decodeSnapshot(name, patch.snapshot(), state.version(), false);
                    keys.hashStates().put(name, decodedSnapshot.state());
                    results.addAll(decodedSnapshot.mutations().records());
                }

                if (!patch.patches().isEmpty()) {
                    var decodedPatches = decodePatches(name, patch.patches(), state, state.version(), false);
                    decodedPatches.mutations()
                            .stream()
                            .map(MutationsRecord::records)
                            .flatMap(Collection::stream)
                            .forEach(results::add);
                }

                return results;
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                keys.hashStates().remove(name);
                if (tries > MAX_SYNC_ATTEMPTS) {
                    throw new RuntimeException("Cannot parse patch", throwable);
                }

                return parsePatch(patch, tries + 1);
            }
        }

        private List<SnapshotSyncRecord> parseSyncRequest(Node node) {
            var syncNode = node.findNode("dataSync");
            return syncNode.findNodes("collection")
                    .stream()
                    .map(this::parseSync)
                    .toList();
        }

        @SneakyThrows
        private SnapshotSyncRecord parseSync(Node sync) {
            var snapshot = sync.findNode("snapshot");
            var name = sync.attributes().getString("name");
            var more = sync.attributes().getBool("has_more_patches");
            var snapshotSync = decodeSnapshot(snapshot);
            var patches = decodePatches(sync);
            return new SnapshotSyncRecord(name, snapshotSync, patches, more);
        }

        @SneakyThrows
        private List<PatchSync> decodePatches(Node sync) {
            var versionCode = sync.attributes().getInt("version");
            return requireNonNullElse(sync.findNode("patches"), sync)
                    .findNodes("patch")
                    .stream()
                    .map(patch -> decodePatch(patch, versionCode))
                    .toList();
        }

        @SneakyThrows
        private PatchSync decodePatch(Node patch, int versionCode) {
            if (!patch.hasContent()) {
                return null;
            }

            var patchSync = ProtobufDecoder.forType(PatchSync.class)
                    .decode(patch.bytes());
            if (!patchSync.hasVersion()) {
                var version = new VersionSync(versionCode + 1);
                patchSync.version(version);
            }

            return patchSync;
        }

        private void processSyncActions(ActionDataSync mutation) {
            var targetContact = store.findContactByJid(mutation.messageIndex().chatJid());
            var targetChat = store.findChatByJid(mutation.messageIndex().chatJid());
            var targetMessage = targetChat.flatMap(chat -> store.findMessageById(chat, mutation.messageIndex().messageId()));

            var value = mutation.value();
            if(value == null){
                return;
            }

            var action = value.action();
            if (action != null){
                switch (action) {
                    case AndroidUnsupportedActions ignored -> {}
                    case ClearChatAction ignored -> targetChat.map(Chat::messages).ifPresent(Collection::clear);
                    case ContactAction contactAction -> targetContact.ifPresent(contact -> updateContactName(contact, contactAction));
                    case DeleteChatAction ignored -> targetChat.ifPresent(store.chats()::remove);
                    case DeleteMessageForMeAction ignored -> targetMessage.ifPresent(message -> targetChat.ifPresent(chat -> chat.messages().remove(message)));
                    case MarkChatAsReadAction markAction -> targetChat.ifPresent(chat -> chat.unreadMessages(markAction.read() ? 0 : -1));
                    case MuteAction muteAction -> targetChat.ifPresent(chat -> chat.mute(ChatMute.muted(muteAction.muteEndTimestamp())));
                    case PinAction pinAction -> targetChat.ifPresent(chat -> chat.pinned(pinAction.pinned() ? mutation.value().timestamp() : 0));
                    case StarAction starAction -> targetMessage.ifPresent(message -> message.starred(starAction.starred()));
                    case ArchiveChatAction archiveChatAction -> targetChat.ifPresent(chat -> chat.archived(archiveChatAction.archived()));
                    default -> log.info("Unsupported action: " + mutation.value().action());
                }

                store.callListeners(listener -> listener.onAction(action));
            }

            var setting = value.setting();
            if(setting != null){
                store.callListeners(listener -> listener.onSetting(setting));
            }

            var features = mutation.value().primaryFeature();
            if(features != null && !features.flags().isEmpty()){
                store.callListeners(listener -> listener.onFeatures(features.flags()));
            }
        }

        private void updateContactName(Contact contact, ContactAction action) {
            contact.update(action);
            var name = contact.bestName("unknown");
            store.findChatByJid(contact.jid().toString())
                    .ifPresent(chat -> chat.name(name));
        }

        @SneakyThrows
        private SnapshotsRecord decodePatches(String name, List<PatchSync> patches, LTHashState initial, long minimumVersionNumber, boolean validateMacs) {
            var newState = initial.copy();
            var snapshots = patches.stream()
                    .map(patch -> decodePatch(name, minimumVersionNumber, validateMacs, newState, patch))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
            return new SnapshotsRecord(newState, snapshots);
        }

        @SneakyThrows
        private Optional<MutationsRecord> decodePatch(String name, long minimumVersionNumber, boolean validateMacs, LTHashState newState, PatchSync patch) {
            if(patch.hasExternalMutations()) {
                var blob = Medias.download(patch.externalMutations(), store);
                var mutationsSync = ProtobufDecoder.forType(MutationsSync.class)
                        .decode(blob);
                patch.mutations().addAll(mutationsSync.mutations());
            }

            newState.version(patch.version().version());
            Validate.isTrue(!validateMacs || Arrays.equals(calculateSyncMac(patch, name), patch.patchMac()),
                    "Cannot decode mutations: Hmac validation failed",
                    SecurityException.class);

            var decoded = decodeMutations(patch.mutations(), newState, validateMacs);
            newState.hash(decoded.hash());
            newState.indexValueMap(decoded.indexValueMap());
            Validate.isTrue(!validateMacs || Arrays.equals(generatePatchMac(name, newState, patch), patch.snapshotMac()),
                    "Cannot decode mutations: Hmac validation failed",
                    SecurityException.class);

            return Optional.of(decoded)
                    .filter(ignored -> patch.version().version() == 0 || patch.version().version() > minimumVersionNumber);
        }

        private byte[] generatePatchMac(String name, LTHashState newState, PatchSync patch) {
            var encryptedKey = keys.findAppKeyById(patch.keyId().id())
                    .orElseThrow(() -> new NoSuchElementException("No keys available for mutation"));
            var mutationKeys = MutationKeys.of(encryptedKey.keyData().keyData());
            return generateSnapshotMac(newState.hash(), newState.version(), name, mutationKeys.snapshotMacKey());
        }

        private byte[] calculateSyncMac(PatchSync sync, String name) {
            var appStateSyncKey = keys.findAppKeyById(sync.keyId().id())
                    .orElseThrow(() -> new NoSuchElementException("No keys available for mutation"));
            var mutationKeys = MutationKeys.of(appStateSyncKey.keyData().keyData());
            var mutationMacs = sync.mutations()
                    .stream()
                    .map(mutation -> BinaryArray.of(mutation.record().value().blob()).cut(-32).data())
                    .toArray(byte[][]::new);
            return generatePatchMac(sync.snapshotMac(), mutationMacs, sync.version().version(), name, mutationKeys.patchMacKey());
        }


        private SnapshotRecord decodeSnapshot(String name, SnapshotSync snapshot, long minimumVersion, boolean validateMacs) {
            var newState = new LTHashState(snapshot.version().version());
            var mutations = decodeMutations(snapshot.records(), newState, validateMacs);
            newState.hash(mutations.hash());
            newState.indexValueMap(mutations.indexValueMap());

            Validate.isTrue(!validateMacs || Arrays.equals(snapshot.mac(), computeSnapshotMac(name, snapshot, newState)),
                    "Cannot decode mutations: Hmac validation failed",
                    SecurityException.class);

            var required = minimumVersion == 0 || newState.version() > minimumVersion;
            if(!required){
                mutations.records().clear();
            }

            return new SnapshotRecord(newState, mutations);
        }

        private byte[] computeSnapshotMac(String name, SnapshotSync snapshot, LTHashState newState) {
            var encryptedKey = keys.findAppKeyById(snapshot.keyId().id())
                    .orElseThrow(() -> new NoSuchElementException("No keys available for mutation"));
            var mutationKeys = MutationKeys.of(encryptedKey.keyData().keyData());
            return generateSnapshotMac(newState.hash(), newState.version(), name, mutationKeys.snapshotMacKey());
        }

        private MutationsRecord decodeMutations(List<? extends ParsableMutation> syncs, LTHashState initialState, boolean validateMacs) {
            var generator = new LTHash(initialState.hash());
            var mutations = syncs.stream()
                    .map(mutation -> decodeMutation(validateMacs, generator, mutation))
                    .toList();
            return new MutationsRecord(generator.finish(), generator.data(), mutations);
        }

        @SneakyThrows
        private ActionDataSync decodeMutation(boolean validateMacs, LTHash generator, ParsableMutation mutation) {
            var appStateSyncKey = keys.findAppKeyById(mutation.id())
                    .orElseThrow(() -> new NoSuchElementException("No keys available for mutation"));

            var mutationKeys = MutationKeys.of(appStateSyncKey.keyData().keyData());
            var encryptedBlob = mutation.valueBlob()
                    .cut(-32)
                    .data();
            var encryptedMac = mutation.valueBlob()
                    .slice(-32)
                    .data();
            Validate.isTrue(!validateMacs || Arrays.equals(generateMac(MutationSync.Operation.SET, encryptedBlob, mutation.id(), mutationKeys.macKey()), encryptedMac),
                    "Cannot decode mutations: Hmac validation failed",
                    SecurityException.class);

            var result = AesCbc.decrypt(encryptedBlob, mutationKeys.encKey());
            var actionSync = ProtobufDecoder.forType(ActionDataSync.class)
                    .decode(result);
            Validate.isTrue(!validateMacs || Objects.equals(Hmac.calculateSha256(actionSync.index(), mutationKeys.indexKey()), mutation.indexBlob()),
                    "Cannot decode mutations: Hmac validation failed",
                    SecurityException.class);

            var blob = mutation.valueBlob().data();
            generator.mix(blob, encryptedMac, MutationSync.Operation.SET);
            return actionSync;
        }

        private byte[] generateMac(MutationSync.Operation operation, byte[] data, byte[] keyId, byte[] key) {
            var keyData = (byte) switch (operation){
                case SET -> 0x01;
                case REMOVE -> 0x02;
            };

            var encodedKey = BinaryArray.of(keyData)
                    .append(keyId)
                    .data();

            var last = BinaryArray.allocate(8)
                    .withLast((byte) encodedKey.length)
                    .data();

            var total = BinaryArray.of(encodedKey, data, last)
                    .data();

            return Hmac.calculateSha512(total, key)
                    .cut(32)
                    .data();
        }

        private byte[] generateSnapshotMac(byte[] ltHash, long version, String patchName, byte[] key) {
            var order = to64BitNetworkOrder(version);
            var patchBytes = patchName.getBytes(StandardCharsets.UTF_8);
            var total = BinaryArray.of(ltHash, order, patchBytes)
                    .data();
            return Hmac.calculateSha256(total, key)
                    .data();
        }

        private byte[] generatePatchMac(byte[] snapshotMac, byte[][] valueMacs, long version, String type, byte[] key) {
            var macs = BinaryArray.of(valueMacs);
            var total = BinaryArray.of(snapshotMac)
                    .append(macs)
                    .append(to64BitNetworkOrder(version))
                    .append(type.getBytes(StandardCharsets.UTF_8))
                    .data();
            return Hmac.calculateSha256(total, key)
                    .data();
        }

        private byte[] to64BitNetworkOrder(long number) {
            var binary = Buffers.newBuffer()
                    .order(ByteOrder.BIG_ENDIAN)
                    .writeBytes(new byte[4])
                    .writeLong(number);
            return Buffers.readBytes(binary);
        }

        private SnapshotSync decodeSnapshot(Node snapshot) throws IOException {
            if(snapshot == null){
                return null;
            }

            var blob = ProtobufDecoder.forType(ExternalBlobReference.class)
                    .decode(snapshot.bytes());
            var syncedData = Medias.download(blob, store);
            return ProtobufDecoder.forType(SnapshotSync.class)
                    .decode(syncedData);
        }
    }
}