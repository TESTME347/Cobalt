package it.auties.whatsapp.model.signal.keypair;

import it.auties.curve25519.Curve25519;
import it.auties.curve25519.XecUtils;
import it.auties.whatsapp.model.request.Node;
import it.auties.whatsapp.util.Keys;
import it.auties.whatsapp.util.Validate;
import lombok.NonNull;

import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.util.Arrays;

public record SignalKeyPair(byte @NonNull [] publicKey, byte[] privateKey) implements ISignalKeyPair{
    public SignalKeyPair(byte[] publicKey, byte[] privateKey) {
        this.publicKey = Keys.withoutHeader(publicKey);
        this.privateKey = privateKey;
    }

    public static SignalKeyPair random(){
        var keyPair = Curve25519.generateKeyPair();
        var publicKey = XecUtils.toBytes((XECPublicKey) keyPair.getPublic());
        var privateKey = XecUtils.toBytes((XECPrivateKey) keyPair.getPrivate());
        return new SignalKeyPair(publicKey, privateKey);
    }

    public byte[] encodedPublicKey(){
        return Keys.withHeader(publicKey);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SignalKeyPair that
                && Arrays.equals(publicKey, that.publicKey) && Arrays.equals(privateKey, that.privateKey);
    }

    @Override
    public Node toNode() {
        throw new UnsupportedOperationException("Cannot serialize generic signal key pair");
    }

    @Override
    public SignalKeyPair toGenericKeyPair() {
        return this;
    }
}