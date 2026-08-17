package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("QCBP-09 当前登录人协作身份映射")
class CollaborationCurrentPrincipalProviderTest {
  private final CollaborationCurrentActorProvider actorProvider =
      mock(CollaborationCurrentActorProvider.class);
  private final CollaborationCurrentPrincipalProvider provider =
      new CollaborationCurrentPrincipalProvider(actorProvider);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void explicitCollaboratorRoleBecomesTechnician() {
    when(actorProvider.current()).thenReturn(new CollaborationActor(601L, "王工"));
    authenticate("ROLE_oa_collaborator", "collaboration:task:read");

    CollaborationPrincipal principal = provider.currentTechnician();

    assertThat(principal.userId()).isEqualTo(601L);
    assertThat(principal.roles()).containsExactly(CollaborationRole.TECHNICIAN);
  }

  @Test
  void adminWildcardNeverBecomesTechnician() {
    when(actorProvider.current()).thenReturn(new CollaborationActor(1L, "管理员"));
    authenticate("ROLE_admin", "*:*:*");

    assertThatThrownBy(provider::currentTechnician)
        .isInstanceOfSatisfying(CollaborationDomainException.class,
            error -> assertThat(error.code())
                .isEqualTo(CollaborationDomainErrorCode.TASK_ASSIGNEE_MISMATCH));
    assertThat(provider.current().roles()).containsExactly(CollaborationRole.ADMINISTRATOR);
  }

  private static void authenticate(String... authorities) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("user", null,
            List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
