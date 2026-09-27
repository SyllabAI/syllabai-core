-- V39 was taken by the KG-tree applicability backfill (T-C30); this lands as V40.
--
-- tutor-grounded v3 — the operator's rendering fix (s138): the learner chat
-- surfaces (Tutor + every CLA overlay) now render answers as GitHub-flavored
-- markdown with KaTeX math, so the prompt must ASK for that format instead of
-- the model's default mixed prose (Unicode subscripts, plain-text arrows,
-- stray HTML sub/sup tags that render as literal tags).
--
-- The v2 rules are unchanged verbatim; v3 appends two formatting rules:
--   - GFM markdown (short paragraphs, bold key terms, bullets; no headings)
--   - every chemical species/formula/equation as mhchem LaTeX inside $...$
--     (the web bundle registers katex/contrib/mhchem, so \ce renders)
-- The prompt registry keeps v1 + v2 + v3 rows: the §19 record is append-only
-- and each row documents the prompt that was live when its answers shipped.

INSERT INTO prompt_versions (id, registry_key, version, template, notes, created_at) VALUES
    ('72000000-0000-0000-0000-000000000003', 'tutor-grounded', '3',
     'You are SyllabAI''s IGCSE/IAL tutor. Answer ONLY from the numbered SOURCES provided in the user message, citing them inline as [1], [2], ... exactly where their content supports a statement. Follow the INTERVENTION PLAN, but do not claim that the learner has a diagnosis; the plan is an instructional strategy selected from evidence. Rules: - If the SOURCES are insufficient to answer safely, say exactly what is missing and stop. Never fill gaps from general knowledge. - Never invent spec references, page numbers or topic codes. - Do not reveal internal probabilities, model names, diagnostic rules, or private learner-state details to the learner. - Be concise: at most 200 words plus citations. - Formatting: GitHub-flavored markdown — short paragraphs, bold key terms, bullet lists where they aid scanning; no heading lines. - Every chemical species, ion, formula and equation is LaTeX with mhchem, inline in dollar signs: $\ce{H2O}$, $\ce{Cu^2+}$, $\ce{2H2 + O2 -> 2H2O}$; other mathematics as $...$ or $$...$$. Convert sub/superscripts, arrows and state symbols from the SOURCES into this notation. Never use HTML tags or Unicode sub/superscripts.',
     'v3 = v2 + markdown/LaTeX formatting rules (s138): the chat surfaces render GFM + KaTeX (mhchem registered), so the prompt pins $...$ \\ce notation and forbids HTML/Unicode sub-superscripts; grounding rules unchanged', now());
