-- V15: teacher-side 4CH1 concept-graph seed (T-C11 settled store materialization).
-- Additive CHECK-constraint extension only — the existing Postgres KG stays the
-- single graph substrate (no Neo4j, no second store, ADR-005):
--   node_type    + CONCEPT            (T-C11 concept nodes; misconceptions already had a type)
--   relation_type + WRONG_ANSWER_PATTERN, COMMONLY_CONFUSED_WITH
-- (the settled T-C11 store's validated semantic vocabulary — the c11 store's
-- meta declared alignment with this enum but carried two relations beyond it).
-- Existing rows are untouched: both constraints only widen their value domains.

ALTER TABLE knowledge_nodes DROP CONSTRAINT ck_node_type;
ALTER TABLE knowledge_nodes ADD CONSTRAINT ck_node_type CHECK (node_type IN
    ('SUBJECT', 'UNIT', 'TOPIC', 'SUBTOPIC', 'MISCONCEPTION', 'CONCEPT'));

ALTER TABLE knowledge_edges DROP CONSTRAINT ck_edge_relation;
ALTER TABLE knowledge_edges ADD CONSTRAINT ck_edge_relation CHECK (relation_type IN
    ('PART_OF', 'REQUIRES_PREREQUISITE', 'RELATED_TO', 'MISCONCEPTION_OF',
     'EXPLAINED_BY', 'REMEDIATED_BY', 'WRONG_ANSWER_PATTERN', 'COMMONLY_CONFUSED_WITH'));
