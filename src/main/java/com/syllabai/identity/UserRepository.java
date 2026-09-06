package com.syllabai.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /**
     * All enabled users holding one role (T-029 class roster: the pilot has no
     * class entity, so the teacher's "class" is the STUDENT cohort). A distinct
     * join is required because roles is an element collection; disabled accounts
     * never appear in teacher-facing surfaces.
     */
    @Query("""
            select distinct u from User u join u.roles r
            where r = :role and u.enabled = true
            order by u.displayName asc, u.email asc
            """)
    List<User> findEnabledByRole(@Param("role") Role role);
}
