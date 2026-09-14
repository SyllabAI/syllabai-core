package com.syllabai.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.identity.BootstrapStateStore.State;
import com.syllabai.identity.dto.AuthResponse;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.ConflictException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The first-admin bootstrap must be exactly one-time: claimable only while the
 * V19 row is PENDING and zero ADMIN accounts exist, consumed terminally by the
 * claim, expiring terminally when unused — and never active when disabled.
 */
class BootstrapAdminServiceTest {

    private final BootstrapStateStore state = mock(BootstrapStateStore.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final JwtService jwt = mock(JwtService.class);

    private BootstrapAdminService service(boolean enabled) {
        return new BootstrapAdminService(state, users, encoder, jwt, enabled);
    }

    private RegisterRequest strong() {
        return new RegisterRequest("ops@syllabai.dev", "bootstrap-passphrase-1", "Product Ops");
    }

    @Nested
    @DisplayName("claimable()")
    class Claimable {

        @Test
        @DisplayName("true only for PENDING row with zero admins and enabled surface")
        void pendingZeroAdmins() {
            when(state.peekState()).thenReturn(Optional.of(State.PENDING));
            when(state.adminCount()).thenReturn(0);
            assertThat(service(true).claimable()).isTrue();
        }

        @Test
        @DisplayName("false once an admin exists, once consumed/expired, or when disabled")
        void closedVariants() {
            when(state.peekState()).thenReturn(Optional.of(State.PENDING));
            when(state.adminCount()).thenReturn(1);
            assertThat(service(true).claimable()).isFalse();

            when(state.peekState()).thenReturn(Optional.of(State.CONSUMED));
            when(state.adminCount()).thenReturn(0);
            assertThat(service(true).claimable()).isFalse();

            when(state.peekState()).thenReturn(Optional.of(State.EXPIRED));
            assertThat(service(true).claimable()).isFalse();

            when(state.peekState()).thenReturn(Optional.of(State.PENDING));
            assertThat(service(false).claimable()).isFalse();
        }

        @Test
        @DisplayName("missing state row fails closed")
        void missingRowFailsClosed() {
            when(state.peekState()).thenReturn(Optional.empty());
            assertThat(service(true).claimable()).isFalse();
        }
    }

    @Nested
    @DisplayName("claim()")
    class Claim {

        @Test
        @DisplayName("happy path: creates ADMIN+TEACHER, consumes window, issues token")
        void happyPath() {
            when(state.lockState()).thenReturn(Optional.of(State.PENDING));
            when(state.adminCount()).thenReturn(0);
            when(encoder.encode(anyString())).thenReturn("bcrypt-hash");
            UUID id = UUID.randomUUID();
            User saved = mock(User.class);
            when(saved.id()).thenReturn(id);
            when(saved.email()).thenReturn("ops@syllabai.dev");
            when(saved.displayName()).thenReturn("Product Ops");
            when(saved.roles()).thenReturn(Set.of(Role.ADMIN, Role.TEACHER));
            when(users.saveAndFlush(any(User.class))).thenReturn(saved);
            when(state.consume(id)).thenReturn(true);
            when(jwt.issueAccessToken(any(User.class))).thenReturn("jwt-token");

            AuthResponse response = service(true).claim(strong());

            assertThat(response.accessToken()).isEqualTo("jwt-token");
            assertThat(response.user().roles()).containsExactlyInAnyOrder("ADMIN", "TEACHER");
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(users).saveAndFlush(captor.capture());
            assertThat(captor.getValue().roles())
                    .containsExactlyInAnyOrder(Role.ADMIN, Role.TEACHER);
            verify(state).consume(id);
        }

        @Test
        @DisplayName("refused when the row is not PENDING — no user written")
        void refusedWhenConsumed() {
            when(state.lockState()).thenReturn(Optional.of(State.CONSUMED));
            assertThatThrownBy(() -> service(true).claim(strong()))
                    .isInstanceOf(ConflictException.class);
            verify(users, never()).saveAndFlush(any(User.class));
            verify(state, never()).consume(any());
        }

        @Test
        @DisplayName("refused when an ADMIN already exists")
        void refusedWhenAdminExists() {
            when(state.lockState()).thenReturn(Optional.of(State.PENDING));
            when(state.adminCount()).thenReturn(1);
            assertThatThrownBy(() -> service(true).claim(strong()))
                    .isInstanceOf(ConflictException.class);
            verify(users, never()).saveAndFlush(any(User.class));
        }

        @Test
        @DisplayName("refused when the surface is disabled")
        void refusedWhenDisabled() {
            assertThatThrownBy(() -> service(false).claim(strong()))
                    .isInstanceOf(ConflictException.class);
            verify(state, never()).lockState();
            verify(users, never()).saveAndFlush(any(User.class));
        }

        @Test
        @DisplayName("weak password rejected before any state change")
        void weakPassword() {
            when(state.lockState()).thenReturn(Optional.of(State.PENDING));
            when(state.adminCount()).thenReturn(0);
            RegisterRequest weak = new RegisterRequest("ops@syllabai.dev", "abcdefgh", "Ops");
            assertThatThrownBy(() -> service(true).claim(weak))
                    .isInstanceOf(BadRequestException.class);
            verify(users, never()).saveAndFlush(any(User.class));
            verify(state, never()).consume(any());
        }

        @Test
        @DisplayName("lost consume race rolls the claim back as a conflict")
        void consumeRaceLost() {
            when(state.lockState()).thenReturn(Optional.of(State.PENDING));
            when(state.adminCount()).thenReturn(0);
            when(encoder.encode(anyString())).thenReturn("bcrypt-hash");
            User saved = mock(User.class);
            when(saved.id()).thenReturn(UUID.randomUUID());
            when(users.saveAndFlush(any(User.class))).thenReturn(saved);
            when(state.consume(saved.id())).thenReturn(false);
            assertThatThrownBy(() -> service(true).claim(strong()))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("missing state row fails closed")
        void missingRowFailsClosed() {
            when(state.lockState()).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service(true).claim(strong()))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("expireUnusedWindow()")
    class Expiry {

        private void rewindBoot(BootstrapAdminService svc) throws Exception {
            java.lang.reflect.Field boot = BootstrapAdminService.class
                    .getDeclaredField("bootMillis");
            boot.setAccessible(true);
            boot.setLong(svc, System.currentTimeMillis()
                    - BootstrapAdminService.CLAIM_WINDOW.toMillis() - 1);
        }

        @Test
        @DisplayName("flips an unused PENDING window to terminal EXPIRED after the claim window")
        void expiresUnused() throws Exception {
            when(state.peekState()).thenReturn(Optional.of(State.PENDING));
            when(state.adminCount()).thenReturn(0);
            when(state.expire()).thenReturn(true);
            BootstrapAdminService svc = service(true);
            rewindBoot(svc);
            svc.expireUnusedWindow();
            verify(state).expire();
        }

        @Test
        @DisplayName("never expires inside the claim window, when claimed, or with admins")
        void guarded() throws Exception {
            BootstrapAdminService fresh = service(true);
            fresh.expireUnusedWindow(); // bootMillis = now → inside window
            verify(state, never()).expire();

            when(state.peekState()).thenReturn(Optional.of(State.CONSUMED));
            BootstrapAdminService svc = service(true);
            rewindBoot(svc);
            svc.expireUnusedWindow();
            verify(state, never()).expire();

            when(state.peekState()).thenReturn(Optional.of(State.PENDING));
            when(state.adminCount()).thenReturn(2);
            svc.expireUnusedWindow();
            verify(state, never()).expire();

            service(false).expireUnusedWindow();
            verify(state, never()).expire();
        }
    }
}
