package com.syllabai.identity;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Adapter between the SyllabAI user store and Spring Security's
 * {@link UserDetailsService} contract (Master Spec §23 Adapter pattern).
 */
@Service
public class SyllabaiUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public SyllabaiUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByEmailIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("user not found"));
        String[] authorities = user.roles().stream().map(r -> "ROLE_" + r.name()).toArray(String[]::new);
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.email())
                .password(user.passwordHash())
                .accountLocked(!user.enabled())
                .authorities(authorities)
                .build();
    }
}
