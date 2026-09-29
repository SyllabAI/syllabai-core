package com.syllabai.classroom;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Append-only read receipts (V51). The teacher's announcement view derives
 *  per-announcement read counts from this table; the student's own read
 *  state is a subset keyed by student. Never merged into the announcement
 *  row — an announcement never mutates on a read. */
public interface AnnouncementReadRepository extends JpaRepository<AnnouncementRead, AnnouncementRead.Pk> {

    List<AnnouncementRead> findByAnnouncementIdOrderByReadAtAsc(UUID announcementId);

    boolean existsByAnnouncementIdAndStudentId(UUID announcementId, UUID studentId);

    @Query("""
            select r.announcementId, count(r)
            from AnnouncementRead r
            where r.announcementId in :announcementIds
            group by r.announcementId
            """)
    List<Object[]> countByAnnouncementIds(@Param("announcementIds") List<UUID> announcementIds);
}
