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
import reberu.core.support.Uuids;
import reberu.core.tenancy.entity.JobType;
import reberu.core.tenancy.repository.JobTypeRepository;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JobTypeConflictTest {

    @Autowired
    JobTypeRepository jobTypes;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM job_type").update();
    }

    @Test
    @DisplayName("이름 키 유일 제약 위반만 이름 중복으로 판정")
    void detectsOnlyNameKeyConflict() {
        UUID orgId = UUID.randomUUID();
        save(jobType(orgId, "연구", "연구"));

        DataIntegrityViolationException duplicated = violation(jobType(orgId, "연구", "연구"));
        DataIntegrityViolationException tooLong = violation(jobType(orgId,
                "가".repeat(JobTypeService.NAME_MAX + 1), "긴 이름"));

        assertThat(JobTypeService.isNameKeyConflict(duplicated)).as(duplicated.getMessage()).isTrue();
        assertThat(JobTypeService.isNameKeyConflict(tooLong)).as(tooLong.getMessage()).isFalse();
    }

    private DataIntegrityViolationException violation(JobType jobType) {
        return catchThrowableOfType(DataIntegrityViolationException.class, () -> save(jobType));
    }

    private void save(JobType jobType) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jobTypes.saveAndFlush(jobType));
    }

    private static JobType jobType(UUID orgId, String name, String nameKey) {
        return JobType.create(Uuids.v7(), orgId, name, NameRules.nameKey(nameKey), null, "{}", Instant.now());
    }

}
