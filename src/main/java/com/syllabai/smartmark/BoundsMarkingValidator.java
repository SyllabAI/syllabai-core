package com.syllabai.smartmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Every allocation must reference exactly one in-scheme mark point — no invented
 * point ids, no duplicates, no allocations for points outside this answer's part.
 */
public class BoundsMarkingValidator implements MarkingValidator {

    @Override
    public String name() {
        return "bounds";
    }

    @Override
    public List<String> validate(MarkingCandidate candidate, MarkingContext context) {
        List<String> violations = new ArrayList<>();
        Set<UUID> inScope = context.points().stream()
                .map(p -> p.id())
                .collect(Collectors.toSet());
        Set<UUID> seen = new java.util.HashSet<>();
        for (MarkingCandidate.Allocation allocation : candidate.allocations()) {
            if (allocation.markPointId() == null || !inScope.contains(allocation.markPointId())) {
                violations.add("allocation references unknown mark point "
                        + allocation.ref());
            }
            if (allocation.markPointId() != null && !seen.add(allocation.markPointId())) {
                violations.add("duplicate allocation for mark point " + allocation.ref());
            }
        }
        return violations;
    }
}
