package reberu.core.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class FakeGoogle {

    static final String ISSUER = "https://accounts.google.com";
    static final String CLIENT_ID = "test-client-id";

    private final RSAKey signingKey;
    private final RSAKey otherKey;
    private final HttpServer server;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    FakeGoogle() {
        try {
            signingKey = new RSAKeyGenerator(2048).keyID("fake-google").generate();
            otherKey = new RSAKeyGenerator(2048).keyID("fake-google").generate();
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (JOSEException | IOException exception) {
            throw new IllegalStateException(exception);
        }
        server.createContext("/jwks", exchange -> respond(exchange, 200,
                new JWKSet(signingKey.toPublicJWK()).toString()));
        server.createContext("/token", this::token);
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    String issue(String codeChallenge, IdToken idToken) {
        String code = UUID.randomUUID().toString();
        grants.put(code, new Grant(codeChallenge, idToken));
        return code;
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        Grant grant = grants.remove(form.getOrDefault("code", ""));
        if (grant == null || !grant.codeChallenge().equals(s256(form.getOrDefault("code_verifier", "")))) {
            respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        String idToken = sign(grant.idToken());
        respond(exchange, 200, """
                {"access_token":"fake-access-token","token_type":"Bearer","expires_in":3600,
                 "scope":"openid email profile","id_token":"%s"}
                """.formatted(idToken));
    }

    private String sign(IdToken token) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(token.issuer())
                .audience(token.audience())
                .subject(token.sub())
                .issueTime(Date.from(token.issuedAt()))
                .expirationTime(Date.from(token.expiresAt()))
                .claim("nonce", token.nonce())
                .claim("email", token.email())
                .claim("email_verified", token.emailVerified())
                .claim("name", token.name())
                .claim("picture", "https://lh3.googleusercontent.com/a/fake-picture");
        if (token.hostedDomain() != null) {
            claims.claim("hd", token.hostedDomain());
        }
        if (token.authTime() != null) {
            claims.claim("auth_time", token.authTime().getEpochSecond());
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fake-google").build(),
                    claims.build());
            jwt.sign(new RSASSASigner(token.forgedSignature() ? otherKey : signingKey));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                form.put(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Grant(String codeChallenge, IdToken idToken) {
    }

    record IdToken(String issuer, String audience, String sub, String email, boolean emailVerified, String name,
            String hostedDomain, String nonce, Instant issuedAt, Instant expiresAt, Instant authTime,
            boolean forgedSignature) {

        static IdToken valid(String sub, String email, String nonce) {
            Instant now = Instant.now();
            return new IdToken(ISSUER, CLIENT_ID, sub, email, true, "다온 하늘", null, nonce, now,
                    now.plusSeconds(3600), null, false);
        }

        IdToken withIssuer(String value) {
            return new IdToken(value, audience, sub, email, emailVerified, name, hostedDomain, nonce, issuedAt,
                    expiresAt, authTime, forgedSignature);
        }

        IdToken withAudience(String value) {
            return new IdToken(issuer, value, sub, email, emailVerified, name, hostedDomain, nonce, issuedAt,
                    expiresAt, authTime, forgedSignature);
        }

        IdToken withEmailVerified(boolean value) {
            return new IdToken(issuer, audience, sub, email, value, name, hostedDomain, nonce, issuedAt,
                    expiresAt, authTime, forgedSignature);
        }

        IdToken withNonce(String value) {
            return new IdToken(issuer, audience, sub, email, emailVerified, name, hostedDomain, value, issuedAt,
                    expiresAt, authTime, forgedSignature);
        }

        IdToken withName(String value) {
            return new IdToken(issuer, audience, sub, email, emailVerified, value, hostedDomain, nonce, issuedAt,
                    expiresAt, authTime, forgedSignature);
        }

        IdToken withHostedDomain(String value) {
            return new IdToken(issuer, audience, sub, email, emailVerified, name, value, nonce, issuedAt,
                    expiresAt, authTime, forgedSignature);
        }

        IdToken withAuthTime(Instant value) {
            return new IdToken(issuer, audience, sub, email, emailVerified, name, hostedDomain, nonce, issuedAt,
                    expiresAt, value, forgedSignature);
        }

        IdToken expired() {
            Instant past = Instant.now().minusSeconds(7200);
            return new IdToken(issuer, audience, sub, email, emailVerified, name, hostedDomain, nonce, past,
                    past.plusSeconds(3600), authTime, forgedSignature);
        }

        IdToken forged() {
            return new IdToken(issuer, audience, sub, email, emailVerified, name, hostedDomain, nonce, issuedAt,
                    expiresAt, authTime, true);
        }

    }

}
