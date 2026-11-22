package dev.vladefined.taskbridge;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import dev.vladefined.taskbridge.core.HmacVerifier;
import dev.vladefined.taskbridge.core.HmacVerifier.AuthException;

class HmacVerifierTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef";
    static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    final HmacVerifier v = new HmacVerifier(SECRET, Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
    final byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
    final String ts = String.valueOf(NOW.getEpochSecond());

    String sig(String ts, String nonce, String method, String path, byte[] b) {
        return HexFormat.of().formatHex(v.sign(ts, nonce, method, path, b));
    }

    @Test
    void acceptsValidRequest() {
        assertThatCode(() -> v.verify("POST", "/tasks", ts, "n1", sig(ts, "n1", "POST", "/tasks", body), body))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsReplayedNonce() {
        String s = sig(ts, "n2", "POST", "/tasks", body);
        v.verify("POST", "/tasks", ts, "n2", s, body);
        assertThatThrownBy(() -> v.verify("POST", "/tasks", ts, "n2", s, body))
                .isInstanceOf(AuthException.class).hasMessageContaining("nonce");
    }

    @Test
    void rejectsTamperedBody() {
        String s = sig(ts, "n3", "POST", "/tasks", body);
        byte[] other = "{\"x\":2}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> v.verify("POST", "/tasks", ts, "n3", s, other))
                .isInstanceOf(AuthException.class).hasMessageContaining("mismatch");
    }

    @Test
    void rejectsSignatureMadeForAnotherEndpoint() {
        String s = sig(ts, "n4", "GET", "/tasks/abc", new byte[0]);
        assertThatThrownBy(() -> v.verify("GET", "/tasks/xyz", ts, "n4", s, new byte[0]))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void rejectsOldTimestamp() {
        String old = String.valueOf(NOW.minusSeconds(600).getEpochSecond());
        assertThatThrownBy(() -> v.verify("POST", "/tasks", old, "n5", sig(old, "n5", "POST", "/tasks", body), body))
                .isInstanceOf(AuthException.class).hasMessageContaining("window");
    }

    @Test
    void badSignatureDoesNotBurnNonce() {
        assertThatThrownBy(() -> v.verify("POST", "/tasks", ts, "n6", "00", body)).isInstanceOf(AuthException.class);
        assertThatCode(() -> v.verify("POST", "/tasks", ts, "n6", sig(ts, "n6", "POST", "/tasks", body), body))
                .doesNotThrowAnyException();
    }
}
