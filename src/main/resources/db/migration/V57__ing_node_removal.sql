-- V57: ING ingest-era placeholder topic-node removal (app-migration lane).
--
-- Background: the ING-* knowledge_nodes are leftovers of the ingest-era
-- placeholder topic generation. The 2026-10-01 bank-residue-closure lane
-- deleted the 47 unanchored nodes + fixture learner rows + 4 edges and left
-- 57 nodes retained ONLY because archived (inactive) questions anchored them
-- via questions.primary_topic_node_id — an app-level reference with no FK and
-- a NOT NULL constraint that a DB lane could not lift (re-pointing archived
-- questions at real topics would fabricate provenance).
--
-- This migration changes the anchor-column semantics (the "app migration"
-- named by the residue-closure closeout):
--   1. questions.primary_topic_node_id becomes nullable (the anchor is
--      meaningful for serving questions; archived questions no longer carry
--      one). All read paths already tolerate null (ServableQuestionService,
--      SmartLessonService null-check; ClaContextResolver + NextBestAction
--      degrade via their existing NotFound/skip paths).
--   2. Archived questions lose their ING anchors (honest absence).
--   3. The remaining ING nodes are deleted under fail-closed reference
--      re-checks (questions any-state, KG edges, learner skill states,
--      review schedules). Any unexpected reference aborts the boot loudly
--      instead of partially deleting.
--
-- Reversibility note (operator): before-images of all 104 original ING
-- nodes are pinned in the records repo
-- bench/review/psaxis-review-2026-09-28/bank-residue-closure-20261001/ing_plan.json
-- (sha 9d53e3fa…), making the whole removal reversible by INSERT.

ALTER TABLE questions ALTER COLUMN primary_topic_node_id DROP NOT NULL;

UPDATE questions q
SET primary_topic_node_id = NULL
FROM knowledge_nodes kn
WHERE q.primary_topic_node_id = kn.id
  AND kn.code LIKE 'ING-%'
  AND q.active = false;

DO $$
DECLARE
  v_questions int;
  v_edges int;
  v_skills int;
  v_schedules int;
  v_deleted int;
BEGIN
  -- guard: every ING anchor must be gone by now (archived rows were nulled
  -- above; an active question must never have anchored an ING node)
  SELECT count(*) INTO v_questions
  FROM questions q
  JOIN knowledge_nodes kn ON kn.id = q.primary_topic_node_id
  WHERE kn.code LIKE 'ING-%';
  IF v_questions > 0 THEN
    RAISE EXCEPTION 'V57 guard: % question(s) still anchor ING nodes', v_questions;
  END IF;

  -- guard: no KG edges may reference ING nodes (the 2026-10-01 lane removed
  -- the 4 ING->SUBJECT edges; any reappearance here is unexpected drift)
  SELECT count(*) INTO v_edges
  FROM knowledge_edges e
  WHERE e.source_node_id IN (SELECT id FROM knowledge_nodes WHERE code LIKE 'ING-%')
     OR e.target_node_id IN (SELECT id FROM knowledge_nodes WHERE code LIKE 'ING-%');
  IF v_edges > 0 THEN
    RAISE EXCEPTION 'V57 guard: % knowledge edge(s) reference ING nodes', v_edges;
  END IF;

  -- guard: no learner state may reference ING nodes
  SELECT count(*) INTO v_skills
  FROM skill_states WHERE node_id IN (SELECT id FROM knowledge_nodes WHERE code LIKE 'ING-%');
  IF v_skills > 0 THEN
    RAISE EXCEPTION 'V57 guard: % skill state(s) reference ING nodes', v_skills;
  END IF;

  SELECT count(*) INTO v_schedules
  FROM review_schedules WHERE node_id IN (SELECT id FROM knowledge_nodes WHERE code LIKE 'ING-%');
  IF v_schedules > 0 THEN
    RAISE EXCEPTION 'V57 guard: % review schedule(s) reference ING nodes', v_schedules;
  END IF;

  DELETE FROM knowledge_nodes WHERE code LIKE 'ING-%';
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  -- completeness: no ING node may survive the delete
  SELECT count(*) INTO v_questions FROM knowledge_nodes WHERE code LIKE 'ING-%';
  IF v_questions > 0 THEN
    RAISE EXCEPTION 'V57 guard: % ING node(s) survived deletion', v_questions;
  END IF;
END $$;
