package com.syllabai.classroom;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Read access to teacher-owned classes. Classes are teacher-authored and
 *  few (tens); lists are newest-first with a bounded page at the controller. */
public interface SchoolClassRepository extends JpaRepository<SchoolClass, UUID> {

    List<SchoolClass> findByTeacherIdOrderByCreatedAtDesc(UUID teacherId, Pageable pageable);

    List<SchoolClass> findByTeacherIdAndCourseSlugAndStatusOrderByCreatedAtDesc(
            UUID teacherId, String courseSlug, SchoolClass.Status status, Pageable pageable);

    /** member count per class for the teacher list view (one grouped query) */
    @Query("""
            select cm.classId as classId, count(cm) as memberCount
            from ClassMember cm
            where cm.classId in :classIds
            group by cm.classId
            """)
    List<MemberCountProjection> countMembersByClassId(@Param("classIds") List<UUID> classIds);

    interface MemberCountProjection {
        UUID getClassId();

        long getMemberCount();
    }
}
