/**
 * Classroom foundation (V51, TFA-01 + TFA-02; TEACHER_ARCHITECTURE.md §4.2,
 * §8, §9): the explicit Class entity, class membership, teacher
 * announcements, and the learner classroom overlay.
 *
 * <p>Boundaries:</p>
 * <ul>
 *   <li>course identity is the hub's — course_slug/course_label are opaque
 *       refs (the V47/V48/V49 ruling); core has no course registry;</li>
 *   <li>membership rows are the ONLY source of classroom visibility on the
 *       learner side (the independent-student rule: no membership, no
 *       classroom capability, honest empty reads);</li>
 *   <li>classroom traffic never writes learner-model state — no BKT, no
 *       SkillState, no misconceptions, no review schedules (pinned by
 *       ClassroomFlowIT);</li>
 *   <li>announcements are teacher-to-class communication, never an AI
 *       channel (§9);</li>
 *   <li>teaching coverage (taught-vs-not-taught) is TFA-03 and lives
 *       nowhere in this package.</li>
 * </ul>
 */
package com.syllabai.classroom;
