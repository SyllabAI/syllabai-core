package com.syllabai.smartmark;

import com.syllabai.assessment.MarkPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Total awarded marks must respect the scheme's own ceiling: sum of awarded points
 * (points are atomic) can never exceed the sum of in-scope point marks, and if the
 * part carries an extracted mark total, that bounds it too. Guards against a
 * hallucinating model awarding more than the paper allows.
 */
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
                .filter(MarkingCandidate.Allocation::awarded)
                .mapToInt(a -> {
                    MarkPoint point = byId.get(a.markPointId());
                    return point == null ? 0 : point.marks();
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
