/**
 * Content bounded context (Master Spec §6.4): canonical document store, deterministic
 * chunking, embeddings and vector retrieval. Owns the ingestion API that persists
 * syllabai-parser's sealed canonical documents (§8) and the retrieval index T-024
 * (KA-RAG) consumes. Deliberately chat-free: nothing here generates tutor answers.
 */
package com.syllabai.content;
