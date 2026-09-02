package com.syllabai.infrastructure.storage;

/**
 * Result of storing an object.
 *
 * @param key  storage key (path-like)
 * @param size stored byte count
 * @param etag provider etag when available
 */
public record StoredObject(String key, long size, String etag) {
}
