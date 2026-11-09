package reberu.core.support.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.core.support.ErrorCode;
import reberu.core.support.IdempotentRequest;
import reberu.core.support.IdempotentRequests;
import reberu.core.support.ReberuException;
import tools.jackson.databind.json.JsonMapper;

class JdbcIdempotentRequests implements IdempotentRequests {

    static final Duration TTL = Duration.ofHours(24);
    static final int MAX_BODY_LENGTH = 65_536;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    JdbcIdempotentRequests(JdbcClient jdbc, TransactionTemplate transactions, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public ResponseEntity<String> execute(IdempotentRequest request, Supplier<? extends ResponseEntity<?>> action) {
        return run(request, action, true);
    }

    @Override
    public ResponseEntity<String> executeWithoutReplay(IdempotentRequest request,
            Supplier<? extends ResponseEntity<?>> action) {
        return run(request, action, false);
    }

    private ResponseEntity<String> run(IdempotentRequest request, Supplier<? extends ResponseEntity<?>> action,
            boolean replay) {
        Objects.requireNonNull(request, "중복 요청 정보 없음");
        Objects.requireNonNull(action, "처리 함수 없음");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("중복 요청 처리는 트랜잭션 밖에서만 호출 가능");
        }
        String hash = hash(request);
        try {
            return transactions.execute(status -> {
                reserve(request, hash);
                ResponseEntity<?> response = Objects.requireNonNull(action.get(), "처리 결과 없음");
                int code = response.getStatusCode().value();
                String body = serialize(response.getBody());
                if (!response.getStatusCode().is2xxSuccessful()) {
                    status.setRollbackOnly();
                    return json(code, body);
                }
                jdbc.sql("""
                                UPDATE api_idempotency_key SET response_status = :status, response_body = :body
                                WHERE account_id = :account AND idempotency_key = :key
                                """)
                        .param("status", code)
                        .param("body", replay ? body : null)
                        .param("account", request.accountId().toString())
                        .param("key", request.key())
                        .update();
                return json(code, body);
            });
        }
        catch (AlreadyReserved exception) {
            return stored(request, hash);
        }
    }

    private void reserve(IdempotentRequest request, String hash) {
        LocalDateTime now = now();
        jdbc.sql("""
                        DELETE FROM api_idempotency_key
                        WHERE account_id = :account AND idempotency_key = :key AND expires_at <= :now
                        """)
                .param("account", request.accountId().toString())
                .param("key", request.key())
                .param("now", now)
                .update();
        try {
            jdbc.sql("""
                            INSERT INTO api_idempotency_key (account_id, idempotency_key, request_hash, expires_at)
                            VALUES (:account, :key, :hash, :expiresAt)
                            """)
                    .param("account", request.accountId().toString())
                    .param("key", request.key())
                    .param("hash", hash)
                    .param("expiresAt", now.plus(TTL))
                    .update();
        }
        catch (DuplicateKeyException exception) {
            throw new AlreadyReserved();
        }
    }

    private ResponseEntity<String> stored(IdempotentRequest request, String hash) {
        Stored row = jdbc.sql("""
                        SELECT request_hash, response_status, response_body FROM api_idempotency_key
                        WHERE account_id = :account AND idempotency_key = :key AND expires_at > :now
                        """)
                .param("account", request.accountId().toString())
                .param("key", request.key())
                .param("now", now())
                .query((rs, rowNum) -> {
                    int status = rs.getInt("response_status");
                    return new Stored(rs.getString("request_hash"), rs.wasNull() ? null : status,
                            rs.getString("response_body"));
                })
                .optional()
                .orElseThrow(() -> new ReberuException(ErrorCode.ALREADY_PROCESSED));
        if (!MessageDigest.isEqual(row.hash().getBytes(StandardCharsets.US_ASCII),
                hash.getBytes(StandardCharsets.US_ASCII))) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST);
        }
        if (row.status() == null || row.body() == null) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        return json(row.status(), row.body());
    }

    private String hash(IdempotentRequest request) {
        String canonical = request.method() + "\n" + request.path() + "\n" + serialize(request.body());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String serialize(Object body) {
        String json = body == null ? "" : jsonMapper.writeValueAsString(body);
        if (json.length() > MAX_BODY_LENGTH) {
            throw new IllegalStateException("중복 요청 본문 길이 초과");
        }
        return json;
    }

    private static ResponseEntity<String> json(int status, String body) {
        if (body.isEmpty()) {
            return ResponseEntity.status(status).build();
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private record Stored(String hash, Integer status, String body) {
    }

    private static final class AlreadyReserved extends RuntimeException {

        AlreadyReserved() {
            super(null, null, false, false);
        }

    }

}
