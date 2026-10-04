package com.syllabai.learner.exam;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearnerCourseEnrolmentRepository extends JpaRepository<LearnerCourseEnrolment, UUID> {

    Optional<LearnerCourseEnrolment> findByLearnerIdAndCourseSlug(UUID learnerId, String courseSlug);

    List<LearnerCourseEnrolment> findByLearnerId(UUID learnerId);

    List<LearnerCourseEnrolment> findByLearnerIdAndTargetSeriesIdNotNull(UUID learnerId);
}
