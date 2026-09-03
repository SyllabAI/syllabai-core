package com.syllabai.smartmark;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SmartMarkAgreementEvaluationRepository
        extends JpaRepository<SmartMarkAgreementEvaluation, UUID> {

    List<SmartMarkAgreementEvaluation> findAllByOrderByComputedAtDesc(Pageable pageable);

    Optional<SmartMarkAgreementEvaluation> findFirstByScopeOrderByComputedAtDesc(String scope);

    Optional<SmartMarkAgreementEvaluation> findFirstByScopeAndExamPaperIdOrderByComputedAtDesc(
            String scope, UUID examPaperId);
}
