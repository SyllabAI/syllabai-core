-- V2: curriculum metadata + knowledge graph (Master Spec §6.2, §7, §24)
-- The KG is explicit edge tables with recursive-CTE traversal (ADR-005):
-- PART_OF child→parent; REQUIRES_PREREQUISITE dependent→prerequisite;
-- MISCONCEPTION_OF misconception→topic. TESTED_BY is expressed relationally
-- through question_topics (questions are bank items, not graph nodes).

CREATE TABLE curriculum_versions (
    id           UUID PRIMARY KEY,
    board        VARCHAR(50)  NOT NULL,
    qualification VARCHAR(50) NOT NULL,
    code         VARCHAR(50)  NOT NULL,
    title        VARCHAR(200) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT ck_curriculum_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    created_at   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE subjects (
    id                   UUID PRIMARY KEY,
    curriculum_version_id UUID NOT NULL REFERENCES curriculum_versions (id),
    code                 VARCHAR(20)  NOT NULL,
    name                 VARCHAR(100) NOT NULL,
    knowledge_node_id    UUID,
    created_at           TIMESTAMPTZ  NOT NULL
);

CREATE TABLE knowledge_nodes (
    id                UUID PRIMARY KEY,
    code              VARCHAR(40)  NOT NULL,
    node_type         VARCHAR(20)  NOT NULL
        CONSTRAINT ck_node_type CHECK (node_type IN
            ('SUBJECT', 'UNIT', 'TOPIC', 'SUBTOPIC', 'MISCONCEPTION')),
    title             VARCHAR(200) NOT NULL,
    description       VARCHAR(1000),
    validation_status VARCHAR(20)  NOT NULL DEFAULT 'UNVALIDATED'
        CONSTRAINT ck_node_validation CHECK (validation_status IN
            ('UNVALIDATED', 'SUGGESTED', 'VALIDATED')),
    provenance        VARCHAR(300),
    created_by        VARCHAR(100),
    version           INT          NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_knowledge_node_code ON knowledge_nodes (code);
CREATE INDEX ix_knowledge_node_type ON knowledge_nodes (node_type);

CREATE TABLE knowledge_edges (
    id                UUID PRIMARY KEY,
    source_node_id    UUID NOT NULL REFERENCES knowledge_nodes (id) ON DELETE CASCADE,
    target_node_id    UUID NOT NULL REFERENCES knowledge_nodes (id) ON DELETE CASCADE,
    relation_type     VARCHAR(30) NOT NULL
        CONSTRAINT ck_edge_relation CHECK (relation_type IN
            ('PART_OF', 'REQUIRES_PREREQUISITE', 'RELATED_TO',
             'MISCONCEPTION_OF', 'EXPLAINED_BY', 'REMEDIATED_BY')),
    strength          DOUBLE PRECISION,
    rationale         VARCHAR(500),
    validation_status VARCHAR(20) NOT NULL DEFAULT 'UNVALIDATED',
    provenance        VARCHAR(300),
    created_by        VARCHAR(100),
    version           INT NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_edge UNIQUE (source_node_id, target_node_id, relation_type),
    CONSTRAINT ck_no_self_edge CHECK (source_node_id <> target_node_id)
);

CREATE INDEX ix_edge_target_type ON knowledge_edges (target_node_id, relation_type);
CREATE INDEX ix_edge_source_type ON knowledge_edges (source_node_id, relation_type);
