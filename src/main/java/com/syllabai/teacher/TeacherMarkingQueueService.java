package com.syllabai.teacher;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.shared.BadRequestException;
import com.syllabai.smartmark.HumanMark;
import com.syllabai.smartmark.HumanMarkRepository;
import com.syllabai.smartmark.SmartMarkResult;
import com.syllabai.smartmark.SmartMarkResultRepository;
import com.syllabai.smartmark.SmartMarkService;
import com.syllabai.teacher.dto.TeacherViews;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Marking throughput lane (sprint 2 §6/§7): the deterministic, batched read
 * model behind the teacher marking surface, plus the bounded Smart Mark batch.
 *
 * <p>Ordering philosophy (§7 — defensible, deterministic, never a fabricated
 * confidence): the queue is grouped by exam paper so the reviewer holds ONE
 * mark scheme in working memory at a time; papers surface oldest-waiting
 * learner evidence first (the answers that have waited longest get marked
 * first — the class-mastery unlock is FIFO-fair); within a paper, attempts run
 * oldest-first, then part label, then id. Every tie is broken deterministically
 * down to the UUID. Ordering changes which answer a reviewer SEES first — it
 * never changes any mark, gate, or evidence semantic.</p>
 *
 * <p>Throughput metrics are counts of what happened (Master Spec honesty rule):
 * zero when nothing happened, absent κ evaluation reported as absent — never
 * an invented number.</p>
 */
@Service
public class TeacherMarkingQueueService {

    private static final Logger log = LoggerFactory.getLogger(TeacherMarkingQueueService.class);

    /** bound on one Smart Mark batch: partial batches are re-dispatchable */
    static final int SMART_MARK_BATCH_LIMIT = 50;

    private final AnswerRepository answers;
    private final ExamPaperRepository examPapers;
    private final SmartMarkResultRepository smartMarkResults;
    private final SmartMarkService smartMarkService;
    private final HumanMarkRepository humanMarks;
    private final com.syllabai.identity.UserRepository users;

    public TeacherMarkingQueueService(AnswerRepository answers,
                                       ExamPaperRepository examPapers,
                                       SmartMarkResultRepository smartMarkResults,
                                       SmartMarkService smartMarkService,
                                       HumanMarkRepository humanMarks,
                                       com.syllabai.identity.UserRepository users) {
        this.answers = answers;
        this.examPapers = examPapers;
        this.smartMarkResults = smartMarkResults;
        this.smartMarkService = smartMarkService;
        this.humanMarks = humanMarks;
        this.users = users;
    }

    /**
     * The v2 marking queue (§6): the state-filtered queue, deterministically
     * ordered (§7), grouped by paper, each item carrying its paper context,
     * the newest Smart Mark run, and the next-answer link so the UI can walk
     * "mark → next" without a second lookup. Batched: one queue query + one
     * paper lookup + one smart-mark lookup + one identity lookup.
     */
    public MarkingQueueView markingQueue(Answer.MarkingState state) {
        List<Answer> queue = answers.findByMarkingState(state);
        if (queue.isEmpty()) {
            return new MarkingQueueView(state.name(), List.of(), List.of());
        }

        // paper context in one lookup. Question-bank (SME) questions carry NO
        // paper — examPaperId is null by design — so nulls are filtered before
        // the repository call (findAllById rejects null elements). Their answers
        // still group under the null paperId below; the view layer renders that
        // group as the unfiled bucket (paper == null branches).
        Set<UUID> paperIds = queue.stream()
                .map(a -> a.attempt().question().examPaperId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, ExamPaper> papers = paperIds.isEmpty()
                ? Map.of()
                : examPapers.findAllById(paperIds).stream()
                        .collect(Collectors.toMap(ExamPaper::id, p -> p));

        // newest smart-mark run per answer in one lookup (oldest first, overwrite)
        Map<UUID, SmartMarkResult> latestSmart = new HashMap<>();
        for (SmartMarkResult run : smartMarkResults.findByAnswerIdsOrderByCreatedAtAsc(
                queue.stream().map(Answer::id).toList())) {
            latestSmart.put(run.answerId(), run);
        }

        // newest human mark per answer in one lookup (present on the
        // HUMAN_MARKED / OVERRIDDEN states; empty for PENDING)
        Map<UUID, HumanMark> latestHuman = new HashMap<>();
        for (HumanMark mark : humanMarks.findByAnswerIdsOrderByCreatedAtAsc(
                queue.stream().map(Answer::id).toList())) {
            latestHuman.put(mark.answerId(), mark);
        }

        // learner display names in one lookup (queue self-contained, T-029)
        Map<UUID, String> names = learnerNames(queue.stream()
                .map(a -> a.attempt().learnerId()).collect(Collectors.toSet()));

        // group by paper — LinkedHashMap keeps papers in first-seen order,
        // re-sorted below by the deterministic group ordering
        Map<UUID, List<Answer>> byPaper = new LinkedHashMap<>();
        for (Answer a : queue) {
            byPaper.computeIfAbsent(a.attempt().question().examPaperId(),
                    k -> new ArrayList<>()).add(a);
        }
        for (List<Answer> group : byPaper.values()) {
            group.sort(TeacherMarkingQueueService::compareWithinPaper);
        }

        // paper order: oldest waiting answer first, then more pending, then id
        List<List<Answer>> groups = new ArrayList<>(byPaper.values());
        Instant now = Instant.now();
        groups.sort((g1, g2) -> {
            int byOldest = g1.get(0).attempt().createdAt()
                    .compareTo(g2.get(0).attempt().createdAt());
            if (byOldest != 0) return byOldest;
            int bySize = Integer.compare(g2.size(), g1.size());
            if (bySize != 0) return bySize;
            // paperId is nullable (question-bank questions) — null-safe tie-break,
            // unfiled groups last; never a bare compareTo on a possibly-null id
            UUID p1 = g1.get(0).attempt().question().examPaperId();
            UUID p2 = g2.get(0).attempt().question().examPaperId();
            if (p1 != null && p2 != null) return p1.compareTo(p2);
            if (p1 == null && p2 == null) return 0;
            return p1 == null ? 1 : -1;
        });

        List<MarkingQueueItem> items = new ArrayList<>(queue.size());
        List<MarkingGroupView> groupViews = new ArrayList<>(groups.size());
        for (List<Answer> group : groups) {
            UUID paperId = group.get(0).attempt().question().examPaperId();
            // paperId is null for the unfiled (question-bank) group BY DESIGN —
            // and when the queue holds ZERO paper rows, `papers` is the immutable
            // Map.of() above, whose get(null) NPEs (immutable maps reject null
            // key queries; a state whose answers are ALL bank answers 500ed
            // exactly there — SMART_MARKED 2026-09-24, exposed once the detached
            // lazy lookups stopped throwing first). The unfiled group's paper
            // context is null by definition: skip the lookup entirely.
            ExamPaper paper = paperId == null ? null : papers.get(paperId);
            Instant oldestAt = group.get(0).attempt().createdAt();
            groupViews.add(new MarkingGroupView(
                    paperId,
                    paper == null ? null : paper.title(),
                    paper == null ? null : paper.sessionLabel(),
                    paper == null ? null : paper.paperCode(),
                    group.size(),
                    oldestAt,
                    oldestAt.isAfter(now) ? null : Duration.between(oldestAt, now).toHours()));
            for (Answer a : group) {
                items.add(TeacherMarkingQueueService.item(a, paper, names,
                        latestSmart, latestHuman));
            }
        }

        // the mark→next chain over the ordered items (null on the last item)
        for (int i = 0; i < items.size(); i++) {
            items.set(i, items.get(i).withNext(
                    i + 1 < items.size() ? items.get(i + 1).answer().answerId() : null));
        }
        return new MarkingQueueView(state.name(), groupViews, items);
    }

    /**
     * Marking throughput metrics (§6): workload by state, authoritative human
     * marks in the 24h/7d windows, pending-by-paper leaders, oldest pending
     * age, and the κ gate status. Read-only; counts only what happened.
     */
    public ThroughputView throughput() {
        Map<String, Long> byState = new HashMap<>();
        for (Answer.MarkingState s : Answer.MarkingState.values()) {
            byState.put(s.name(), 0L);
        }
        for (Object[] row : answers.countGroupedByMarkingState()) {
            byState.put(((Answer.MarkingState) row[0]).name(), (Long) row[1]);
        }

        Instant now = Instant.now();
        long human24h = humanMarks.countSince(now.minus(Duration.ofHours(24)));
        long human7d = humanMarks.countSince(now.minus(Duration.ofHours(24 * 7)));

        // pending-by-paper leaders from the pending queue itself (bounded,
        // batched read) — the papers that unlock the most learner evidence
        List<Answer> pending = answers.findByMarkingState(Answer.MarkingState.PENDING);
        Map<UUID, Integer> pendingByPaper = new HashMap<>();
        final Map<UUID, ExamPaper> paperLookup;
        if (!pending.isEmpty()) {
            Set<UUID> ids = pending.stream()
                    .map(a -> a.attempt().question().examPaperId())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            paperLookup = examPapers.findAllById(ids).stream()
                    .collect(Collectors.toMap(ExamPaper::id, p -> p));
            for (Answer a : pending) {
                pendingByPaper.merge(a.attempt().question().examPaperId(), 1, Integer::sum);
            }
        } else {
            paperLookup = Map.of();
        }
        List<PendingPaperView> leaders = pendingByPaper.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed()
                        // paperId is nullable (question-bank answers) — null-safe key
                        // tie-break, unfiled bucket last; never a bare natural-order
                        // compare on the nullable key
                        .thenComparing(Map.Entry.comparingByKey(
                                Comparator.nullsLast(Comparator.naturalOrder()))))
                .limit(5)
                .map(e -> {
                    ExamPaper p = paperLookup.get(e.getKey());
                    return new PendingPaperView(e.getKey(),
                            p == null ? null : p.title(),
                            p == null ? null : p.paperCode(),
                            e.getValue());
                })
                .toList();
        Instant oldestPending = pending.stream()
                .map(a -> a.attempt().createdAt())
                .min(Instant::compareTo)
                .orElse(null);

        return new ThroughputView(
                byState,
                human24h,
                human7d,
                leaders,
                oldestPending,
                oldestPending == null || oldestPending.isAfter(now)
                        ? null
                        : Duration.between(oldestPending, now).toHours());
    }

    /**
     * Bounded Smart Mark batch (§6): runs the EXISTING per-answer pipeline once
     * per answer — each item its own transaction, so one failure never rolls
     * back the others (partial success is the honest outcome). Idempotent:
     * answers no longer PENDING are SKIPPED, not re-marked. The κ gate and the
     * evidence contract are untouched — the batch only sequences calls.
     */
    public SmartMarkBatchView smartMarkBatch(List<UUID> answerIds) {
        if (answerIds == null || answerIds.isEmpty()) {
            throw new BadRequestException("answerIds must not be empty");
        }
        // de-duplicate, order-preserving
        Set<UUID> unique = new LinkedHashSet<>(answerIds);
        if (unique.size() > SMART_MARK_BATCH_LIMIT) {
            throw new BadRequestException("batch too large: " + unique.size()
                    + " > " + SMART_MARK_BATCH_LIMIT + " — dispatch smaller batches");
        }

        // one batched read: current state of every requested answer
        Map<UUID, Answer> current = new LinkedHashMap<>();
        for (Answer a : answers.findAllById(unique)) {
            current.put(a.id(), a);
        }

        List<SmartMarkBatchItem> results = new ArrayList<>(unique.size());
        for (UUID id : unique) {
            Answer a = current.get(id);
            if (a == null) {
                results.add(new SmartMarkBatchItem(id, "FAILED", null,
                        "answer not found"));
                continue;
            }
            if (a.markingState() != Answer.MarkingState.PENDING) {
                results.add(new SmartMarkBatchItem(id, "SKIPPED_ALREADY_MARKED",
                        a.marksAwarded(), "state " + a.markingState().name()));
                continue;
            }
            try {
                SmartMarkResult run = smartMarkService.markAnswer(id);
                results.add(new SmartMarkBatchItem(id,
                        run.validationPassed() ? "MARKED" : "FAILED",
                        run.validationPassed() ? run.marksAwarded() : null,
                        run.validationPassed() ? null
                                : (run.failureReason() == null ? "FAILED" : run.failureReason())));
            } catch (Exception e) {
                // per-item failure: honest reason, batch continues
                log.warn("smart-mark batch item {} failed: {}", id, e.getMessage());
                results.add(new SmartMarkBatchItem(id, "FAILED", null,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        long marked = results.stream().filter(r -> "MARKED".equals(r.outcome())).count();
        long skipped = results.stream()
                .filter(r -> "SKIPPED_ALREADY_MARKED".equals(r.outcome())).count();
        long failed = results.stream().filter(r -> "FAILED".equals(r.outcome())).count();
        return new SmartMarkBatchView(results.size(), marked, skipped, failed, results);
    }

    /** deterministic within-paper order: attempt age, then part label, then id */
    private static int compareWithinPaper(Answer a1, Answer a2) {
        int byAttempt = a1.attempt().createdAt().compareTo(a2.attempt().createdAt());
        if (byAttempt != 0) return byAttempt;
        int byAttemptId = a1.attempt().id().compareTo(a2.attempt().id());
        if (byAttemptId != 0) return byAttemptId;
        int byPart = a1.questionPart().label().compareTo(a2.questionPart().label());
        if (byPart != 0) return byPart;
        return a1.id().compareTo(a2.id());
    }

    private static MarkingQueueItem item(Answer a, ExamPaper paper,
                                         Map<UUID, String> names,
                                         Map<UUID, SmartMarkResult> latestSmart,
                                         Map<UUID, HumanMark> latestHuman) {
        Attempt attempt = a.attempt();
        SmartMarkResult smart = latestSmart.get(a.id());
        HumanMark human = latestHuman.get(a.id());
        TeacherViews.AnswerMarkingView base = TeacherViews.answer(a,
                names.get(attempt.learnerId()), smart, human);
        return new MarkingQueueItem(
                base, paper == null ? null : paper.id(),
                paper == null ? null : paper.title(),
                paper == null ? null : paper.sessionLabel(),
                paper == null ? null : paper.paperCode(),
                attempt.evidenceEmitted(),
                null);
    }

    private Map<UUID, String> learnerNames(Set<UUID> learnerIds) {
        if (learnerIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(learnerIds).stream()
                .collect(Collectors.toMap(com.syllabai.identity.User::id,
                        com.syllabai.identity.User::displayName, (a, b) -> a));
    }

    // ---- views ----

    public record MarkingQueueView(String state,
                                   List<MarkingGroupView> groups,
                                   List<MarkingQueueItem> items) {
    }

    /** one paper group: the marking-unit the reviewer works through */
    public record MarkingGroupView(UUID paperId, String paperTitle,
                                   String sessionLabel, String paperCode,
                                   int count, Instant oldestPendingAt,
                                   Long oldestWaitingHours) {
    }

    /** a queue item: the existing marking view + paper context + next link */
    public record MarkingQueueItem(TeacherViews.AnswerMarkingView answer,
                                   UUID paperId, String paperTitle,
                                   String sessionLabel, String paperCode,
                                   boolean evidenceEmitted,
                                   UUID nextAnswerId) {

        MarkingQueueItem withNext(UUID next) {
            return new MarkingQueueItem(answer, paperId, paperTitle,
                    sessionLabel, paperCode, evidenceEmitted, next);
        }
    }

    /** throughput metrics — counts of what happened, never estimates */
    public record ThroughputView(Map<String, Long> answersByState,
                                 long humanMarks24h, long humanMarks7d,
                                 List<PendingPaperView> pendingByPaper,
                                 Instant oldestPendingAt,
                                 Long oldestPendingHours) {
    }

    public record PendingPaperView(UUID paperId, String paperTitle,
                                   String paperCode, int pending) {
    }

    public record SmartMarkBatchView(int requested, long marked, long skipped,
                                     long failed, List<SmartMarkBatchItem> items) {
    }

    public record SmartMarkBatchItem(UUID answerId, String outcome,
                                     Integer marksAwarded, String reason) {
    }
}
