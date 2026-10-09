package modules.organization.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import modules.common.model.SystemRole;
import modules.organization.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    List<User> findBySystemRoleAndActiveTrueOrderByName(SystemRole systemRole);

    Optional<User> findByUsername(String username);
}
