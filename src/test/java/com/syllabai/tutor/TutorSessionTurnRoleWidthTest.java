package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The always-runnable half of the session-141 regression pair (the other
 * half is {@code TutorSessionStoreFlowIT}, which needs Docker).
 *
 * <p>The 2026-09-27 incident: V42 declared the transcript role column
 * {@code VARCHAR(8)} while {@code ConversationTurn.ROLE_ASSISTANT}
 * ("assistant") is NINE characters — every session-anchored ask 500'd at
 * the append step. The mock-based service tests never touched a real
 * schema, so nothing pinned the declared width against the constants that
 * get written. This test does exactly that, with no database: if someone
 * narrows the column below the longest role ever persisted, it fails
 * before the schema ships.</p>
 */
class TutorSessionTurnRoleWidthTest {

    @Test
    @DisplayName("declared role column width fits every ConversationTurn role")
    void roleColumnFitsBothRoles() throws Exception {
        int declared = TutorSessionTurn.class.getDeclaredField("role")
                .getAnnotation(Column.class).length();

        assertThat(declared)
                .as("role column width must fit '%s' (%d chars) — the varchar(8) "
                        + "incident rolled back every session append",
                        ConversationTurn.ROLE_ASSISTANT, ConversationTurn.ROLE_ASSISTANT.length())
                .isGreaterThanOrEqualTo(ConversationTurn.ROLE_ASSISTANT.length())
                .isGreaterThanOrEqualTo(ConversationTurn.ROLE_USER.length());
    }
}
