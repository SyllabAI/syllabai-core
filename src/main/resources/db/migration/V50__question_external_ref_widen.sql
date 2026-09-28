-- V50: widen question external_ref (ADR-026 amendment, layer-3 subject-#2).
--
-- The hub join (src/app/api/core/questions/route.ts) derives core identity
-- verbatim as sme-eq-<corpusTopicSlug>-q<order>; the longest 4MA1 Higher
-- corpus slug (right-angled-triangles---pythagoras-and-trigonometry) yields
-- 63-char base refs and 66-char -pN/-s member refs, past the chemistry-era
-- 60. The refs must not be rewritten (the join is course-generic), so the
-- column widens instead.

alter table questions alter column external_ref type varchar(80);
