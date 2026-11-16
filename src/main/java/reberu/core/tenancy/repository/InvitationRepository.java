package reberu.core.tenancy.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import reberu.core.tenancy.entity.Invitation;
import reberu.core.tenancy.entity.TenancyCodes.InvitationState;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.orgId = :orgId and i.id = :invitationId")
    Optional<Invitation> findForUpdate(UUID orgId, UUID invitationId);

    List<Invitation> findByOrgIdAndTeamIdAndEmailInAndStateIn(UUID orgId, UUID teamId, Collection<String> emails,
            Collection<InvitationState> states);

    List<Invitation> findByOrgIdAndTeamIdIsNullAndEmailInAndStateIn(UUID orgId, Collection<String> emails,
            Collection<InvitationState> states);

}
