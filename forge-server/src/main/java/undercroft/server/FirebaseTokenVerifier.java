package undercroft.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that a start_game request comes from someone signed in to this app.
 *
 * The game server had no idea who was connecting: anyone who found its address could start
 * games and fill its memory. The web app already signs players in with Firebase, and a
 * signed-in browser can ask Firebase for an ID token: a short-lived statement, signed by
 * Google, that says "this is user X of project Y". This class checks that statement the way
 * Firebase documents for servers without the Admin SDK: RS256 signature against Google's
 * published keys, audience and issuer equal to our project, not expired, a non-empty subject.
 *
 * No service account and no extra dependency: the keys are public and fetched over HTTPS.
 * Enforcement is on when FIREBASE_PROJECT_ID is set; without it the server behaves as before.
 */
final class FirebaseTokenVerifier {
    private static final Logger log = LoggerFactory.getLogger(FirebaseTokenVerifier.class);
    private static final Gson gson = new Gson();

    /** Seconds of clock difference tolerated between Google and this machine. */
    private static final long SKEW_SECONDS = 60;

    static final class AuthException extends Exception {
        /** True when the token could not be checked at all (keys unreachable), not when it is bad. */
        final boolean unavailable;

        AuthException(String message) {
            this(message, false);
        }

        AuthException(String message, boolean unavailable) {
            super(message);
            this.unavailable = unavailable;
        }
    }

    /** Where signing keys come from: Google in production, a generated pair in the self-test. */
    interface KeySource {
        PublicKey keyFor(String keyId) throws Exception;
    }

    private final String projectId;
    private final KeySource keys;
    private final LongSupplier nowSeconds;

    FirebaseTokenVerifier(String projectId) {
        this(projectId, new GoogleKeySource(), () -> System.currentTimeMillis() / 1000);
    }

    FirebaseTokenVerifier(String projectId, KeySource keys, LongSupplier nowSeconds) {
        this.projectId = projectId;
        this.keys = keys;
        this.nowSeconds = nowSeconds;
    }

    /** The signed-in user's id, or an AuthException saying why the token was not accepted. */
    String verify(String idToken) throws AuthException {
        if (idToken == null || idToken.isBlank()) {
            throw new AuthException("no sign-in token was sent");
        }
        String[] parts = idToken.trim().split("\\.");
        if (parts.length != 3) {
            throw new AuthException("the sign-in token is malformed");
        }

        JsonObject header;
        JsonObject claims;
        byte[] signature;
        try {
            header = gson.fromJson(new String(decode(parts[0]), StandardCharsets.UTF_8), JsonObject.class);
            claims = gson.fromJson(new String(decode(parts[1]), StandardCharsets.UTF_8), JsonObject.class);
            signature = decode(parts[2]);
        } catch (RuntimeException e) {
            throw new AuthException("the sign-in token is malformed");
        }
        if (header == null || claims == null) {
            throw new AuthException("the sign-in token is malformed");
        }

        // "none" and the HMAC family must never be accepted: the key here is public.
        if (!"RS256".equals(string(header, "alg"))) {
            throw new AuthException("the sign-in token uses an unexpected algorithm");
        }
        String keyId = string(header, "kid");
        if (keyId == null || keyId.isEmpty()) {
            throw new AuthException("the sign-in token names no signing key");
        }

        if (!projectId.equals(string(claims, "aud"))) {
            throw new AuthException("the sign-in token is for a different app");
        }
        if (!("https://securetoken.google.com/" + projectId).equals(string(claims, "iss"))) {
            throw new AuthException("the sign-in token has an unexpected issuer");
        }
        String subject = string(claims, "sub");
        if (subject == null || subject.isEmpty() || subject.length() > 128) {
            throw new AuthException("the sign-in token names no user");
        }
        long now = nowSeconds.getAsLong();
        long expires = number(claims, "exp");
        long issued = number(claims, "iat");
        if (expires <= 0 || expires + SKEW_SECONDS < now) {
            throw new AuthException("the sign-in token has expired");
        }
        if (issued <= 0 || issued > now + SKEW_SECONDS) {
            throw new AuthException("the sign-in token is dated in the future");
        }

        PublicKey key;
        try {
            key = keys.keyFor(keyId);
        } catch (Exception e) {
            throw new AuthException("the signing keys could not be fetched: " + e.getMessage(), true);
        }
        if (key == null) {
            throw new AuthException("the sign-in token was signed with an unknown key");
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(signature)) {
                throw new AuthException("the sign-in token's signature does not match");
            }
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("the sign-in token's signature could not be checked");
        }
        return subject;
    }

    private static byte[] decode(String part) {
        return Base64.getUrlDecoder().decode(part);
    }

    private static String string(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static long number(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        try {
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    // =====================================================================
    // Google's signing keys for Firebase ID tokens
    // =====================================================================

    /**
     * The x509 certificates Google signs Firebase ID tokens with, keyed by key id. They rotate
     * every few days; the response's Cache-Control says how long a copy is good for. A failed
     * refresh keeps the copy in hand, so a hiccup at Google does not lock players out.
     */
    static final class GoogleKeySource implements KeySource {
        private static final String CERTS_URL =
                "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";
        private static final Pattern MAX_AGE = Pattern.compile("max-age=(\\d+)");

        private Map<String, PublicKey> keys = Map.of();
        private long expiresAtMillis = 0;
        private long lastAttemptMillis = 0;

        @Override
        public synchronized PublicKey keyFor(String keyId) throws Exception {
            long now = System.currentTimeMillis();
            boolean stale = now >= expiresAtMillis;
            // An unknown key id usually means a rotation since the last fetch; look again, but
            // not more than once a minute, so junk tokens cannot make this hammer Google.
            boolean unknown = !keys.containsKey(keyId) && now - lastAttemptMillis > 60_000;
            if (stale || unknown) {
                lastAttemptMillis = now;
                try {
                    refresh(now);
                } catch (Exception e) {
                    if (keys.isEmpty()) throw e;
                    log.warn("Could not refresh Firebase signing keys, keeping the previous set: {}", e.getMessage());
                }
            }
            return keys.get(keyId);
        }

        private void refresh(long now) throws Exception {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(CERTS_URL)).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from the key server");
            }
            keys = parseCertificates(response.body());
            long maxAge = 3600;
            Matcher m = MAX_AGE.matcher(response.headers().firstValue("cache-control").orElse(""));
            if (m.find()) maxAge = Math.max(300, Long.parseLong(m.group(1)));
            expiresAtMillis = now + maxAge * 1000;
            log.info("Fetched {} Firebase signing keys, good for {} minutes", keys.size(), maxAge / 60);
        }

        static Map<String, PublicKey> parseCertificates(String json) throws Exception {
            JsonObject body = gson.fromJson(json, JsonObject.class);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Map<String, PublicKey> fresh = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
                byte[] pem = entry.getValue().getAsString().getBytes(StandardCharsets.US_ASCII);
                fresh.put(entry.getKey(), factory.generateCertificate(new ByteArrayInputStream(pem)).getPublicKey());
            }
            if (fresh.isEmpty()) throw new IOException("the key server returned no keys");
            return fresh;
        }
    }

    // =====================================================================
    // Self-test, run with the server's --smoke-test in CI
    // =====================================================================

    /**
     * Signs tokens with a throwaway key and checks that the good one is accepted and each
     * kind of bad one is refused. A verifier that accepts everything looks exactly like one
     * that works, so this runs wherever the smoke test runs.
     */
    static boolean selfTest() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            KeyPair other = generator.generateKeyPair();
            long now = 1_800_000_000L;
            String project = "self-test-project";
            KeySource source = keyId -> "k1".equals(keyId) ? pair.getPublic() : null;
            FirebaseTokenVerifier verifier = new FirebaseTokenVerifier(project, source, () -> now);
            String issuer = "https://securetoken.google.com/" + project;

            String good = sign(pair.getPrivate(), "RS256", "k1", project, issuer, "user-1", now + 3000, now - 10);
            if (!"user-1".equals(verifier.verify(good))) return fail("a valid token was not accepted");

            Map<String, String> bad = new java.util.LinkedHashMap<>();
            bad.put("no token", "");
            bad.put("not a token", "abc");
            bad.put("wrong project", sign(pair.getPrivate(), "RS256", "k1", "someone-elses-project", "https://securetoken.google.com/someone-elses-project", "user-1", now + 3000, now - 10));
            bad.put("wrong issuer", sign(pair.getPrivate(), "RS256", "k1", project, "https://example.com/" + project, "user-1", now + 3000, now - 10));
            bad.put("expired", sign(pair.getPrivate(), "RS256", "k1", project, issuer, "user-1", now - 3600, now - 7200));
            bad.put("issued in the future", sign(pair.getPrivate(), "RS256", "k1", project, issuer, "user-1", now + 9000, now + 3600));
            bad.put("no subject", sign(pair.getPrivate(), "RS256", "k1", project, issuer, "", now + 3000, now - 10));
            bad.put("signed by another key", sign(other.getPrivate(), "RS256", "k1", project, issuer, "user-1", now + 3000, now - 10));
            bad.put("unknown key id", sign(pair.getPrivate(), "RS256", "k2", project, issuer, "user-1", now + 3000, now - 10));
            bad.put("algorithm none", sign(pair.getPrivate(), "none", "k1", project, issuer, "user-1", now + 3000, now - 10));
            // The payload swapped after signing: the classic forgery.
            String[] parts = good.split("\\.");
            String forgedClaims = encode("{\"aud\":\"" + project + "\",\"iss\":\"" + issuer + "\",\"sub\":\"someone-else\",\"exp\":" + (now + 3000) + ",\"iat\":" + (now - 10) + "}");
            bad.put("tampered payload", parts[0] + "." + forgedClaims + "." + parts[2]);

            for (Map.Entry<String, String> entry : bad.entrySet()) {
                try {
                    verifier.verify(entry.getValue());
                    return fail("a bad token was accepted: " + entry.getKey());
                } catch (AuthException expected) {
                    // refused, as it should be
                }
            }

            // Keys unreachable must read as "cannot check", not as "bad token".
            FirebaseTokenVerifier offline = new FirebaseTokenVerifier(project, keyId -> { throw new IOException("offline"); }, () -> now);
            try {
                offline.verify(good);
                return fail("a token was accepted with no keys");
            } catch (AuthException e) {
                if (!e.unavailable) return fail("unreachable keys were reported as a bad token");
            }

            log.info("Sign-in check self-test passed: 1 token accepted, {} refused", bad.size() + 1);
            return true;
        } catch (Exception e) {
            return fail("unexpected error: " + e);
        }
    }

    private static boolean fail(String why) {
        log.error("Sign-in check self-test FAILED: {}", why);
        return false;
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(PrivateKey key, String alg, String keyId, String audience, String issuer,
                               String subject, long expires, long issued) throws Exception {
        String header = encode("{\"alg\":\"" + alg + "\",\"kid\":\"" + keyId + "\",\"typ\":\"JWT\"}");
        String claims = encode("{\"aud\":\"" + audience + "\",\"iss\":\"" + issuer + "\",\"sub\":\"" + subject
                + "\",\"exp\":" + expires + ",\"iat\":" + issued + "}");
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }
}
