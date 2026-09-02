package com.syllabai.infrastructure.storage;

import java.util.Optional;

/**
 * Object-storage port (Master Spec §25: large binaries live outside Postgres;
 * the adapter is provider-neutral so R2/Supabase/local can be substituted).
 */
public interface ObjectStorage {

    StoredObject put(String key, String contentType, byte[] bytes);

    Optional<byte[]> get(String key);

    boolean exists(String key);

    void delete(String key);
}
