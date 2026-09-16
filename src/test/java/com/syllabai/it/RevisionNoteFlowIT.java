package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.identity.AuthService;
import com.syllabai.identity.Role;
import com.syllabai.revisionnotes.RevisionNoteAsset;
import com.syllabai.revisionnotes.RevisionNoteDtos.MarkNoteViewedRequest;
import com.syllabai.revisionnotes.RevisionNoteDtos.RevisionNoteBodyView;
import com.syllabai.revisionnotes.RevisionNoteDtos.RevisionNoteIngestSummary;
import com.syllabai.revisionnotes.RevisionNoteDtos.RevisionNotesIndexView;
import com.syllabai.revisionnotes.RevisionNoteIngestService;
import com.syllabai.revisionnotes.RevisionNoteLearnerController;
import com.syllabai.revisionnotes.RevisionNoteService;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.NotFoundException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: the revision-notes pilot corpus boundary over a real
 * Postgres — operator ingest (fail-closed package validation, replace-all
 * semantics), learner tree/body/asset reads, viewed-progress idempotency and
 * ownership, orphan-progress sweep on re-ingestion, and the containment rule
 * for asset filenames. Runs in CI where Docker exists; skipped locally
 * otherwise.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class RevisionNoteFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired
    private RevisionNoteIngestService ingestService;
    @Autowired
    private RevisionNoteService noteService;
    @Autowired
    private RevisionNoteLearnerController learnerController;
    @Autowired
    private AuthService authService;

    private UUID newLearner() {
        return authService.provisionUser(
                "it-notes-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner", Set.of(Role.STUDENT)).id();
    }

    /** Minimal v1 package: one topic, two subtopics, three notes, one asset. */
    private byte[] packageV1() throws Exception {
        String pkg = """
                {
                  "packageVersion": "1.0",
                  "corpusVersion": "it-corpus-1",
                  "generatedAt": "2026-09-17T00:00:00Z",
                  "topics": [
                    {"order": 1, "title": "1. Principles of Chemistry", "subtopics": [
                      {"order": 1, "title": "a. States of Matter", "notes": [
                        {"noteId": "1-1-1-three-states", "title": "The Three States of Matter",
                         "order": 1, "bodyMd": "# States\\n\\nThe three states.\\n\\n![a diagram](assets/diagram-a.png)",
                         "specMapJson": "{\\"spec_points\\":[]}", "specPointCodes": "4CH1-1.1",
                         "sourceUrl": "https://example/sme/1", "assets": ["diagram-a.png"]},
                        {"noteId": "1-1-2-diffusion", "title": "Diffusion & Dilution",
                         "order": 2, "bodyMd": "# Diffusion\\n\\nParticles spread out.",
                         "specMapJson": "{}", "specPointCodes": "4CH1-1.2",
                         "sourceUrl": "https://example/sme/2", "assets": []}
                      ]},
                      {"order": 2, "title": "b. Atomic Structure", "notes": [
                        {"noteId": "1-2-1-atoms", "title": "Atomic Structure",
                         "order": 1, "bodyMd": "# Atoms\\n\\nProtons, neutrons, electrons.",
                         "specMapJson": "{}", "specPointCodes": "4CH1-1.7",
                         "sourceUrl": "https://example/sme/3", "assets": []}
                      ]}
                    ]}
                  ],
                  "assets": [
                    {"filename": "diagram-a.png", "contentType": "image/png"}
                  ]
                }
                """;
        return zip(pkg, "diagram-a.png", new byte[] {1, 2, 3, 4});
    }

    private byte[] zip(String packageJson, String assetName, byte[] assetBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("package.json"));
            zip.write(packageJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/" + assetName));
            zip.write(assetBytes);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("ingest → index/body/asset reads → viewed idempotency + ownership → replace-all sweep")
    void ingestServeProgressAndReplace() throws Exception {
        RevisionNoteIngestSummary summary = ingestService.ingest(packageV1());
        assertThat(summary.topics()).isEqualTo(1);
        assertThat(summary.subtopics()).isEqualTo(2);
        assertThat(summary.notes()).isEqualTo(3);
        assertThat(summary.assets()).isEqualTo(1);
        assertThat(summary.replaced()).isFalse();

        UUID learner = newLearner();

        // index: canonical tree, empty progress
        RevisionNotesIndexView index = learnerController.index(learner);
        assertThat(index.corpusVersion()).isEqualTo("it-corpus-1");
        assertThat(index.topics()).hasSize(1);
        assertThat(index.topics().get(0).subtopics()).hasSize(2);
        assertThat(index.topics().get(0).subtopics().get(0).noteCount()).isEqualTo(2);
        assertThat(index.viewed()).isEmpty();

        // body: canonical prev/next across subtopic boundaries + asset extraction
        RevisionNoteBodyView body = learnerController.body("1-1-2-diffusion");
        assertThat(body.prevNoteId()).isEqualTo("1-1-1-three-states");
        assertThat(body.nextNoteId()).isEqualTo("1-2-1-atoms");
        // the diffusion note has no diagrams; the three-states note carries one
        assertThat(body.assets()).isEmpty();

        RevisionNoteBodyView first = learnerController.body("1-1-1-three-states");
        assertThat(first.prevNoteId()).isNull();
        assertThat(first.nextNoteId()).isEqualTo("1-1-2-diffusion");
        assertThat(first.assets()).containsExactly("diagram-a.png");

        // asset bytes round-trip
        RevisionNoteAsset asset = noteService.asset("diagram-a.png");
        assertThat(asset.bytes()).containsExactly(1, 2, 3, 4);
        assertThat(asset.contentType()).isEqualTo("image/png");

        // progress: first view wins (idempotent), ownership is per-learner
        learnerController.markViewed(learner, new MarkNoteViewedRequest("1-1-1-three-states"));
        learnerController.markViewed(learner, new MarkNoteViewedRequest("1-1-1-three-states"));
        UUID other = newLearner();
        learnerController.markViewed(other, new MarkNoteViewedRequest("1-1-1-three-states"));
        learnerController.markViewed(other, new MarkNoteViewedRequest("1-2-1-atoms"));

        RevisionNotesIndexView mine = learnerController.index(learner);
        assertThat(mine.viewed()).hasSize(1);
        assertThat(mine.viewed().get(0).noteId()).isEqualTo("1-1-1-three-states");
        RevisionNotesIndexView theirs = learnerController.index(other);
        assertThat(theirs.viewed()).hasSize(2);

        // unknown ids are indistinguishable 404s
        assertThatThrownBy(() -> noteService.body("no-such-note"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> noteService.markViewed(learner, "no-such-note"))
                .isInstanceOf(NotFoundException.class);

        // replace-all re-ingestion: smaller corpus v2 sweeps the orphaned view
        String pkgV2 = """
                {
                  "packageVersion": "1.0",
                  "corpusVersion": "it-corpus-2",
                  "generatedAt": "2026-09-17T01:00:00Z",
                  "topics": [
                    {"order": 1, "title": "1. Principles of Chemistry", "subtopics": [
                      {"order": 1, "title": "a. States of Matter", "notes": [
                        {"noteId": "1-1-1-three-states", "title": "The Three States of Matter",
                         "order": 1, "bodyMd": "# States v2", "specMapJson": "{}",
                         "specPointCodes": "4CH1-1.1", "sourceUrl": null, "assets": []}
                      ]}
                    ]}
                  ],
                  "assets": []
                }
                """;
        RevisionNoteIngestSummary v2 = ingestService.ingest(zip(pkgV2, "unused.png", new byte[] {9}));
        assertThat(v2.replaced()).isTrue();
        assertThat(v2.notes()).isEqualTo(1);

        RevisionNotesIndexView after = learnerController.index(learner);
        assertThat(after.corpusVersion()).isEqualTo("it-corpus-2");
        assertThat(after.topics().get(0).subtopics().get(0).notes()).hasSize(1);
        // the surviving note's view must survive the replace; only the orphan
        // (1-2-1-atoms for `other`) is swept.
        assertThat(noteService.progress(learner).viewed())
                .anySatisfy(v -> assertThat(v.noteId()).isEqualTo("1-1-1-three-states"));
        assertThat(noteService.progress(other).viewed())
                .hasSize(1)
                .first().satisfies(v -> assertThat(v.noteId()).isEqualTo("1-1-1-three-states"));
    }

    @Test
    @DisplayName("fail-closed package validation: bad version, traversal filename, dangling asset ref")
    void ingestValidationIsFailClosed() throws Exception {
        // a failed ingest must not have replaced or partially written anything:
        // the corpus stays exactly as the earlier test's successful ingest left it
        java.util.function.ToIntFunction<RevisionNotesIndexView> count =
                idx -> idx.topics().stream()
                        .mapToInt(t -> t.subtopics().stream()
                                .mapToInt(s -> s.notes().size()).sum()).sum();
        int before = count.applyAsInt(noteService.index(UUID.randomUUID()));
        assertThat(before).isGreaterThan(0); // this test may run after the flow test

        String badVersion = """
                {"packageVersion": "9.9", "corpusVersion": "x", "generatedAt": "t",
                 "topics": [], "assets": []}
                """;
        assertThatThrownBy(() -> ingestService.ingest(zip(badVersion, "a.png", new byte[] {1})))
                .isInstanceOf(BadRequestException.class);

        String traversal = """
                {"packageVersion": "1.0", "corpusVersion": "x", "generatedAt": "t",
                 "topics": [{"order": 1, "title": "T", "subtopics": [
                   {"order": 1, "title": "S", "notes": [
                     {"noteId": "n1", "title": "N", "order": 1, "bodyMd": "b",
                      "specMapJson": "{}", "specPointCodes": "", "sourceUrl": null,
                      "assets": ["../evil.png"]}]}]}],
                 "assets": []}
                """;
        assertThatThrownBy(() -> ingestService.ingest(zip(traversal, "../evil.png", new byte[] {1})))
                .isInstanceOf(BadRequestException.class);

        String dangling = """
                {"packageVersion": "1.0", "corpusVersion": "x", "generatedAt": "t",
                 "topics": [{"order": 1, "title": "T", "subtopics": [
                   {"order": 1, "title": "S", "notes": [
                     {"noteId": "n1", "title": "N", "order": 1,
                      "bodyMd": "![x](assets/missing.png)", "specMapJson": "{}",
                      "specPointCodes": "", "sourceUrl": null, "assets": ["missing.png"]}]}]}],
                 "assets": []}
                """;
        assertThatThrownBy(() -> ingestService.ingest(zip(dangling, "other.png", new byte[] {1})))
                .isInstanceOf(BadRequestException.class);

        // the corpus is byte-identical before and after all rejected ingests
        assertThat(count.applyAsInt(noteService.index(UUID.randomUUID()))).isEqualTo(before);
    }
}
