package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.entity.system.LpCollaborationToken;
import com.sanhua.marketingcost.security.CollaborationPortalAuthentication;
import com.sanhua.marketingcost.security.CollaborationPortalGrantCodec;
import com.sanhua.marketingcost.security.CollaborationPortalModule;
import com.sanhua.marketingcost.service.CollaborationTokenService;
import com.sanhua.marketingcost.service.SysUserService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CollaborationPortalLinkServiceTest {
  private final QuoteCollaborationTaskRepository repository =
      mock(QuoteCollaborationTaskRepository.class);
  private final CollaborationTokenService tokenService = mock(CollaborationTokenService.class);
  private final SysUserService userService = mock(SysUserService.class);
  private final CollaborationPortalGrantCodec codec =
      new CollaborationPortalGrantCodec(new ObjectMapper());
  private final CollaborationPortalLinkService service = new CollaborationPortalLinkService(
      repository, tokenService, codec, userService, "https://quote.example.com/", 72);

  @BeforeEach
  void setup() {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("quote-user", null, List.of());
    authentication.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void oneTechnicianGetsOneLinkCoveringHisBomAndPriceTasks() {
    QuoteCollaborationProductTask bom = task(11L, 88L, 601L, 1, 0, 0);
    QuoteCollaborationProductTask price = task(12L, 88L, 601L, 0, 0, 1);
    QuoteCollaborationProductTask otherTechnician = task(13L, 88L, 602L, 1, 0, 1);
    when(repository.findProductTaskByIdAndBusinessUnit(11L, "COMMERCIAL"))
        .thenReturn(Optional.of(bom));
    when(repository.findProductTasksByCollaboration(88L, "COMMERCIAL"))
        .thenReturn(List.of(bom, price, otherTechnician));
    SysUser user = new SysUser();
    user.setUserId(601L);
    user.setNickName("王工");
    user.setStatus("0");
    user.setDelFlag("0");
    user.setBusinessUnitType("COMMERCIAL");
    when(userService.getById(601L)).thenReturn(user);
    when(tokenService.getOrCreateReusableToken(eq(601L),
        eq(CollaborationPortalAuthentication.TOKEN_TYPE), anyString(), eq(72)))
        .thenAnswer(invocation -> {
          LpCollaborationToken token = new LpCollaborationToken();
          token.setToken("secret-token");
          token.setExpireTime(LocalDateTime.of(2026, 8, 28, 12, 0));
          return token;
        });

    var response = service.issue(11L);

    assertThat(response.modules()).containsExactly("BOM", "PRICE");
    assertThat(response.accessUrl()).isEqualTo(
        "https://quote.example.com/collaborate/tasks#access_token=secret-token");
    verify(tokenService).getOrCreateReusableToken(eq(601L),
        eq(CollaborationPortalAuthentication.TOKEN_TYPE), anyString(), eq(72));
  }

  private static QuoteCollaborationProductTask task(
      Long id, Long masterId, Long technicianId, int needBom, int needPackage, int needPrice) {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(id);
    task.setOriginCollaborationId(masterId);
    task.setOriginalTechnicianUserId(technicianId);
    task.setNeedBom(needBom);
    task.setNeedPackage(needPackage);
    task.setNeedPrice(needPrice);
    task.setBusinessUnitType("COMMERCIAL");
    return task;
  }
}
