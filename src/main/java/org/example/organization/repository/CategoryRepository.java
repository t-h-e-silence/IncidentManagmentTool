package org.example.organization.repository;

import java.util.List;
import java.util.UUID;

import org.example.organization.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findByActiveTrueOrderByName();
}
