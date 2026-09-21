package com.syllabai.smartmark;

import com.syllabai.assessment.MarkPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Total awarded marks must respect the scheme's own ceiling: the sum of per-point
 * awarded marks (partial credit since v3 — each allocation clamped to its point's
 * worth) can never exceed the sum of in-scope point marks, and if the part carries
 * an extracted mark total, that bounds it too. Guards against a hallucinating
 * model awarding more than the paper allows.
 *
 * <p>Registered as a bean so the production pipeline's injected validator chain
 * is non-empty (§23 factory wiring).</p>
 */
@Component
public class MarkSumMarkingValidator implements MarkingValidator {

    @Override
    public String name() {
        return "mark-sum";
    }

    @Override
    public List<String> validate(MarkingCandidate candidate, MarkingContext context) {
        List<String> violations = new ArrayList<>();
        Map<UUID, MarkPoint> byId = context.points().stream()
                .collect(Collectors.toMap(MarkPoint::id, p -> p));

        int awarded = candidate.allocations().stream()
                .mapToInt(a -> {
                    MarkPoint point = byId.get(a.markPointId());
                    if (point == null) {
                        return 0;
                    }
                    // per-point clamp first: an over-award on one point must not
                    // eat the part budget of the others
                    return Math.max(0, Math.min(a.marksAwarded(), point.marks()));
                })
                .sum();

        int ceiling = MarkingValidator.pointMarkCeiling(context);
        int partMarks = context.part().marks();
        // extracted part marks can lag the scheme (v0 heuristics); the scheme is authoritative
        int bound = partMarks > 0 ? Math.min(partMarks, ceiling) : ceiling;

        if (awarded > bound) {
            violations.add("awarded " + awarded + " marks exceeds scheme bound " + bound);
        }
        return violations;
    }
}
