package reberu.core.support.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.TestcontainersConfiguration;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.IdempotentRequest;
import reberu.core.support.IdempotentRequests;
import reberu.core.support.ReberuException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotentRequestsTest {

    private static final String PATH = "/api/orgs";

    @Autowired
    IdempotentRequests requests;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactions;

    private final AtomicInteger handled = new AtomicInteger();
    private final UUID account = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM api_idempotency_key").update();
    }

    @Test
    @DisplayName("API-TEST-06: 같은 키의 두 번째 요청은 다시 처리하지 않고 처음 응답 반환")
    void replaysFirstResponse() {
        IdempotentRequest request = request(account, key(), new Body("첫 조직"));

        ResponseEntity<String> first = requests.execute(request, created());
        ResponseEntity<String> second = requests.execute(request, created());

        assertThat(handled).hasValue(1);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isEqualTo(first.getBody()).contains("\"n\":1");
    }

    @Test
    @DisplayName("API-TEST-06: 같은 키의 동시 두 요청 중 하나만 처리")
    void concurrentRequestsHandledOnce() throws Exception {
        IdempotentRequest request = request(account, key(), new Body("동시 조직"));
        CountDownLatch firstStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> first = executor.submit(() -> requests.execute(request, () -> {
                firstStarted.countDown();
                sleep(500);
                return created().get();
            }));
            assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<String>> second = executor.submit(() -> requests.execute(request, created()));

            ResponseEntity<String> firstResponse = first.get(30, TimeUnit.SECONDS);
            ResponseEntity<String> secondResponse = second.get(30, TimeUnit.SECONDS);

            assertThat(handled).hasValue(1);
            assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(secondResponse.getBody()).isEqualTo(firstResponse.getBody());
            assertThat(count()).isOne();
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("다른 계정의 같은 키는 서로 영향 없음")
    void keysAreScopedByAccount() {
        String key = key();
        UUID other = UUID.randomUUID();

        ResponseEntity<String> mine = requests.execute(request(account, key, new Body("내 조직")), created());
        ResponseEntity<String> theirs = requests.execute(request(other, key, new Body("다른 조직")), created());

        assertThat(handled).hasValue(2);
        assertThat(mine.getBody()).contains("\"n\":1");
        assertThat(theirs.getBody()).contains("\"n\":2");
        assertThat(count()).isEqualTo(2);
    }

    @Test
    @DisplayName("API-CON-03: 같은 키에 다른 본문·경로면 400 INVALID_REQUEST")
    void rejectsDifferentRequestWithSameKey() {
        String key = key();
        requests.execute(request(account, key, new Body("첫 조직")), created());

        assertThatThrownBy(() -> requests.execute(request(account, key, new Body("바꾼 조직")), created()))
                .isInstanceOfSatisfying(ReberuException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> requests.execute(
                new IdempotentRequest(account, key, "POST", "/api/orgs/other/teams", new Body("첫 조직")), created()))
                .isInstanceOfSatisfying(ReberuException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThat(handled).hasValue(1);
    }

    @Test
    @DisplayName("API-CON-05: 토큰 응답은 본문을 저장하지 않고 재요청은 409 ALREADY_PROCESSED")
    void tokenResponseIsNotStored() {
        IdempotentRequest request = request(account, key(), new Body("초대 링크"));
        Supplier<ResponseEntity<?>> link = () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(new Created(handled.incrementAndGet(), "secret-link-token"));

        ResponseEntity<String> first = requests.executeWithoutReplay(request, link);

        assertThat(first.getBody()).contains("secret-link-token");
        assertThatThrownBy(() -> requests.executeWithoutReplay(request, link))
                .isInstanceOfSatisfying(ReberuException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.ALREADY_PROCESSED));
        assertThat(handled).hasValue(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM api_idempotency_key WHERE response_body IS NULL"
                        + " AND response_status = 201")
                .query(Integer.class).single()).isOne();
    }

    @Test
    @DisplayName("처리 실패 시 키를 남기지 않아 같은 키로 다시 요청 가능")
    void failureReleasesKey() {
        IdempotentRequest request = request(account, key(), new Body("실패 조직"));

        assertThatThrownBy(() -> requests.execute(request, () -> {
            handled.incrementAndGet();
            throw new ReberuException(ErrorCode.LIMIT_EXCEEDED);
        })).isInstanceOf(ReberuException.class);
        assertThat(count()).isZero();

        ResponseEntity<String> retried = requests.execute(request, created());

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(handled).hasValue(2);
    }

    @Test
    @DisplayName("성공이 아닌 응답은 저장하지 않음")
    void nonSuccessResponseIsNotStored() {
        IdempotentRequest request = request(account, key(), new Body("거절 조직"));

        ResponseEntity<String> rejected = requests.execute(request,
                () -> ResponseEntity.status(HttpStatus.CONFLICT).body(new Created(handled.incrementAndGet(), null)));
        ResponseEntity<String> retried = requests.execute(request, created());

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(handled).hasValue(2);
    }

    @Test
    @DisplayName("24시간 지난 키는 새 요청으로 처리")
    void expiredKeyIsHandledAgain() {
        String key = key();
        IdempotentRequest request = request(account, key, new Body("오래된 조직"));
        requests.execute(request, created());
        jdbc.sql("UPDATE api_idempotency_key SET expires_at = :past WHERE idempotency_key = :key")
                .param("past", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1))
                .param("key", key)
                .update();

        ResponseEntity<String> again = requests.execute(request(account, key, new Body("새 조직")), created());

        assertThat(again.getBody()).contains("\"n\":2");
        assertThat(count()).isOne();
    }

    @Test
    @DisplayName("키 없음·UUID 아님은 400 INVALID_REQUEST, 대문자 키는 소문자로 저장")
    void validatesKey() {
        assertThat(violation(null)).isEqualTo(new FieldViolation(IdempotentRequest.HEADER, FieldReason.REQUIRED));
        assertThat(violation(" ")).isEqualTo(new FieldViolation(IdempotentRequest.HEADER, FieldReason.REQUIRED));
        assertThat(violation("not-a-uuid"))
                .isEqualTo(new FieldViolation(IdempotentRequest.HEADER, FieldReason.INVALID_FORMAT));
        assertThat(violation(key() + "0"))
                .isEqualTo(new FieldViolation(IdempotentRequest.HEADER, FieldReason.INVALID_FORMAT));
        String key = key();
        assertThat(request(account, key.toUpperCase(), null).key()).isEqualTo(key);
    }

    @Test
    @DisplayName("이미 열린 트랜잭션 안에서 호출 거부")
    void rejectsCallInsideTransaction() {
        IdempotentRequest request = request(account, key(), new Body("트랜잭션 조직"));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> requests.execute(request, created())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(handled).hasValue(0);
    }

    @Test
    @DisplayName("정리 작업은 만료된 키만 삭제")
    void cleanupDeletesOnlyExpired() {
        String expired = key();
        requests.execute(request(account, expired, new Body("만료")), created());
        requests.execute(request(account, key(), new Body("유지")), created());
        jdbc.sql("UPDATE api_idempotency_key SET expires_at = :past WHERE idempotency_key = :key")
                .param("past", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1))
                .param("key", expired)
                .update();

        int deleted = new IdempotencyKeyCleanup(jdbc, Clock.systemUTC()).deleteExpired();

        assertThat(deleted).isOne();
        assertThat(count()).isOne();
    }

    private Supplier<ResponseEntity<?>> created() {
        return () -> ResponseEntity.status(HttpStatus.CREATED).body(new Created(handled.incrementAndGet(), null));
    }

    private static IdempotentRequest request(UUID account, String key, Object body) {
        return new IdempotentRequest(account, key, "POST", PATH, body);
    }

    private FieldViolation violation(String key) {
        try {
            request(account, key, null);
        }
        catch (ReberuException exception) {
            assertThat(exception.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
            List<FieldViolation> fields = exception.fields();
            assertThat(fields).hasSize(1);
            return fields.get(0);
        }
        throw new AssertionError("키 검사 예외 없음");
    }

    private int count() {
        return jdbc.sql("SELECT COUNT(*) FROM api_idempotency_key").query(Integer.class).single();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    record Body(String name) {
    }

    record Created(int n, String token) {
    }

}
