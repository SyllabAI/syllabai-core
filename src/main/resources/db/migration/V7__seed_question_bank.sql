-- V7: seed question bank — 8 MCQs over the WCH11 subset with distractor→misconception
-- tags (Master Spec §10). All items are SEED_DEMO provenance: replace with
-- past-paper items through syllabai-parser in Wave 1 (T-011).

INSERT INTO questions (id, external_ref, question_type, stem, marks, difficulty, expected_time_seconds, command_word, primary_topic_node_id, provenance, created_at) VALUES
    ('40000000-0000-0000-0000-000000000001', 'SEED-WCH11-001', 'MCQ_SINGLE',
     'What is the mass of 0.25 mol of calcium carbonate, CaCO3 (Mr = 100.1)?',
     1, 2, 60, 'Calculate', '20000000-0000-0000-0000-000000000012', 'SEED_DEMO', now()),
    ('40000000-0000-0000-0000-000000000002', 'SEED-WCH11-002', 'MCQ_SINGLE',
     'How many moles of sodium hydroxide, NaOH (Mr = 40.0), are present in 8.0 g?',
     1, 2, 60, 'Calculate', '20000000-0000-0000-0000-000000000012', 'SEED_DEMO', now()),
    ('40000000-0000-0000-0000-000000000003', 'SEED-WCH11-003', 'MCQ_SINGLE',
     'A compound contains 40.0% carbon, 6.7% hydrogen and 53.3% oxygen by mass. What is its empirical formula?',
     1, 3, 120, 'Deduce', '20000000-0000-0000-0000-000000000013', 'SEED_DEMO', now()),
    ('40000000-0000-0000-0000-000000000004', 'SEED-WCH11-004', 'MCQ_SINGLE',
     'Which statement about isotopes of the same element is correct?',
     1, 2, 60, 'Select', '20000000-0000-0000-0000-000000000022', 'SEED_DEMO', now()),
    ('40000000-0000-0000-0000-000000000005', 'SEED-WCH11-005', 'MCQ_SINGLE',
     'An atom of chlorine is represented as 37Cl(-). How many protons, neutrons and electrons are present in this ion?',
     1, 3, 90, 'Deduce', '20000000-0000-0000-0000-000000000022', 'SEED_DEMO', now()),
    ('40000000-0000-0000-0000-000000000006', 'SEED-WCH11-006', 'MCQ_SINGLE',
     'Which of the following best describes the structure of solid sodium chloride?',
     1, 3, 60, 'Describe', '20000000-0000-0000-0000-000000000032', 'SEED_DEMO', now()),
    ('40000000-0000-0000-0000-000000000007', 'SEED-WCH11-007', 'MCQ_SINGLE',
     'Why does the formation of a covalent bond in a molecule generally lower the potential energy of the system?',
     1, 4, 90, 'Explain', '20000000-0000-0000-0000-000000000033', 'SEED_DEMO', now()),
    ('40000000-0000-0000-0000-000000000008', 'SEED-WCH11-008', 'MCQ_SINGLE',
     'Ammonia, NH3, has a bond angle of approximately 107°. Why is it not 109.5°, as in methane?',
     1, 4, 90, 'Explain', '20000000-0000-0000-0000-000000000033', 'SEED_DEMO', now());

-- options (correct + distractors; distractors tagged with misconception nodes)
INSERT INTO question_options (id, question_id, label, option_text, is_correct, misconception_node_id, ordering, created_at) VALUES
    -- Q1 mole↔gram confusion (MIS-T1.1-01)
    ('41000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 'A', '0.25 g', false, '30000000-0000-0000-0000-000000000001', 1, now()),
    ('41000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001', 'B', '4.0 g', false, NULL, 2, now()),
    ('41000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000001', 'C', '25.0 g', true, NULL, 3, now()),
    ('41000000-0000-0000-0000-000000000004', '40000000-0000-0000-0000-000000000001', 'D', '400.4 g', false, NULL, 4, now()),
    -- Q2
    ('41000000-0000-0000-0000-000000000011', '40000000-0000-0000-0000-000000000002', 'A', '0.20 mol', true, NULL, 1, now()),
    ('41000000-0000-0000-0000-000000000012', '40000000-0000-0000-0000-000000000002', 'B', '0.32 mol', false, NULL, 2, now()),
    ('41000000-0000-0000-0000-000000000013', '40000000-0000-0000-0000-000000000002', 'C', '5.0 mol', false, '30000000-0000-0000-0000-000000000001', 3, now()),
    ('41000000-0000-0000-0000-000000000014', '40000000-0000-0000-0000-000000000002', 'D', '8.0 mol', false, NULL, 4, now()),
    -- Q3 empirical formula
    ('41000000-0000-0000-0000-000000000021', '40000000-0000-0000-0000-000000000003', 'A', 'CH2O', true, NULL, 1, now()),
    ('41000000-0000-0000-0000-000000000022', '40000000-0000-0000-0000-000000000003', 'B', 'C2H4O', false, NULL, 2, now()),
    ('41000000-0000-0000-0000-000000000023', '40000000-0000-0000-0000-000000000003', 'C', 'CHO', false, NULL, 3, now()),
    ('41000000-0000-0000-0000-000000000024', '40000000-0000-0000-0000-000000000003', 'D', 'C6H12O6', false, NULL, 4, now()),
    -- Q4 isotopes (MIS-T2.1-01 distractor)
    ('41000000-0000-0000-0000-000000000031', '40000000-0000-0000-0000-000000000004', 'A', 'They have different chemical properties because their masses differ', false, '30000000-0000-0000-0000-000000000004', 1, now()),
    ('41000000-0000-0000-0000-000000000032', '40000000-0000-0000-0000-000000000004', 'B', 'They have the same number of protons but different numbers of neutrons', true, NULL, 2, now()),
    ('41000000-0000-0000-0000-000000000033', '40000000-0000-0000-0000-000000000004', 'C', 'They have different numbers of protons but the same number of neutrons', false, NULL, 3, now()),
    ('41000000-0000-0000-0000-000000000034', '40000000-0000-0000-0000-000000000004', 'D', 'They are atoms of different elements with similar chemistry', false, NULL, 4, now()),
    -- Q5 37Cl-: 17 p, 20 n, 18 e
    ('41000000-0000-0000-0000-000000000041', '40000000-0000-0000-0000-000000000005', 'A', '17 protons, 20 neutrons, 18 electrons', true, NULL, 1, now()),
    ('41000000-0000-0000-0000-000000000042', '40000000-0000-0000-0000-000000000005', 'B', '17 protons, 20 neutrons, 16 electrons', false, NULL, 2, now()),
    ('41000000-0000-0000-0000-000000000043', '40000000-0000-0000-0000-000000000005', 'C', '20 protons, 17 neutrons, 18 electrons', false, NULL, 3, now()),
    ('41000000-0000-0000-0000-000000000044', '40000000-0000-0000-0000-000000000005', 'D', '17 protons, 37 neutrons, 17 electrons', false, NULL, 4, now()),
    -- Q6 NaCl lattice (MIS-T3.1-01 distractor)
    ('41000000-0000-0000-0000-000000000051', '40000000-0000-0000-0000-000000000006', 'A', 'A lattice of alternating Na+ and Cl- ions held by electrostatic attraction', true, NULL, 1, now()),
    ('41000000-0000-0000-0000-000000000052', '40000000-0000-0000-0000-000000000006', 'B', 'Discrete molecules each containing one Na+ ion bonded to one Cl- ion', false, '30000000-0000-0000-0000-000000000002', 2, now()),
    ('41000000-0000-0000-0000-000000000053', '40000000-0000-0000-0000-000000000006', 'C', 'A covalent network of shared electron pairs', false, NULL, 3, now()),
    ('41000000-0000-0000-0000-000000000054', '40000000-0000-0000-0000-000000000006', 'D', 'Separate atoms held together by weak intermolecular forces', false, NULL, 4, now()),
    -- Q7 covalent bond energy (octet distractor)
    ('41000000-0000-0000-0000-000000000061', '40000000-0000-0000-0000-000000000007', 'A', 'Because each atom completes its outer electron shell', false, '30000000-0000-0000-0000-000000000003', 1, now()),
    ('41000000-0000-0000-0000-000000000062', '40000000-0000-0000-0000-000000000007', 'B', 'Because nuclei attract the shared bonding electrons, stabilising the arrangement', true, NULL, 2, now()),
    ('41000000-0000-0000-0000-000000000063', '40000000-0000-0000-0000-000000000007', 'C', 'Because the octet rule requires atoms to bond', false, '30000000-0000-0000-0000-000000000003', 3, now()),
    ('41000000-0000-0000-0000-000000000064', '40000000-0000-0000-0000-000000000007', 'D', 'Because bond formation always releases energy absorbed by the surroundings', false, NULL, 4, now()),
    -- Q8 ammonia bond angle
    ('41000000-0000-0000-0000-000000000071', '40000000-0000-0000-0000-000000000008', 'A', 'The lone pair on nitrogen repels bonding pairs more strongly, compressing the angle', true, NULL, 1, now()),
    ('41000000-0000-0000-0000-000000000072', '40000000-0000-0000-0000-000000000008', 'B', 'Hydrogen atoms are smaller than carbon atoms', false, NULL, 2, now()),
    ('41000000-0000-0000-0000-000000000073', '40000000-0000-0000-0000-000000000008', 'C', 'Nitrogen is more electronegative than carbon', false, NULL, 3, now()),
    ('41000000-0000-0000-0000-000000000074', '40000000-0000-0000-0000-000000000008', 'D', 'Ammonia is a planar molecule with 107° angles by convention', false, NULL, 4, now());

-- secondary topic mappings (multi-topic questions, §10)
INSERT INTO question_topics (id, question_id, node_id, is_primary, created_at) VALUES
    ('42000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000012', false, now()),
    ('42000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000012', false, now()),
    ('42000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000007', '20000000-0000-0000-0000-000000000032', false, now()),
    ('42000000-0000-0000-0000-000000000004', '40000000-0000-0000-0000-000000000008', '20000000-0000-0000-0000-000000000023', false, now());
