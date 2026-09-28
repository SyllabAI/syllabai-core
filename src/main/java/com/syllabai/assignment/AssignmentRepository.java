package com.syllabai.assignment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read access to teacher assignments. Assignments are teacher-authored and
 *  few (tens); lists are newest-first with a bounded page at the controller. */
public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {

    List<Assignment> findByOrderByCreatedAtDesc(Pageable pageable);
}
