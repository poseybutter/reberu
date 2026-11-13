package reberu.core.tenancy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.TestcontainersConfiguration;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.support.Uuids;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.repository.TeamRepository;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TeamConflictTest {

    @Autowired
    TeamRepository teams;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM team").update();
    }

    @Test
    @DisplayName("TEN-TEAM-06: 이름 키 유일 제약 위반만 이름 중복으로 판정, 원래 예외는 원인으로 보존")
    void detectsOnlyNameKeyConflict() {
        UUID orgId = UUID.randomUUID();
        save(team(orgId, "연구", "연구"));

        DataIntegrityViolationException duplicated = violation(team(orgId, "연구", "연구"));
        DataIntegrityViolationException tooLong = violation(team(orgId, "가".repeat(Team.NAME_MAX + 1), "긴 이름"));

        assertThat(TeamService.isNameKeyConflict(duplicated)).as(duplicated.getMessage()).isTrue();
        assertThat(TeamService.isNameKeyConflict(tooLong)).as(tooLong.getMessage()).isFalse();
        assertThat(TeamService.duplicateName(duplicated)).isInstanceOfSatisfying(ReberuException.class, rejected -> {
            assertThat(rejected.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(rejected.fields()).containsExactly(new FieldViolation("name", FieldReason.DUPLICATE));
            assertThat(rejected.getCause()).isSameAs(duplicated);
        });
        assertThat(TeamService.duplicateName(tooLong)).isSameAs(tooLong);
    }

    private DataIntegrityViolationException violation(Team team) {
        return catchThrowableOfType(DataIntegrityViolationException.class, () -> save(team));
    }

    private void save(Team team) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> teams.saveAndFlush(team));
    }

    private static Team team(UUID orgId, String name, String nameKey) {
        return Team.create(Uuids.v7(), orgId, name, NameRules.nameKey(nameKey), null, 1, "[]", Instant.now());
    }

}
