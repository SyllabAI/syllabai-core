package com.syllabai.revisionnotes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.shared.BadRequestException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator-facing corpus ingestion: a ZIP package produced by
 * {@code scripts/c13_build_note_package.py} in syllabai-resources containing
 * {@code package.json} (tree + note bodies + spec maps) and {@code assets/*}
 * (diagram PNGs).
 *
 * <p>Fail-closed validation: unknown package version, duplicate ids/orders,
 * traversal-looking asset names, asset references without a provided file,
 * invalid spec-map JSON — any violation rejects the whole package (400) and
 * leaves the previous corpus untouched (single transaction, replace-all).</p>
 */
@Service
public class RevisionNoteIngestService {

    private static final Logger log = LoggerFactory.getLogger(RevisionNoteIngestService.class);

    /** Package format this build understands; bump when the format changes. */
    static final String SUPPORTED_PACKAGE_VERSION = "1.0";

    /** Asset filenames are package-provided; keep them boring and traversal-free. */
    private static final java.util.regex.Pattern SAFE_FILENAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._()-]{0,511}");

    private final RevisionNoteRepository notes;
    private final RevisionNoteAssetRepository assets;
    private final RevisionNoteViewedRepository viewed;
    private final ObjectMapper objectMapper;

    public RevisionNoteIngestService(RevisionNoteRepository notes,
            RevisionNoteAssetRepository assets,
            RevisionNoteViewedRepository viewed,
            ObjectMapper objectMapper) {
        this.notes = notes;
        this.assets = assets;
        this.viewed = viewed;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RevisionNoteDtos.RevisionNoteIngestSummary ingest(byte[] zipBytes) {
        ParsedPackage parsed = unzip(zipBytes);
        RevisionNoteDtos.RevisionNotePackage pkg = parsed.pkg();
        validate(pkg, parsed.assetBytes().keySet());

        Instant now = Instant.now();
        boolean replaced = notes.count() > 0;

        notes.deleteAllInBatch();
        assets.deleteAllInBatch();

        for (var topic : pkg.topics()) {
            for (var sub : topic.subtopics()) {
                for (var n : sub.notes()) {
                    notes.save(new RevisionNote(n.noteId(), topic.order(), topic.title(),
                            sub.order(), sub.title(), n.order(), n.title(), n.bodyMd(),
                            n.specMapJson() == null ? "{}" : n.specMapJson(),
                            n.specPointCodes() == null ? "" : n.specPointCodes(),
                            n.sourceUrl(), now, pkg.corpusVersion()));
                }
            }
        }
        for (var a : pkg.assets()) {
            byte[] bytes = parsed.assetBytes().get(a.filename());
            assets.save(new RevisionNoteAsset(a.filename(), a.contentType(),
                    bytes.length, bytes, now));
        }
        // the orphan sweep is a bulk JPQL query against the DB — the freshly
        // saved corpus must be flushed first or surviving views would be
        // wrongly swept (the new rows are not yet visible to the subquery).
        notes.flush();
        assets.flush();
        int orphans = viewed.deleteOrphans();

        RevisionNoteDtos.RevisionNoteIngestSummary summary = new RevisionNoteDtos.RevisionNoteIngestSummary(
                pkg.topics().size(),
                pkg.topics().stream().mapToInt(t -> t.subtopics().size()).sum(),
                pkg.topics().stream().mapToInt(t -> t.subtopics().stream()
                        .mapToInt(s -> s.notes().size()).sum()).sum(),
                pkg.assets().size(),
                replaced);
        log.info("revision-notes ingest: {} topics, {} notes, {} assets, replaced={}, "
                + "orphaned-progress-swept={}",
                summary.topics(), summary.notes(), summary.assets(), replaced, orphans);
        return summary;
    }

    public RevisionNoteDtos.RevisionNoteStatusView status() {
        long noteCount = notes.count();
        if (noteCount == 0) {
            return new RevisionNoteDtos.RevisionNoteStatusView(false, 0, 0, null, null);
        }
        RevisionNote any = notes.findAll().get(0);
        return new RevisionNoteDtos.RevisionNoteStatusView(true,
                noteCount, assets.count(), any.ingestedAt(), any.corpusVersion());
    }

    private record ParsedPackage(RevisionNoteDtos.RevisionNotePackage pkg,
            Map<String, byte[]> assetBytes) {
    }

    private ParsedPackage unzip(byte[] zipBytes) {
        Map<String, byte[]> assetBytes = new LinkedHashMap<>();
        byte[] packageJson = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] content = zip.readAllBytes();
                if (entry.getName().contains("..")) {
                    throw new BadRequestException(
                            "revision-notes package contains an unsafe entry path: "
                                    + entry.getName());
                }
                if (entry.getName().equals("package.json")) {
                    packageJson = content;
                } else if (entry.getName().startsWith("assets/")) {
                    assetBytes.put(entry.getName().substring("assets/".length()), content);
                }
            }
        } catch (IOException e) {
            throw new BadRequestException("revision-notes package is not a readable ZIP");
        }
        if (packageJson == null) {
            throw new BadRequestException("revision-notes package is missing package.json");
        }
        try {
            RevisionNoteDtos.RevisionNotePackage pkg = objectMapper.readValue(
                    new String(packageJson, StandardCharsets.UTF_8),
                    RevisionNoteDtos.RevisionNotePackage.class);
            return new ParsedPackage(pkg, assetBytes);
        } catch (IOException e) {
            throw new BadRequestException("revision-notes package.json is not valid JSON");
        }
    }

    private void validate(RevisionNoteDtos.RevisionNotePackage pkg, Set<String> providedAssets) {
        if (pkg.packageVersion() == null
                || !SUPPORTED_PACKAGE_VERSION.equals(pkg.packageVersion())) {
            throw new BadRequestException("unsupported revision-notes package_version "
                    + "(expected " + SUPPORTED_PACKAGE_VERSION + ")");
        }
        if (pkg.corpusVersion() == null || pkg.corpusVersion().isBlank()
                || pkg.corpusVersion().length() > 128) {
            throw new BadRequestException("corpus_version must be 1..128 chars");
        }
        if (pkg.topics() == null || pkg.topics().isEmpty()) {
            throw new BadRequestException("package must contain at least one topic");
        }
        Set<Integer> topicOrders = new HashSet<>();
        Set<String> noteIds = new HashSet<>();
        Set<String> referencedAssets = new HashSet<>();
        for (var topic : pkg.topics()) {
            require(topic.title() != null && !topic.title().isBlank(), "topic title");
            require(topic.subtopics() != null && !topic.subtopics().isEmpty(),
                    "topic '" + topic.title() + "' has no subtopics");
            require(topicOrders.add(topic.order()), "duplicate topic order " + topic.order());
            Set<Integer> subOrders = new HashSet<>();
            for (var sub : topic.subtopics()) {
                require(sub.title() != null && !sub.title().isBlank(), "subtopic title");
                require(subOrders.add(sub.order()), "duplicate subtopic order "
                        + topic.order() + "." + sub.order());
                require(sub.notes() != null && !sub.notes().isEmpty(),
                        "subtopic '" + sub.title() + "' has no notes");
                Set<Integer> noteOrders = new HashSet<>();
                for (var n : sub.notes()) {
                    require(n.noteId() != null && n.noteId().matches("[A-Za-z0-9._-]{1,256}"),
                            "note_id must be 1..256 chars of [A-Za-z0-9._-]: " + n.noteId());
                    require(noteIds.add(n.noteId()), "duplicate note_id " + n.noteId());
                    require(noteOrders.add(n.order()), "duplicate note order "
                            + n.noteId());
                    require(n.title() != null && !n.title().isBlank(), "note title "
                            + n.noteId());
                    require(n.bodyMd() != null && !n.bodyMd().isBlank(), "note body "
                            + n.noteId());
                    if (n.specMapJson() != null) {
                        try {
                            objectMapper.readTree(n.specMapJson());
                        } catch (IOException e) {
                            throw new BadRequestException("note " + n.noteId()
                                    + " spec_map is not valid JSON");
                        }
                    }
                    if (n.assets() != null) {
                        referencedAssets.addAll(n.assets());
                    }
                }
            }
        }
        if (pkg.assets() != null) {
            for (var a : pkg.assets()) {
                require(a.filename() != null && SAFE_FILENAME.matcher(a.filename()).matches(),
                        "unsafe asset filename: " + a.filename());
                require(providedAssets.contains(a.filename()),
                        "asset declared in package.json but missing from ZIP: " + a.filename());
                require(a.contentType() != null && a.contentType().matches("[a-z]+/[a-z0-9.+\\-]+"),
                        "asset content type for " + a.filename());
            }
        }
        for (String ref : referencedAssets) {
            require(SAFE_FILENAME.matcher(ref).matches(),
                    "unsafe asset reference: " + ref);
            require(providedAssets.contains(ref),
                    "note references asset missing from package: " + ref);
        }
    }

    private void require(boolean condition, String what) {
        if (!condition) {
            throw new BadRequestException("invalid revision-notes package: " + what);
        }
    }
}
