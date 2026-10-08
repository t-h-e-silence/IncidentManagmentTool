package org.example.organization.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.example.common.model.SystemRole;
import org.example.organization.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    List<User> findBySystemRoleAndActiveTrueOrderByName(SystemRole systemRole);

    Optional<User> findByUsername(String username);
}
