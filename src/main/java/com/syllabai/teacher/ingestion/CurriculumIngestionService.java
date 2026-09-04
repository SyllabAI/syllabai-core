package com.syllabai.teacher.ingestion;

import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.CurriculumVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.knowledge.KnowledgeEdge;
import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.knowledge.RelationType;
import com.syllabai.shared.ConflictException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingests a syllabai-parser curriculum draft into the curriculum tables and the
 * knowledge graph (T-010 bridge, Master Spec §7/§17). Deterministic, whole-draft,
 * single transaction: either the full curriculum seed lands or nothing does.
 *
 * <p>Every created node and PART_OF edge carries full provenance — source
 * document id, spec section, extraction method, checksum — and lands in
 * SUGGESTED state. The pipeline never invents structure it was not given:
 * prerequisite edges are <em>not</em> derived (no such signal exists in a
 * heading outline) — prerequisites stay a teacher/SME curation concern.
 * Re-ingesting the identical draft (same provenance fingerprint) is an
 * idempotent no-op; the same node codes with a different provenance fail
 * loudly instead of silently double-seeding the graph.</p>
 */
@Service
public class CurriculumIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CurriculumIngestionService.class);

    private final CurriculumVersionRepository curriculumVersions;
    private final SubjectRepository subjects;
    private final KnowledgeNodeRepository knowledgeNodes;
    private final KnowledgeEdgeRepository knowledgeEdges;

    public CurriculumIngestionService(CurriculumVersionRepository curriculumVersions,
                                      SubjectRepository subjects,
                                      KnowledgeNodeRepository knowledgeNodes,
                                      KnowledgeEdgeRepository knowledgeEdges) {
        this.curriculumVersions = curriculumVersions;
        this.subjects = subjects;
        this.knowledgeNodes = knowledgeNodes;
        this.knowledgeEdges = knowledgeEdges;
    }

    @Transactional
    public IngestionSummary ingest(CurriculumDraftDto draft, UUID ingestedBy) {
        validate(draft);

        CurriculumVersion version = resolveVersion(draft);
        Subject subject = resolveSubject(draft, version);
        KnowledgeNode subjectRoot = resolveSubjectRoot(draft, subject, ingestedBy);

        String namespace = namespaceOf(draft.code());
        String fingerprint = fingerprint(draft);

        int units = 0;
        int topics = 0;
        int subtopics = 0;
        for (CurriculumDraftDto.UnitDraft unitDraft : draft.units()) {
            KnowledgeNode unit = resolveNode(
                    qualifiedCode(namespace, unitDraft.code()), NodeType.UNIT,
                    bound(unitDraft.title(), 200),
                    descriptionOf(unitDraft.pageNumber(), draft.provenance(), unitDraft.confidence()),
                    fingerprint, ingestedBy);
            attach(unit, subjectRoot, fingerprint, ingestedBy);
            units++;

            for (CurriculumDraftDto.TopicDraft topicDraft : unitDraft.topics()) {
                KnowledgeNode topic = resolveNode(
                        qualifiedCode(namespace, topicDraft.code()), NodeType.TOPIC,
                        bound(topicDraft.title(), 200),
                        descriptionOf(topicDraft.pageNumber(), draft.provenance(), topicDraft.confidence()),
                        fingerprint, ingestedBy);
                attach(topic, unit, fingerprint, ingestedBy);
                topics++;

                for (CurriculumDraftDto.TopicDraft subDraft : topicDraft.subtopics()) {
                    KnowledgeNode subtopic = resolveNode(
                            qualifiedCode(namespace, subDraft.code()), NodeType.SUBTOPIC,
                            bound(subDraft.title(), 200),
                            descriptionOf(subDraft.pageNumber(), draft.provenance(), subDraft.confidence()),
                            fingerprint, ingestedBy);
                    attach(subtopic, topic, fingerprint, ingestedBy);
                    subtopics++;
                }
            }
        }

        log.info("ingested curriculum {}: {} units, {} topics, {} subtopics (all SUGGESTED)",
                draft.code(), units, topics, subtopics);
        return new IngestionSummary(version.id(), subject.id(), subjectRoot.id(),
                units, topics, subtopics);
    }

    private void validate(CurriculumDraftDto draft) {
        if (draft.board() == null || draft.board().isBlank()
                || draft.qualification() == null || draft.qualification().isBlank()
                || draft.code() == null || draft.code().isBlank()) {
            throw new ConflictException("draft has no board/qualification/code identity");
        }
        if (draft.subject() == null || draft.subject().code() == null
                || draft.subject().code().isBlank()) {
            throw new ConflictException("draft has no subject");
        }
        if (draft.units() == null || draft.units().isEmpty()) {
            throw new ConflictException("draft has no units — nothing to ingest");
        }
        if (draft.provenance() == null || draft.provenance().sourceDocumentId() == null
                || draft.provenance().sourceChecksum() == null) {
            throw new ConflictException("draft carries no source provenance (§17) — refusing");
        }
        if (!"SUGGESTED".equals(draft.provenance().validationStatus())) {
            throw new ConflictException("parser drafts must arrive SUGGESTED, got "
                    + draft.provenance().validationStatus());
        }
    }

    private CurriculumVersion resolveVersion(CurriculumDraftDto draft) {
        return curriculumVersions
                .findByBoardAndQualificationAndCode(draft.board(), draft.qualification(), draft.code())
                .orElseGet(() -> curriculumVersions.save(new CurriculumVersion(
                        draft.board(), draft.qualification(), draft.code(),
                        bound(draft.title() == null ? draft.code() : draft.title(), 200),
                        CurriculumVersion.Status.DRAFT)));
    }

    private Subject resolveSubject(CurriculumDraftDto draft, CurriculumVersion version) {
        return subjects.findByCurriculumVersionIdAndCode(version.id(), draft.subject().code())
                .orElseGet(() -> subjects.save(new Subject(version,
                        bound(draft.subject().code(), 20),
                        bound(draft.subject().name() == null
                                ? draft.subject().code() : draft.subject().name(), 100))));
    }

    private KnowledgeNode resolveSubjectRoot(CurriculumDraftDto draft, Subject subject,
                                             UUID ingestedBy) {
        if (subject.knowledgeNodeId() != null) {
            KnowledgeNode existing = knowledgeNodes.findById(subject.knowledgeNodeId()).orElse(null);
            if (existing != null) {
                return existing;      // e.g. the V6 CHM root — spec nodes join the same tree
            }
        }
        KnowledgeNode root = knowledgeNodes.save(new KnowledgeNode(
                qualifiedCode(namespaceOf(draft.code()), "ROOT"), NodeType.SUBJECT,
                bound(draft.subject().name() == null ? draft.code() : draft.subject().name(), 200),
                "Subject root for " + draft.code() + " (created by curriculum ingestion)",
                KnowledgeNode.ValidationStatus.SUGGESTED,
                fingerprint(draft), author(ingestedBy)));
        subject.linkKnowledgeNode(root.id());
        return root;
    }

    /**
     * Idempotent node resolution: same code + same draft fingerprint → reuse;
     * same code + different origin → conflict (never silently re-seed the graph).
     */
    private KnowledgeNode resolveNode(String code, NodeType type, String title,
                                      String description, String fingerprint, UUID ingestedBy) {
        return knowledgeNodes.findByCode(code)
                .map(existing -> {
                    if (!fingerprint.equals(existing.provenance())) {
                        throw new ConflictException("knowledge node " + code
                                + " already exists with different provenance — resolve manually");
                    }
                    return existing;
                })
                .orElseGet(() -> knowledgeNodes.save(new KnowledgeNode(
                        code, type, title, description,
                        KnowledgeNode.ValidationStatus.SUGGESTED, fingerprint, author(ingestedBy))));
    }

    private void attach(KnowledgeNode child, KnowledgeNode parent, String fingerprint,
                        UUID ingestedBy) {
        knowledgeEdges.findBySourceIdAndRelationType(child.id(), RelationType.PART_OF)
                .ifPresentOrElse(existing -> {
                    if (!fingerprint.equals(existing.provenance())) {
                        throw new ConflictException("PART_OF edge for " + child.code()
                                + " already exists with different provenance");
                    }
                }, () -> knowledgeEdges.save(new KnowledgeEdge(
                        child, parent, RelationType.PART_OF, null,
                        "spec outline hierarchy",
                        KnowledgeNode.ValidationStatus.SUGGESTED, fingerprint, author(ingestedBy))));
    }

    /** stable namespace for node codes, e.g. "IAL-CHEM-2018" → "IALCHEM2018" */
    private static String namespaceOf(String curriculumCode) {
        String sanitized = curriculumCode == null ? "" : curriculumCode.replaceAll("\\W+", "").toUpperCase();
        return sanitized.isEmpty() ? "CURRICULUM" : sanitized;
    }

    /** qualified node code, capped to the VARCHAR(40) column with a hash suffix */
    private static String qualifiedCode(String namespace, String draftCode) {
        String raw = namespace + "-" + (draftCode == null ? "" : draftCode);
        if (raw.length() <= 40) {
            return raw;
        }
        return raw.substring(0, 31) + "-" + String.format("%08x", raw.hashCode());
    }

    /** verbatim provenance fingerprint of the whole draft (§17: doc + method + checksum) */
    private static String fingerprint(CurriculumDraftDto draft) {
        CurriculumDraftDto.DraftProvenance p = draft.provenance();
        String checksum8 = p.sourceChecksum() == null ? ""
                : p.sourceChecksum().substring(0, Math.min(8, p.sourceChecksum().length()));
        return bound("curriculum:" + p.sourceDocumentId()
                + "|method:" + p.extractionMethod()
                + "|checksum:" + checksum8, 300);
    }

    private static String descriptionOf(Integer pageNumber,
                                        CurriculumDraftDto.DraftProvenance provenance,
                                        double confidence) {
        return bound("Spec " + (pageNumber == null ? "?" : "p" + pageNumber)
                + " — extracted by " + provenance.extractionMethod()
                + " (confidence " + confidence + ")", 1000);
    }

    private static String author(UUID ingestedBy) {
        return ingestedBy == null ? "curriculum-ingestion-v1" : bound(ingestedBy.toString(), 100);
    }

    /** null-safe truncation for VARCHAR columns fed from untrusted draft input */
    private static String bound(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * @param curriculumVersionId the (resolved or created) curriculum version
     * @param subjectId           the subject row
     * @param subjectRootNodeId   the KG subject root the tree hangs under
     * @param units               units created/reused
     * @param topics              topics created/reused
     * @param subtopics           subtopics created/reused
     */
    public record IngestionSummary(UUID curriculumVersionId, UUID subjectId,
                                   UUID subjectRootNodeId, int units, int topics, int subtopics) {
    }
}
