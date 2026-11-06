package reberu.core.account.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import reberu.core.account.AccountState;
import reberu.core.account.entity.Account;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByGoogleSub(String googleSub);

    @Query("select a.state from Account a where a.id = :id")
    Optional<AccountState> findStateById(UUID id);

}
