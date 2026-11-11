package reberu.core.tenancy.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import reberu.core.tenancy.entity.TeamTaskLabel;

public interface TeamTaskLabelRepository extends Repository<TeamTaskLabel, UUID> {

    TeamTaskLabel save(TeamTaskLabel label);

    TeamTaskLabel saveAndFlush(TeamTaskLabel label);

    void flush();

    @Query("select l from TeamTaskLabel l where l.teamId = :teamId order by l.sortOrder, l.createdAt, l.id")
    List<TeamTaskLabel> findByTeamId(UUID teamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from TeamTaskLabel l where l.teamId = :teamId and l.id = :labelId")
    Optional<TeamTaskLabel> findForUpdate(UUID teamId, UUID labelId);

    boolean existsByTeamIdAndNameKey(UUID teamId, String nameKey);

    long countByTeamId(UUID teamId);

    @Query("select coalesce(max(l.sortOrder), -1) from TeamTaskLabel l where l.teamId = :teamId")
    int findMaxSortOrder(UUID teamId);

}
