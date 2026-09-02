package com.syllabai.identity.dto;

import com.syllabai.identity.User;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * User projection exposed at the API boundary (Master Spec §22: never expose entities).
 */
public record UserView(UUID id, String email, String displayName, Set<String> roles) {

    public static UserView from(User user) {
        return new UserView(
                user.id(),
                user.email(),
                user.displayName(),
                user.roles().stream().map(Enum::name).collect(Collectors.toSet()));
    }
}
