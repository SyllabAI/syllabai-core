package com.syllabai.classroom;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Teacher announcements, newest-first. Announcements are teacher-authored
 *  and few per class; the student feed is bounded at the controller. */
public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

    List<Announcement> findByClassIdOrderByCreatedAtDesc(UUID classId, Pageable pageable);

    List<Announcement> findByClassIdInOrderByCreatedAtDesc(List<UUID> classIds, Pageable pageable);

    /** read receipts for a set of announcements as seen by one student */
    @Query("""
            select r.announcementId
            from AnnouncementRead r
            where r.studentId = :studentId
              and r.announcementId in :announcementIds
            """)
    List<UUID> findReadIds(@Param("studentId") UUID studentId,
                           @Param("announcementIds") List<UUID> announcementIds);

    /** unread count for one student across a set of announcements */
    @Query("""
            select count(a)
            from Announcement a
            where a.classId in :classIds
              and not exists (
                    select 1 from AnnouncementRead r
                    where r.announcementId = a.id
                      and r.studentId = :studentId
              )
            """)
    long countUnread(@Param("classIds") List<UUID> classIds,
                     @Param("studentId") UUID studentId);
}
