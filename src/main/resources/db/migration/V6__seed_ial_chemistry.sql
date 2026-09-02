-- V6: Cycle-1 seed — Edexcel IAL Chemistry, Unit 1 (WCH11) subset + model registries.
-- Provenance: every node/edge carries a source label; all rows are UNVALIDATED
-- (manual v0 seed pending SME validation, Master Spec §7). Fixed UUIDs keep
-- FK relationships stable across environments.

-- ── curriculum ────────────────────────────────────────────────────────────
INSERT INTO curriculum_versions (id, board, qualification, code, title, status, created_at) VALUES
    ('10000000-0000-0000-0000-000000000001', 'Edexcel', 'IAL', 'IAL-CHEM-2018',
     'Edexcel International A Level Chemistry (2018 specification)', 'ACTIVE', now());

-- ── knowledge graph nodes ─────────────────────────────────────────────────
INSERT INTO knowledge_nodes (id, code, node_type, title, description, validation_status, provenance, created_by, created_at) VALUES
    ('20000000-0000-0000-0000-000000000001', 'CHM', 'SUBJECT', 'Chemistry',
     'Edexcel IAL Chemistry', 'VALIDATED', 'Edexcel IAL specification 2018', 'seed-v6', now()),
    ('20000000-0000-0000-0000-000000000010', 'WCH11', 'UNIT',
     'Unit 1: Structure, Bonding and Introduction to Organic Chemistry',
     'WCH11 — IAL Chemistry Unit 1', 'UNVALIDATED', 'Edexcel IAL specification topic list', 'seed-v6', now()),
    ('20000000-0000-0000-0000-000000000011', 'WCH11-T1', 'TOPIC',
     'Formulae, Equations and Amount of Substance',
     'Chemical formulae, equations, the mole, reacting masses and volumes, empirical/molecular formulae.', 'UNVALIDATED', 'Edexcel IAL specification topic list', 'seed-v6', now()),
    ('20000000-0000-0000-0000-000000000012', 'WCH11-T1.1', 'SUBTOPIC',
     'Mole calculations and reacting masses',
     'The mole as the SI unit of amount; molar mass; reacting mass calculations; percentage yield.', 'UNVALIDATED', 'Edexcel IAL specification topic list', 'seed-v6', now()),
    ('20000000-0000-0000-0000-000000000013', 'WCH11-T1.2', 'SUBTOPIC',
     'Empirical and molecular formulae',
     'Determination of empirical formulae from composition data; scaling to molecular formulae.', 'UNVALIDATED', 'Edexcel IAL specification topic list', 'seed-v6', now()),
    ('20000000-0000-0000-0000-000000000021', 'WCH11-T2', 'TOPIC',
     'Atomic Structure and the Periodic Table',
     'Subatomic particles, isotopes, electronic configuration, ionisation energies and periodic trends.', 'UNVALIDATED', 'Edexcel IAL specification topic list', 'seed-v6', now()),
    ('20000000-0000-0000-0000-000000000022', 'WCH11-T2.1', 'SUBTOPIC',
     'Atomic structure, isotopes and ions',
     'Protons, neutrons, electrons; mass number and isotopes; formation of ions; relative atomic mass.', 'UNVALIDATED', 'Edexcel IAL specification topic list', 'seed-v6', now()),
    ('20000000-0000-0000-0000-000000000023', 'WCH11-T2.2', 'SUBTOPIC',
     'Electron configuration and periodic trends',
     'Electron shells, sub-shells and orbitals; successive ionisation energies; periodicity.', 'UNVALIDATED', 'Edexcel IAL specification topic list', 'seed-v6', now()),
    ('20000000-0000-0000-0000-000000000031', 'WCH11-T3', 'TOPIC',
     'Bonding and Structure',
     'Ionic, covalent and metallic bonding; molecular shape; intermolecular forces and structure.', 'UNVALIDATED', 'Edexcel IAL specification topic list', 'seed-v6', now()),
    ('20000000-0000-0000-0000-000000000032', 'WCH11-T3.1', 'SUBTOPIC',
     'Ionic bonding and lattice structure',
     'Formation of ions; ionic lattice structure; physical properties of ionic compounds.', 'UNVALIDATED', 'Edexcel IAL specification topic list', 'seed-v6', now()),
    ('20000000-0000-0000-0000-000000000033', 'WCH11-T3.2', 'SUBTOPIC',
     'Covalent bonding, shapes and intermolecular forces',
     'Single/double covalent bonds, dative bonds; VSEPR shapes; polarity; van der Waals and hydrogen bonding.', 'UNVALIDATED', 'Edexcel IAL specification topic list', 'seed-v6', now()),
    -- misconceptions (classic, widely documented in chemistry education research)
    ('30000000-0000-0000-0000-000000000001', 'MIS-T1.1-01', 'MISCONCEPTION',
     'Moles and grams are interchangeable',
     'Treating a mass in grams as a number of moles, or assuming one mole of any substance weighs 1 g.', 'UNVALIDATED', 'chemistry education research (classic)', 'seed-v6', now()),
    ('30000000-0000-0000-0000-000000000002', 'MIS-T3.1-01', 'MISCONCEPTION',
     'Ionic compounds form discrete molecule pairs',
     'Believing solid NaCl consists of NaCl "molecules" (one Na+ per one Cl-) rather than a giant lattice.', 'UNVALIDATED', 'chemistry education research (classic)', 'seed-v6', now()),
    ('30000000-0000-0000-0000-000000000003', 'MIS-T3.2-01', 'MISCONCEPTION',
     'The octet rule is an absolute law',
     'Assuming atoms always react to complete their outer electron shells, with no exceptions (e.g. BF3, NO).', 'UNVALIDATED', 'chemistry education research (classic)', 'seed-v6', now()),
    ('30000000-0000-0000-0000-000000000004', 'MIS-T2.1-01', 'MISCONCEPTION',
     'Isotopes of the same element differ chemically',
     'Confusing mass differences of isotopes with chemical behaviour differences; isotopes react identically.', 'UNVALIDATED', 'chemistry education research (classic)', 'seed-v6', now());

-- ── subject ↔ KG root ────────────────────────────────────────────────────
INSERT INTO subjects (id, curriculum_version_id, code, name, knowledge_node_id, created_at) VALUES
    ('10000000-0000-0000-0000-000000000010', '10000000-0000-0000-0000-000000000001',
     'CHM', 'Chemistry', '20000000-0000-0000-0000-000000000001', now());

-- ── structure edges: PART_OF (child → parent) ─────────────────────────────
INSERT INTO knowledge_edges (id, source_node_id, target_node_id, relation_type, strength, rationale, validation_status, provenance, created_by, created_at) VALUES
    ('50000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000010', '20000000-0000-0000-0000-000000000001', 'PART_OF', NULL, 'Unit 1 belongs to Chemistry', 'VALIDATED', 'spec structure', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000011', '20000000-0000-0000-0000-000000000010', 'PART_OF', NULL, 'Topic 1 in WCH11', 'VALIDATED', 'spec structure', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000021', '20000000-0000-0000-0000-000000000010', 'PART_OF', NULL, 'Topic 2 in WCH11', 'VALIDATED', 'spec structure', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000031', '20000000-0000-0000-0000-000000000010', 'PART_OF', NULL, 'Topic 3 in WCH11', 'VALIDATED', 'spec structure', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000012', '20000000-0000-0000-0000-000000000011', 'PART_OF', NULL, NULL, 'VALIDATED', 'spec structure', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000006', '20000000-0000-0000-0000-000000000013', '20000000-0000-0000-0000-000000000011', 'PART_OF', NULL, NULL, 'VALIDATED', 'spec structure', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000007', '20000000-0000-0000-0000-000000000022', '20000000-0000-0000-0000-000000000021', 'PART_OF', NULL, NULL, 'VALIDATED', 'spec structure', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000008', '20000000-0000-0000-0000-000000000023', '20000000-0000-0000-0000-000000000021', 'PART_OF', NULL, NULL, 'VALIDATED', 'spec structure', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000009', '20000000-0000-0000-0000-000000000032', '20000000-0000-0000-0000-000000000031', 'PART_OF', NULL, NULL, 'VALIDATED', 'spec structure', 'seed-v6', now()),
    ('50000000-0000-0000-0000-00000000000a', '20000000-0000-0000-0000-000000000033', '20000000-0000-0000-0000-000000000031', 'PART_OF', NULL, NULL, 'VALIDATED', 'spec structure', 'seed-v6', now());

-- ── prerequisite edges: REQUIRES_PREREQUISITE (dependent → prerequisite) ──
INSERT INTO knowledge_edges (id, source_node_id, target_node_id, relation_type, strength, rationale, validation_status, provenance, created_by, created_at) VALUES
    ('50000000-0000-0000-0000-000000000011', '20000000-0000-0000-0000-000000000011', '20000000-0000-0000-0000-000000000021', 'REQUIRES_PREREQUISITE', 0.8, 'Amount of substance underpins relative masses and ionisation calculations', 'UNVALIDATED', 'v0 heuristic — pedagogical ordering', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000012', '20000000-0000-0000-0000-000000000021', '20000000-0000-0000-0000-000000000031', 'REQUIRES_PREREQUISITE', 0.8, 'Bonding requires atomic structure (ions, electron configuration)', 'UNVALIDATED', 'v0 heuristic — pedagogical ordering', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000013', '20000000-0000-0000-0000-000000000012', '20000000-0000-0000-0000-000000000013', 'REQUIRES_PREREQUISITE', 0.6, 'Empirical formulae scale mole calculations', 'UNVALIDATED', 'v0 heuristic — pedagogical ordering', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000014', '20000000-0000-0000-0000-000000000012', '20000000-0000-0000-0000-000000000022', 'REQUIRES_PREREQUISITE', 0.6, 'Relative atomic mass calculations use the mole', 'UNVALIDATED', 'v0 heuristic — pedagogical ordering', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000015', '20000000-0000-0000-0000-000000000022', '20000000-0000-0000-0000-000000000023', 'REQUIRES_PREREQUISITE', 0.7, 'Electron configuration builds on subatomic structure', 'UNVALIDATED', 'v0 heuristic — pedagogical ordering', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000016', '20000000-0000-0000-0000-000000000022', '20000000-0000-0000-0000-000000000032', 'REQUIRES_PREREQUISITE', 0.7, 'Ion formation underpins ionic bonding', 'UNVALIDATED', 'v0 heuristic — pedagogical ordering', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000017', '20000000-0000-0000-0000-000000000023', '20000000-0000-0000-0000-000000000033', 'REQUIRES_PREREQUISITE', 0.7, 'Shapes and polarity require electron configuration (lone pairs, shells)', 'UNVALIDATED', 'v0 heuristic — pedagogical ordering', 'seed-v6', now());

-- ── misconception edges: MISCONCEPTION_OF (misconception → topic) ─────────
INSERT INTO knowledge_edges (id, source_node_id, target_node_id, relation_type, strength, rationale, validation_status, provenance, created_by, created_at) VALUES
    ('50000000-0000-0000-0000-000000000021', '30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000012', 'MISCONCEPTION_OF', 0.8, 'Classic mole-concept confusion', 'UNVALIDATED', 'chemistry education research', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000022', '30000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000032', 'MISCONCEPTION_OF', 0.8, 'Molecular vs lattice model confusion', 'UNVALIDATED', 'chemistry education research', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000023', '30000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000033', 'MISCONCEPTION_OF', 0.7, 'Over-generalised octet rule', 'UNVALIDATED', 'chemistry education research', 'seed-v6', now()),
    ('50000000-0000-0000-0000-000000000024', '30000000-0000-0000-0000-000000000004', '20000000-0000-0000-0000-000000000022', 'MISCONCEPTION_OF', 0.7, 'Isotope chemistry confusion', 'UNVALIDATED', 'chemistry education research', 'seed-v6', now());

-- ── model registry seeds (§19 reproducibility) ────────────────────────────
INSERT INTO model_versions (id, registry_key, version, params, provenance, notes, created_at) VALUES
    ('60000000-0000-0000-0000-000000000001', 'learner.bkt', 'v1-cycle1',
     '{"l0": 0.1, "slip": 0.1, "guess": 0.25, "learnRate": 0.1}'::jsonb,
     'Paper B §3.3 research design (Cycle 1)',
     'Defaults; monthly recalibration is Cycle-2 scope (Apache Commons Math MLE/EM)', now()),
    ('60000000-0000-0000-0000-000000000002', 'learner.bdt', 'v1-cycle1',
     '{"prior": 0.3, "selectIfHeld": 0.7, "selectIfNotHeld": 0.1}'::jsonb,
     'Paper B §3.4 (prior 0.3); likelihoods are v0 heuristics pending calibration', NULL, now()),
    ('60000000-0000-0000-0000-000000000003', 'learner.decay', 'v1-cycle1',
     '{"tauLowDays": 30, "tauMidDays": 90, "tauHighDays": 365, "lowBandCeiling": 0.45, "highBandFloor": 0.8, "floor": 0.1, "reviewBelow": 0.6}'::jsonb,
     'Paper B §3.3 (tau 30/90/365); banding thresholds are v0 heuristics', NULL, now());
