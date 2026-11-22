package dev.vladefined.taskbridge.core;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Request authentication for the remote side.
 *
 * <p>Signature = hex(HMAC-SHA256(secret, timestamp \n nonce \n METHOD \n path \n body)).
 * Binding method and path stops a signature for one endpoint being replayed on another;
 * the timestamp window plus a remembered-nonce set stops replays of the same request.
 */
public class HmacVerifier {

    public static class AuthException extends RuntimeException {
        public AuthException(String reason) {
            super(reason);
        }
    }

    private static final int MAX_NONCE_LENGTH = 128;

    private final byte[] secret;
    private final Duration maxSkew;
    private final Clock clock;
    private final Map<String, Instant> seenNonces = new ConcurrentHashMap<>();

    public HmacVerifier(String secret, Duration maxSkew, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.maxSkew = maxSkew;
        this.clock = clock;
    }

    public void verify(String method, String path, String timestamp, String nonce,
                       String signatureHex, byte[] body) {
        if (timestamp == null || nonce == null || signatureHex == null) {
            throw new AuthException("missing auth headers");
        }
        if (nonce.isEmpty() || nonce.length() > MAX_NONCE_LENGTH) {
            throw new AuthException("bad nonce");
        }
        Instant ts;
        try {
            ts = Instant.ofEpochSecond(Long.parseLong(timestamp));
        } catch (NumberFormatException e) {
            throw new AuthException("bad timestamp");
        }
        Instant now = clock.instant();
        if (Duration.between(ts, now).abs().compareTo(maxSkew) > 0) {
            throw new AuthException("timestamp outside allowed window");
        }

        byte[] expected = sign(timestamp, nonce, method, path, body);
        byte[] given;
        try {
            given = HexFormat.of().parseHex(signatureHex);
        } catch (IllegalArgumentException e) {
            throw new AuthException("bad signature encoding");
        }
        if (!MessageDigest.isEqual(expected, given)) {
            throw new AuthException("signature mismatch");
        }

        // Only remember nonces of correctly signed requests, otherwise anyone
        // could fill the set. Expired entries are dropped on the way.
        purge(now);
        if (seenNonces.putIfAbsent(nonce, now) != null) {
            throw new AuthException("nonce already used");
        }
    }

    public byte[] sign(String timestamp, String nonce, String method, String path, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String head = timestamp + "\n" + nonce + "\n" + method.toUpperCase() + "\n" + path + "\n";
            mac.update(head.getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return mac.doFinal();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private void purge(Instant now) {
        Instant cutoff = now.minus(maxSkew.multipliedBy(2));
        seenNonces.values().removeIf(t -> t.isBefore(cutoff));
    }
}
