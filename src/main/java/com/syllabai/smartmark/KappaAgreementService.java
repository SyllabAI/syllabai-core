package com.syllabai.smartmark;

import java.util.List;

/**
 * Cohen's κ agreement computation for the Smart Mark release gate (F-161, Master
 * Spec §15 calibration). Pure domain logic — persistence lives in the service layer.
 *
 * <p>Unit of agreement: one <em>mark-point decision</em> (awarded 1 / not awarded 0)
 * — partial credit is naturally binarized at point granularity, which is how the
 * IGCSE/IAL schemes are written ("award this point if …").</p>
 */
public final class KappaAgreementService {

    private KappaAgreementService() {
    }

    /**
     * @param pairs paired decisions: [smart (0/1), human (0/1)] per mark point
     * @return κ and raw observed agreement
     */
    public static KappaStats cohenKappa(List<int[]> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            throw new IllegalArgumentException("kappa requires at least one paired decision");
        }
        int n = pairs.size();
        int smartYes = 0;
        int humanYes = 0;
        int bothYes = 0;
        int bothNo = 0;
        for (int[] pair : pairs) {
            // length check must precede element access — a 0/1-element array
            // would otherwise throw ArrayIndexOutOfBoundsException instead of
            // the documented IllegalArgumentException
            if (pair.length != 2) {
                throw new IllegalArgumentException("decisions must be binary 0/1 pairs");
            }
            int smart = pair[0];
            int human = pair[1];
            if ((smart != 0 && smart != 1) || (human != 0 && human != 1)) {
                throw new IllegalArgumentException("decisions must be binary 0/1 pairs");
            }
            if (smart == 1) smartYes++;
            if (human == 1) humanYes++;
            if (smart == 1 && human == 1) bothYes++;
            if (smart == 0 && human == 0) bothNo++;
        }

        double observed = (bothYes + bothNo) / (double) n;
        double pSmartYes = smartYes / (double) n;
        double pHumanYes = humanYes / (double) n;
        double expected = pSmartYes * pHumanYes + (1 - pSmartYes) * (1 - pHumanYes);

        // degenerate case: both markers awarded every point (or none) — chance
        // agreement is total, κ mathematically undefined. Convention (documented):
        // perfect agreement => κ = 1, any disagreement => κ = 0.
        double kappa;
        if (expected >= 1.0 - 1e-12) {
            kappa = observed >= 1.0 - 1e-12 ? 1.0 : 0.0;
        } else {
            kappa = (observed - expected) / (1.0 - expected);
        }
        return new KappaStats(kappa, observed, n);
    }

    /**
     * @param kappa             Cohen's κ
     * @param observedAgreement raw proportion of agreement
     * @param sampleSize        paired decisions the stats were computed over
     */
    public record KappaStats(double kappa, double observedAgreement, int sampleSize) {
    }
}
