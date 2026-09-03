package com.syllabai.smartmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The generator must decide every in-scope mark point exactly once — partial coverage
 * would silently drop marks the learner may have earned (or lost).
 */
public class CoverageMarkingValidator implements MarkingValidator {

    @Override
    public String name() {
        return "coverage";
    }

    @Override
    public List<String> validate(MarkingCandidate candidate, MarkingContext context) {
        List<String> violations = new ArrayList<>();
        Set<UUID> decided = candidate.allocations().stream()
                .map(MarkingCandidate.Allocation::markPointId)
                .collect(Collectors.toSet());
        for (var point : context.points()) {
            UUID id = point.id();
            if (!decided.contains(id)) {
                violations.add("mark point " + point.ref() + " not decided");
            }
        }
        return violations;
    }
}
