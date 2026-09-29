package com.syllabai.classroom;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Explicit class membership — the ONLY source of classroom visibility on
 *  the learner side (the independent-student rule: no row here, no classroom
 *  capability). Membership checks stay exact-match lookups; the classroom
 *  controllers derive their read models from these rows without inventing
 *  speculative query surface. */
public interface ClassMemberRepository extends JpaRepository<ClassMember, UUID> {

    List<ClassMember> findByClassIdOrderByEnrolledAtAsc(UUID classId);

    List<ClassMember> findByStudentIdOrderByEnrolledAtAsc(UUID studentId);

    boolean existsByClassIdAndStudentId(UUID classId, UUID studentId);
}
