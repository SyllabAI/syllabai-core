package com.syllabai.classroom;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit class membership — the ONLY source of classroom visibility on
 *  the learner side (the independent-student rule: no row here, no classroom
 *  capability). The EXISTS-based assignment filter lives on this repository
 *  so the assignment module can ask membership questions without reaching
 *  across modules for repositories (event/contract coupling only). */
public interface ClassMemberRepository extends JpaRepository<ClassMember, UUID> {

    List<ClassMember> findByClassIdOrderByEnrolledAtAsc(UUID classId);

    List<ClassMember> findByStudentIdOrderByEnrolledAtAsc(UUID studentId);

    boolean existsByClassIdAndStudentId(UUID classId, UUID studentId);

    /** does this learner hold membership in ANY live class of this teacher's
     *  class set? Used for scoped checks without loading member rows. */
    @Query("""
            select case when count(cm) > 0 then true else false end
            from ClassMember cm, SchoolClass c
            where cm.classId = c.id
              and cm.studentId = :studentId
              and c.teacherId = :teacherId
              and c.status = 'ACTIVE'
            """)
    boolean existsActiveMembership(@Param("studentId") UUID studentId,
                                   @Param("teacherId") UUID teacherId);
}
