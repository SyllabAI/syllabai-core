package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.identity.AuthService;
import com.syllabai.identity.Role;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.learner.LearnerAgendaController;
import com.syllabai.learner.LearnerStateController;
import com.syllabai.learner.dto.AgendaView;
import com.syllabai.learner.dto.LearnerStateView;
import com.syllabai.learner.exam.CourseExamTargetView;
import com.syllabai.learner.exam.ExamSeriesRepository;
import com.syllabai.learner.exam.ExamSeriesView;
import com.syllabai.learner.exam.LearnerCourseEnrolmentRepository;
import com.syllabai.learner.exam.LearnerExamSeriesController;
import com.syllabai.learner.exam.LearnerExamSeriesController.SetTargetRequest;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import com.syllabai.teacher.ingestion.ExamSeriesDatasetDto;
import com.syllabai.teacher.ingestion.ExamSeriesDatasetDto.SeriesRow;
import com.syllabai.teacher.ingestion.ExamSeriesImportService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: the exam-series calendar lane (T-C79, ADR-035 D1
 * ruling 2026-10-04) against a real Postgres. Pins the whole contract:
 *
 * <ul>
 *   <li>the V63 seed IS the cited Pearson 2026-2027 set — 5 published
 *       sittings, every row carrying an https citation + retrieval stamp,
 *       picker-ordered soonest-window-first, qualification filter exact;</li>
 *   <li>the target flow — declare (idempotent upsert), read through BOTH
 *       read models (/state and /agenda) with the countdown derived at read
 *       (the window arithmetic pinned structurally, the honest
 *       "entries closed" fact pinned on the already-passed Oct 2026
 *       deadline), clear ("not sure yet" keeps the enrolment row);</li>
 *   <li>fail-closed import — no citation, deadline-after-window,
 *       results-before-window, non-https source: each refuses;</li>
 *   <li>re-import semantics — identical = unchanged, a corrected calendar
 *       moves the measured fields WITH its newer citation;</li>
 *   <li>learner scoping — targets never leak across learners.</li>
 * </ul>
 *
 * <p>Runs in CI where Docker exists; skipped locally otherwise (the
 * AgendaFlowIT posture). The teacher import controller carries the
 * class-level role check; invoked directly it sees the installed
 * SecurityContext (the AssignmentFlowIT pattern).</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class ExamSeriesFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired
    private AuthService authService;
    @Autowired
    private LearnerExamSeriesController examController;
    @Autowired
    private LearnerStateController stateController;
    @Autowired
    private LearnerAgendaController agendaController;
    @Autowired
    private ExamSeriesImportService importService;
    @Autowired
    private ExamSeriesRepository examSeries;
    @Autowired
    private LearnerCourseEnrolmentRepository enrolments;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void removeImportFixtureRows() {
        // the re-import test writes its "-it" fixture row into the SAME
        // calendar table the V63 seed occupies; without this cleanup the row
        // leaks into seededCalendarIsTheCitedSet's strict hasSize(5) pin
        // whenever JUnit method order puts the import test first (observed in
        // core-ci run 37201075670)
        examSeries.findByBoardAndQualificationAndSeriesCode(
                "PEARSON_EDEXCEL", "INTERNATIONAL_GCSE", "2028-may-june-it")
                .ifPresent(examSeries::delete);
    }

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-exam-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    private void installTeacher(UUID teacherId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(teacherId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }

    private UUID newTeacher() {
        return authService.provisionUser(
                "it-exam-teacher-" + UUID.randomUUID().toString().substring(0, 8)
                        + "@syllabai.test",
                "ItTeacher123!", "It Teacher", Set.of(Role.TEACHER)).id();
    }

    private SeriesRow row(SeriesRow.Qualification qualification, String seriesCode,
                          LocalDate start, LocalDate end, LocalDate entry, LocalDate results,
                          String sourceUrl) {
        return new SeriesRow(qualification, seriesCode, seriesCode + " sitting", start, end,
                entry, results, true, false, sourceUrl);
    }

    @Test
    @DisplayName("the seeded calendar IS the cited Pearson 2026-2027 set, picker-ordered")
    void seededCalendarIsTheCitedSet() {
        UUID learner = newLearner();

        List<ExamSeriesView> calendar = examController.calendar(learner, null);

        assertThat(calendar).hasSize(5);
        assertThat(calendar).allSatisfy(s -> {
            assertThat(s.sourceUrl()).startsWith("https://qualifications.pearson.com/");
            assertThat(s.retrievedAt()).isNotNull();
            assertThat(s.estimated()).isFalse();
        });
        assertThat(calendar).extracting(ExamSeriesView::seriesCode)
                .containsExactlyInAnyOrder("2026-october-november", "2026-november",
                        "2027-january", "2027-may-june", "2027-may-june");
        // soonest window first (the picker's order)
        assertThat(calendar).extracting(ExamSeriesView::windowStart).isSorted();
        // qualification filter is exact: IAL 3 sittings, International GCSE 2
        assertThat(examController.calendar(learner, "IAL")).hasSize(3)
                .allSatisfy(s -> assertThat(s.qualification()).isEqualTo("IAL"));
        assertThat(examController.calendar(learner, "INTERNATIONAL_GCSE")).hasSize(2)
                .allSatisfy(s -> assertThat(s.qualification()).isEqualTo("INTERNATIONAL_GCSE"));
    }

    @Test
    @DisplayName("declare → both read models expose the derived countdown; clear keeps the enrolment")
    void targetRoundTripExposesDerivedCountdown() {
        UUID learner = newLearner();
        UUID ialJan2027 = examController.calendar(learner, "IAL").stream()
                .filter(s -> s.seriesCode().equals("2027-january")).findFirst().orElseThrow().id();
        UUID ialOct2026 = examController.calendar(learner, "IAL").stream()
                .filter(s -> s.seriesCode().equals("2026-october-november"))
                .findFirst().orElseThrow().id();

        CourseExamTargetView target =
                examController.setTarget(learner, "igcse-chemistry-19",
                        new SetTargetRequest(ialJan2027));

        assertThat(target.courseSlug()).isEqualTo("igcse-chemistry-19");
        assertThat(target.seriesCode()).isEqualTo("2027-january");
        // the countdown is DERIVED: window length arithmetic must hold
        // (2027-01-08 → 2027-01-25 = 17 days — both horizons are measured
        // from the same read-day clock, so end − start is the window length;
        // the start horizon itself is negative iff the window has opened)
        assertThat(target.daysToWindowEnd() - target.daysToWindowStart()).isEqualTo(17);
        assertThat(target.entryDeadlinePassed()).isFalse();

        // both read models carry the block, with the same derived facts
        LearnerStateView state = stateController.state(learner);
        assertThat(state.examTargets()).hasSize(1);
        assertThat(state.examTargets().get(0).seriesCode()).isEqualTo("2027-january");
        AgendaView agenda = agendaController.agenda(learner, null);
        assertThat(agenda.examTargets()).hasSize(1);
        assertThat(agenda.examTargets().get(0).daysToWindowStart())
                .isEqualTo(target.daysToWindowStart());

        // the honest "entries closed" fact on the already-passed Oct 2026 deadline
        CourseExamTargetView closed =
                examController.setTarget(learner, "igcse-physics-19",
                        new SetTargetRequest(ialOct2026));
        assertThat(closed.entryDeadlinePassed()).isTrue();

        // a learner with no declaration sees the honest empty state
        UUID other = newLearner();
        assertThat(stateController.state(other).examTargets()).isEmpty();

        // clear: the target goes (state honest-empty again), the enrolment row remains
        examController.clearTarget(learner, "igcse-chemistry-19");
        assertThat(stateController.state(learner).examTargets())
                .noneMatch(t -> t.courseSlug().equals("igcse-chemistry-19"));
        assertThat(enrolments.findByLearnerIdAndCourseSlug(learner, "igcse-chemistry-19"))
                .isPresent()
                .get()
                .satisfies(e -> assertThat(e.targetSeriesId()).isNull());

        // an unknown series id is a real 404, never a silent declaration
        assertThatThrownBy(() -> examController.setTarget(learner, "igcse-chemistry-19",
                new SetTargetRequest(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("import fails closed: no citation, nonsense dates, non-https source")
    void importFailsClosed() {
        UUID teacher = newTeacher();
        installTeacher(teacher);
        String citation = "https://qualifications.pearson.com/exam-series-it-fixture";

        // a row without a citation cannot exist
        assertThatThrownBy(() -> importService.importDataset(new ExamSeriesDatasetDto(
                "PEARSON_EDEXCEL", java.time.Instant.now(),
                List.of(new SeriesRow(SeriesRow.Qualification.IAL, "2028-january",
                        "January 2028", LocalDate.of(2028, 1, 10), LocalDate.of(2028, 1, 28),
                        null, null, true, false, " ")))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("citation");

        // a deadline after the window start is a transcription error
        assertThatThrownBy(() -> importService.importDataset(new ExamSeriesDatasetDto(
                "PEARSON_EDEXCEL", java.time.Instant.now(),
                List.of(row(SeriesRow.Qualification.IAL, "2028-january",
                        LocalDate.of(2028, 1, 10), LocalDate.of(2028, 1, 28),
                        LocalDate.of(2028, 2, 1), null, citation)))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("entry deadline");

        // results cannot land before the window ends
        assertThatThrownBy(() -> importService.importDataset(new ExamSeriesDatasetDto(
                "PEARSON_EDEXCEL", java.time.Instant.now(),
                List.of(row(SeriesRow.Qualification.IAL, "2028-january",
                        LocalDate.of(2028, 1, 10), LocalDate.of(2028, 1, 28),
                        null, LocalDate.of(2028, 1, 2), citation)))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("results date");

        // non-https "citations" are not citations
        assertThatThrownBy(() -> importService.importDataset(new ExamSeriesDatasetDto(
                "PEARSON_EDEXCEL", java.time.Instant.now(),
                List.of(row(SeriesRow.Qualification.IAL, "2028-january",
                        LocalDate.of(2028, 1, 10), LocalDate.of(2028, 1, 28),
                        null, null, "http://example.com/timetable.pdf")))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("https");
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("re-import: identical row is unchanged; a correction moves the fields WITH the citation")
    void reimportIsIdempotentAndCorrectionsCarryCitations() {
        UUID teacher = newTeacher();
        installTeacher(teacher);
        String v1 = "https://qualifications.pearson.com/exam-series-it-v1";
        String v2 = "https://qualifications.pearson.com/exam-series-it-v2";

        SeriesRow original = row(SeriesRow.Qualification.INTERNATIONAL_GCSE,
                "2028-may-june-it", LocalDate.of(2028, 5, 8), LocalDate.of(2028, 6, 16),
                LocalDate.of(2028, 3, 21), LocalDate.of(2028, 8, 16), v1);
        var first = importService.importDataset(new ExamSeriesDatasetDto(
                "PEARSON_EDEXCEL", java.time.Instant.now(), List.of(original)));
        assertThat(first.imported()).isEqualTo(1);

        var again = importService.importDataset(new ExamSeriesDatasetDto(
                "PEARSON_EDEXCEL", java.time.Instant.now(), List.of(original)));
        assertThat(again.unchanged()).isEqualTo(1);
        assertThat(again.updated()).isZero();

        // Pearson revises the window: the measured fields move, the citation moves with them
        SeriesRow corrected = row(SeriesRow.Qualification.INTERNATIONAL_GCSE,
                "2028-may-june-it", LocalDate.of(2028, 5, 9), LocalDate.of(2028, 6, 16),
                LocalDate.of(2028, 3, 21), LocalDate.of(2028, 8, 16), v2);
        var third = importService.importDataset(new ExamSeriesDatasetDto(
                "PEARSON_EDEXCEL", java.time.Instant.now(), List.of(corrected)));
        assertThat(third.updated()).isEqualTo(1);

        var stored = examSeries.findByBoardAndQualificationAndSeriesCode(
                "PEARSON_EDEXCEL", "INTERNATIONAL_GCSE", "2028-may-june-it").orElseThrow();
        assertThat(stored.windowStart()).isEqualTo(LocalDate.of(2028, 5, 9));
        assertThat(stored.sourceUrl()).isEqualTo(v2);
    }
}
