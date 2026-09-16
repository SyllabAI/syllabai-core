package com.syllabai.revisionnotes;

import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Learner-facing reads over the ingested corpus + the viewed-progress model:
 * opening a note marks it viewed (Save-My-Exams semantics), rings are
 * viewed ÷ total per subtopic, and the index GET returns both tree and
 * viewed markers so one request powers the whole view.
 */
@Service
public class RevisionNoteService {

    private final RevisionNoteRepository notes;
    private final RevisionNoteAssetRepository assets;
    private final RevisionNoteViewedRepository viewed;

    public RevisionNoteService(RevisionNoteRepository notes,
            RevisionNoteAssetRepository assets,
            RevisionNoteViewedRepository viewed) {
        this.notes = notes;
        this.assets = assets;
        this.viewed = viewed;
    }

    public RevisionNoteDtos.RevisionNotesIndexView index(UUID userId) {
        List<RevisionNote> all = notes.findAllByOrderByTopicOrderAscSubtopicOrderAscNoteOrderAsc();
        if (all.isEmpty()) {
            return new RevisionNoteDtos.RevisionNotesIndexView(null, null, List.of(), List.of());
        }
        // group by (topic, subtopic) preserving canonical order
        Map<Integer, List<RevisionNote>> byTopic = new LinkedHashMap<>();
        for (RevisionNote n : all) {
            byTopic.computeIfAbsent(n.topicOrder(), k -> new ArrayList<>()).add(n);
        }
        List<RevisionNoteDtos.RevisionNotesIndexView.TopicView> topics = new ArrayList<>();
        for (var entry : byTopic.entrySet()) {
            List<RevisionNote> topicNotes = entry.getValue();
            Map<Integer, List<RevisionNote>> bySub = new LinkedHashMap<>();
            for (RevisionNote n : topicNotes) {
                bySub.computeIfAbsent(n.subtopicOrder(), k -> new ArrayList<>()).add(n);
            }
            List<RevisionNoteDtos.RevisionNotesIndexView.SubtopicView> subs = new ArrayList<>();
            for (var subEntry : bySub.entrySet()) {
                List<RevisionNote> subNotes = subEntry.getValue();
                List<RevisionNoteDtos.RevisionNotesIndexView.NoteMetaView> metas =
                        subNotes.stream()
                                .map(n -> new RevisionNoteDtos.RevisionNotesIndexView.NoteMetaView(
                                        n.noteId(), n.title(), n.noteOrder(),
                                        specCodes(n)))
                                .toList();
                RevisionNote first = subNotes.get(0);
                subs.add(new RevisionNoteDtos.RevisionNotesIndexView.SubtopicView(
                        first.subtopicOrder(), first.subtopicTitle(), subNotes.size(), metas));
            }
            RevisionNote first = topicNotes.get(0);
            topics.add(new RevisionNoteDtos.RevisionNotesIndexView.TopicView(
                    first.topicOrder(), first.topicTitle(), subs));
        }
        List<RevisionNoteDtos.RevisionNotesIndexView.ViewedView> viewedList =
                viewed.findByUserIdOrderByViewedAtDesc(userId).stream()
                        .map(v -> new RevisionNoteDtos.RevisionNotesIndexView.ViewedView(
                                v.noteId(), v.viewedAt()))
                        .toList();
        RevisionNote any = all.get(0);
        return new RevisionNoteDtos.RevisionNotesIndexView(
                any.corpusVersion(), any.ingestedAt(), topics, viewedList);
    }

    public RevisionNoteDtos.RevisionNoteBodyView body(String noteId) {
        List<RevisionNote> all = notes.findAllByOrderByTopicOrderAscSubtopicOrderAscNoteOrderAsc();
        Optional<Integer> at = Optional.empty();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).noteId().equals(noteId)) {
                at = Optional.of(i);
                break;
            }
        }
        RevisionNote note = at.map(all::get)
                .orElseThrow(() -> new NotFoundException("revision note " + noteId + " not found"));
        String prev = at.get() > 0 ? all.get(at.get() - 1).noteId() : null;
        String next = at.get() < all.size() - 1 ? all.get(at.get() + 1).noteId() : null;
        return new RevisionNoteDtos.RevisionNoteBodyView(note.noteId(), note.title(),
                note.bodyMd(), note.specMap(), note.sourceUrl(), assetFilenames(note), prev, next);
    }

    public RevisionNoteAsset asset(String filename) {
        return assets.findByFilename(filename)
                .orElseThrow(() -> new NotFoundException("revision note asset "
                        + filename + " not found"));
    }

    /** Idempotent: re-viewing an already-viewed note refreshes nothing (first view wins). */
    @Transactional
    public RevisionNoteDtos.RevisionNotesIndexView.ViewedView markViewed(UUID userId, String noteId) {
        if (notes.findById(noteId).isEmpty()) {
            throw new NotFoundException("revision note " + noteId + " not found");
        }
        RevisionNoteViewed existing = viewed.findByUserIdAndNoteId(userId, noteId).orElse(null);
        if (existing != null) {
            return new RevisionNoteDtos.RevisionNotesIndexView.ViewedView(
                    existing.noteId(), existing.viewedAt());
        }
        RevisionNoteViewed saved = viewed.save(new RevisionNoteViewed(userId, noteId, Instant.now()));
        return new RevisionNoteDtos.RevisionNotesIndexView.ViewedView(saved.noteId(), saved.viewedAt());
    }

    public RevisionNoteDtos.RevisionNoteProgressView progress(UUID userId) {
        List<RevisionNoteDtos.RevisionNotesIndexView.ViewedView> list =
                viewed.findByUserIdOrderByViewedAtDesc(userId).stream()
                        .map(v -> new RevisionNoteDtos.RevisionNotesIndexView.ViewedView(
                                v.noteId(), v.viewedAt()))
                        .toList();
        return new RevisionNoteDtos.RevisionNoteProgressView(list);
    }

    private List<String> specCodes(RevisionNote n) {
        if (n.specPointCodes() == null || n.specPointCodes().isBlank()) {
            return List.of();
        }
        return List.of(n.specPointCodes().split(","));
    }

    /**
     * Asset filenames referenced by the note body. The corpus generator rewrites
     * every image ref to the flattened {@code assets/<filename>} form, so the
     * body is the single source of truth; the frontend needs the list to fetch
     * the images through the authenticated asset endpoint.
     */
    private List<String> assetFilenames(RevisionNote note) {
        return ASSET_REF_FINDER.matcher(note.bodyMd()).results()
                .map(m -> m.group(1))
                .distinct()
                .toList();
    }

    private static final java.util.regex.Pattern ASSET_REF_FINDER =
            java.util.regex.Pattern.compile("!\\[[^\\]]*\\]\\(assets/([^)\\s]+)\\)");
}
