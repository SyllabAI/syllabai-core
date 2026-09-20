-- V35: routing hygiene (R4, plan §8.1–8.3) — the data layer Fetch/Enumerate serve from.
--
-- §8.1 SESSION CANONICALIZATION: exam_papers gains the canonical `series`
-- (JAN/JUN/NOV enum, same vocabulary as document_chunks.series from V33) and
-- `year` columns, backfilled from session_label. Raw labels like "Summer 2019"
-- stay as the display label; the canonical columns are what year-range
-- queries filter on ("done before Enumerate ships, or year-range queries lie").
-- "Specimen" is not a canonical session: series stays NULL, year is stamped.
--
-- §8.2 MISSING PAPER_CODES: the plan's estimate ("~10 papers, backfill from
-- folder identity") turned out to be a DIFFERENT defect. The 10 paper_code IS
-- NULL rows are all legacy glm-ocr-markdown@1.0.0-era DUPLICATE imports of
-- already-coded exam_papers rows — proven by the QP document source_uri folder
-- being byte-identical to the coded row's folder with the same question count
-- (e.g. 60054b96 'June 2014' ≡ 129a3edc 4CH0/1CR, folder
-- corpus/igcse-chemistry-4ch0-1c-2014junr, 10 questions each). Backfilling a
-- code onto them would violate uq_exam_paper_identity; they are superseded
-- copies, not codeless papers. Honest action: mark them REJECTED (reversible —
-- rows, questions and documents are retained) so the deterministic Fetch and
-- Enumerate paths (which filter REJECTED) resolve to the retained coded row.
-- NO surrogate codes were needed: zero genuinely-codeless papers exist.
--
-- §8.3 MISSING MS: audited — 0 rows have mark_scheme_document_id IS NULL while
-- carrying a paper_code (plan estimated ~10). Nothing to mark ms_unavailable;
-- recorded here so the number is traceable.
--
-- AUDIT DEBRIS: 3 'Audit E2E' self-test papers (titles carry the AUDIT tag,
-- 2 already REJECTED) are marked REJECTED so bank surfaces never serve them.
--
-- GEN JUNK SUBJECT: the 'GEN' / 'unknown subject' row (R4 hygiene audit finding)
-- has zero references (0 exam_papers, 0 document_chunks, 0 question links) and
-- is deleted. Any future FK violation here is the fail-closed proof of a
-- reference the audit missed — the migration then fails loudly, by design.
--
-- FETCH INDEX: deterministic paper resolution filters (paper_code, year,
-- series) — the same predicate shape document_chunks already indexes (V33).

ALTER TABLE exam_papers
    ADD COLUMN series VARCHAR(3),
    ADD COLUMN year INT;

ALTER TABLE exam_papers
    ADD CONSTRAINT ck_exam_papers_series
    CHECK (series IS NULL OR series IN ('JAN', 'JUN', 'NOV'));

-- §8.1 backfill — display labels map to the canonical session enum
-- (plan §8.1: "Summer"/"June" → JUN, "October/November" → NOV).
UPDATE exam_papers
    SET series = 'JUN', year = substring(session_label FROM '[0-9]{4}')::int
    WHERE series IS NULL
      AND session_label ~* '^(june|summer) [0-9]{4}$';

UPDATE exam_papers
    SET series = 'JAN', year = substring(session_label FROM '[0-9]{4}')::int
    WHERE series IS NULL
      AND session_label ~* '^(january|jan) [0-9]{4}$';

UPDATE exam_papers
    SET series = 'NOV', year = substring(session_label FROM '[0-9]{4}')::int
    WHERE series IS NULL
      AND session_label ~* '^(november|october|october/november|nov) [0-9]{4}$';

UPDATE exam_papers
    SET year = substring(session_label FROM '[0-9]{4}')::int
    WHERE year IS NULL
      AND session_label ~* '^specimen [0-9]{4}$';

-- §8.2 — the 10 null-code papers are duplicates of coded rows: supersede.
UPDATE exam_papers
    SET validation_state = 'REJECTED'
    WHERE paper_code IS NULL
      AND validation_state <> 'REJECTED';

-- Audit self-test debris.
UPDATE exam_papers
    SET validation_state = 'REJECTED'
    WHERE session_label LIKE 'Audit E2E%'
      AND validation_state <> 'REJECTED';

-- GEN junk subject (0 references — see audit note above).
DELETE FROM subjects WHERE code = 'GEN';

-- Deterministic Fetch resolution index.
CREATE INDEX ix_exam_papers_paper_year_series
    ON exam_papers (paper_code, year, series) WHERE paper_code IS NOT NULL;

-- ── fail-closed assertions (migration-time invariants) ─────────────────────
DO $$
DECLARE
    stragglers int;
    dup_folders int;
BEGIN
    -- every parseable session label carried its canonical columns through
    SELECT count(*) INTO stragglers FROM exam_papers
        WHERE series IS NULL
          AND session_label ~* '^(june|summer|january|jan|november|october|nov) [0-9]{4}$';
    IF stragglers <> 0 THEN
        RAISE EXCEPTION 'V35: % session labels failed canonicalization (§8.1)', stragglers;
    END IF;

    -- no codeless paper survives outside REJECTED (§8.2: duplicates superseded)
    SELECT count(*) INTO stragglers FROM exam_papers
        WHERE paper_code IS NULL AND validation_state <> 'REJECTED';
    IF stragglers <> 0 THEN
        RAISE EXCEPTION 'V35: % non-rejected papers still lack paper_code (§8.2)', stragglers;
    END IF;

    -- two live papers may never claim the same QP source folder (duplicate guard)
    SELECT count(*) INTO dup_folders FROM (
        SELECT qpd.source_uri
        FROM exam_papers ep
        JOIN documents qpd ON qpd.document_id = ep.question_paper_document_id
        WHERE ep.validation_state <> 'REJECTED' AND qpd.source_uri IS NOT NULL
        GROUP BY qpd.source_uri HAVING count(*) > 1) x;
    IF dup_folders <> 0 THEN
        RAISE EXCEPTION 'V35: % QP source folders claimed by multiple live papers (§8.2 duplicate guard)', dup_folders;
    END IF;
END $$;
