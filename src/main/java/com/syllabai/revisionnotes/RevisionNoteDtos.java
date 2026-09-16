package com.syllabai.revisionnotes;

import java.time.Instant;
import java.util.List;

/**
 * Wire shapes of the revision-notes surface. The ingest package DTO is nested
 * here so the corpus package format lives in exactly one file (the corpus
 * generator script in syllabai-resources is its producer).
 */
public final class RevisionNoteDtos {

    private RevisionNoteDtos() {
    }

    /** Learner index: the full tree + the caller's viewed markers in one GET. */
    public record RevisionNotesIndexView(
            String corpusVersion,
            Instant ingestedAt,
            List<TopicView> topics,
            List<ViewedView> viewed) {

        public record TopicView(int order, String title, List<SubtopicView> subtopics) {
        }

        public record SubtopicView(int order, String title, int noteCount,
                List<NoteMetaView> notes) {
        }

        public record NoteMetaView(String noteId, String title, int order,
                List<String> specPointCodes) {
        }

        public record ViewedView(String noteId, Instant viewedAt) {
        }
    }

    /** One note's render payload; prev/next follow canonical corpus order. */
    public record RevisionNoteBodyView(
            String noteId,
            String title,
            String bodyMd,
            String specMapJson,
            String sourceUrl,
            List<String> assets,
            String prevNoteId,
            String nextNoteId) {
    }

    public record RevisionNoteProgressView(List<RevisionNotesIndexView.ViewedView> viewed) {
    }

    public record MarkNoteViewedRequest(String noteId) {
    }

    public record RevisionNoteIngestSummary(
            int topics, int subtopics, int notes, int assets, boolean replaced) {
    }

    public record RevisionNoteStatusView(
            boolean ingested, int notes, int assets, Instant ingestedAt,
            String corpusVersion) {
    }

    /** Corpus package format v1 (parsed from package.json inside the ZIP). */
    public record RevisionNotePackage(
            String packageVersion,
            String corpusVersion,
            String generatedAt,
            List<PkgTopic> topics,
            List<PkgAsset> assets) {

        public record PkgTopic(int order, String title, List<PkgSubtopic> subtopics) {
        }

        public record PkgSubtopic(int order, String title, List<PkgNote> notes) {
        }

        public record PkgNote(String noteId, String title, int order, String bodyMd,
                String specMapJson, String sourceUrl, List<String> assets) {
        }

        public record PkgAsset(String filename, String contentType) {
        }
    }
}
