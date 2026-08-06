package com.sanhua.marketingcost.service.effectivebom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.service.SysUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

class SecurityQuoteEffectiveBomActorProviderTest {

  private final SysUserService userService = mock(SysUserService.class);
  private final SecurityQuoteEffectiveBomActorProvider provider =
      new SecurityQuoteEffectiveBomActorProvider(userService);

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void collaborationOrJwtDetailUserIdTakesPriority() {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("collaborator:88", null, List.of());
    authentication.setDetails(Map.of("userId", 88L));
    SecurityContextHolder.getContext().setAuthentication(authentication);

    assertThat(provider.currentUserId()).isEqualTo(88L);
    verifyNoInteractions(userService);
  }

  @Test
  void resolvesRegularJwtUsernameThroughSystemUser() {
    User principal = new User("finance", "N/A", List.of());
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    SysUser user = new SysUser();
    user.setUserId(9527L);
    when(userService.findByUsername("finance")).thenReturn(user);

    assertThat(provider.currentUserId()).isEqualTo(9527L);
    verify(userService).findByUsername("finance");
  }

  @Test
  void missingUserIdIsRejectedInsteadOfWritingAnonymousFreeze() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("unknown", null, List.of()));

    assertThatThrownBy(provider::currentUserId)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("缺少用户ID");
  }
}
