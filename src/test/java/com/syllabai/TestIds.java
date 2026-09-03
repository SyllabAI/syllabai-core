package com.syllabai;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Test-only helper: assigns entity ids before persistence (ids are normally
 * generated in @PrePersist, which never runs in pure unit tests).
 */
public final class TestIds {

    private TestIds() {
    }

    public static <T> T withId(T entity, UUID id) {
        try {
            Field field = findField(entity.getClass(), "id");
            field.setAccessible(true);
            field.set(entity, id);
            return entity;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set id on " + entity.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " on " + type);
    }
}
