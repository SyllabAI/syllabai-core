-- V63: exam-series seed — Pearson Edexcel international calendar 2026-2027
-- (T-C79, ADR-035). Every row below was retrieved 2026-10-04 from official
-- Pearson documents: the "Key Dates Table International 2026-2027" XLSX
-- (Entries & information manual) for entry deadlines + results dates, and the
-- per-series FINAL exam timetables for the windows. Source URLs are per row
-- (source_url); retrieved_at is the retrieval date of this lane's citation
-- pass. The operator directed the retrieval themselves ("Search the web
-- yourself and find the dates", zai-web trace 1a10626076367841) — the
-- UNVERIFIED blocker on the T-C79 card is discharged by these citations.
-- The not-yet-published 2027-11 / 2028 series are deliberately ABSENT
-- (no citable dates yet — the honest empty state; estimated rows may only
-- enter when a window is announced without a published timetable).
-- Seed-in-migration follows the V6/V7 reference-data precedent; the
-- fail-closed import service is the ongoing update path.

INSERT INTO exam_series (id, board, qualification, series_code, label,
                         window_start, window_end, entry_deadline, results_date,
                         published, estimated, source_url, retrieved_at, created_at, updated_at) VALUES
-- International A Level, October/November 2026 (final timetable; 7 subjects only)
('0a000000-0000-0000-0000-000000000001', 'PEARSON_EDEXCEL', 'IAL', '2026-october-november',
 'October/November 2026', '2026-10-08', '2026-10-30', '2026-08-28', '2027-01-21',
 TRUE, FALSE,
 'https://qualifications.pearson.com/content/dam/pdf/Support/Examination-timetables-for-International-Advanced-Levels/ial-october2026-final.pdf',
 '2026-10-04T00:00:00Z', now(), now()),
-- International GCSE, November 2026 (final timetable)
('0a000000-0000-0000-0000-000000000002', 'PEARSON_EDEXCEL', 'INTERNATIONAL_GCSE', '2026-november',
 'November 2026', '2026-10-27', '2026-11-19', '2026-09-12', '2027-01-21',
 TRUE, FALSE,
 'https://qualifications.pearson.com/content/dam/pdf/Support/Examination-timetables-for-Edexcel-International-GCSE/intgcse-nov-2026-final.pdf',
 '2026-10-04T00:00:00Z', now(), now()),
-- International A Level, January 2027 (final timetable)
('0a000000-0000-0000-0000-000000000003', 'PEARSON_EDEXCEL', 'IAL', '2027-january',
 'January 2027', '2027-01-08', '2027-01-25', '2026-10-16', '2027-03-04',
 TRUE, FALSE,
 'https://qualifications.pearson.com/content/dam/pdf/Support/Examination-timetables-for-International-Advanced-Levels/ial-january-2027-final.pdf',
 '2026-10-04T00:00:00Z', now(), now()),
-- International A Level, May/June 2027 (final timetable)
('0a000000-0000-0000-0000-000000000004', 'PEARSON_EDEXCEL', 'IAL', '2027-may-june',
 'May/June 2027', '2027-05-04', '2027-06-10', '2027-03-21', '2027-08-12',
 TRUE, FALSE,
 'https://qualifications.pearson.com/content/dam/pdf/Support/Examination-timetables-for-International-Advanced-Levels/ial-summer-2027-final.pdf',
 '2026-10-04T00:00:00Z', now(), now()),
-- International GCSE, May/June 2027 (final timetable)
('0a000000-0000-0000-0000-000000000005', 'PEARSON_EDEXCEL', 'INTERNATIONAL_GCSE', '2027-may-june',
 'May/June 2027', '2027-05-10', '2027-06-18', '2027-03-21', '2027-08-19',
 TRUE, FALSE,
 'https://qualifications.pearson.com/content/dam/pdf/Support/Examination-timetables-for-Edexcel-International-GCSE/int-gcse-summer-2027-final.pdf',
 '2026-10-04T00:00:00Z', now(), now());
