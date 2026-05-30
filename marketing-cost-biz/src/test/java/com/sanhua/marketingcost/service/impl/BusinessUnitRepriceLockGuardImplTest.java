package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.MonthlyRepriceAuditService;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class BusinessUnitRepriceLockGuardImplTest {

  private OaFormMapper oaFormMapper;
  private MonthlyRepriceBatchMapper monthlyRepriceBatchMapper;
  private MonthlyRepriceAuditService auditService;
  private BusinessUnitRepriceLockGuardImpl guard;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, OaForm.class);
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceBatch.class);
  }

  @BeforeEach
  void setUp() {
    oaFormMapper = Mockito.mock(OaFormMapper.class);
    monthlyRepriceBatchMapper = Mockito.mock(MonthlyRepriceBatchMapper.class);
    auditService = Mockito.mock(MonthlyRepriceAuditService.class);
    guard =
        new BusinessUnitRepriceLockGuardImpl(
            oaFormMapper, monthlyRepriceBatchMapper, new PermissionService(), auditService);
    authenticate("COMMERCIAL", "ROLE_BU_STAFF", "cost:run:edit");
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("普通报价员从月度调价批次 CREATED 起不能发起普通 OA 核算")
  void staffCannotRunCostOnceMonthlyRepriceCreated() {
    when(oaFormMapper.selectOne(any())).thenReturn(oa("COMMERCIAL"));
    when(monthlyRepriceBatchMapper.selectOne(any())).thenReturn(batch("CREATED"));

    assertThatThrownBy(() -> guard.assertCostRunAllowed("OA-001"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("当前业务单元正在月度调价")
        .hasMessageContaining("COMMERCIAL");
    verify(auditService).recordOaCostRunBlocked(any(MonthlyRepriceBatch.class), org.mockito.ArgumentMatchers.eq("OA-001"), org.mockito.ArgumentMatchers.eq("u"));
  }

  @Test
  @DisplayName("非锁定业务单元允许普通 OA 核算")
  void staffCanRunCostWhenBusinessUnitNotLocked() {
    when(oaFormMapper.selectOne(any())).thenReturn(oa("HOUSEHOLD"));
    when(monthlyRepriceBatchMapper.selectOne(any())).thenReturn(null);

    guard.assertCostRunAllowed("OA-002");

    verify(monthlyRepriceBatchMapper).selectOne(any());
    verify(auditService, never()).recordOaCostRunBlocked(any(), any(), any());
  }

  @Test
  @DisplayName("业务总监不受普通 OA 核算入口的月度调价锁校验限制")
  void directorBypassesOrdinaryCostRunGuard() {
    authenticate("COMMERCIAL", "ROLE_BU_DIRECTOR", "cost:run:edit");

    guard.assertCostRunAllowed("OA-003");

    verify(oaFormMapper, never()).selectOne(any());
    verify(monthlyRepriceBatchMapper, never()).selectOne(any());
  }

  @Test
  @DisplayName("OA 缺少业务单元时使用当前登录业务单元做锁校验")
  void fallbackToCurrentBusinessUnitWhenOaBusinessUnitMissing() {
    when(oaFormMapper.selectOne(any())).thenReturn(oa(null));
    when(monthlyRepriceBatchMapper.selectOne(any())).thenReturn(batch("WAIT_CONFIRM"));

    assertThatThrownBy(() -> guard.assertCostRunAllowed("OA-004"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("COMMERCIAL");
  }

  private static OaForm oa(String businessUnitType) {
    OaForm form = new OaForm();
    form.setOaNo("OA");
    form.setBusinessUnitType(businessUnitType);
    return form;
  }

  private static MonthlyRepriceBatch batch(String status) {
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setId(1001L);
    batch.setRepriceNo("MRP-001");
    batch.setPricingMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setStatus(status);
    return batch;
  }

  private static void authenticate(String businessUnitType, String... authorities) {
    List<SimpleGrantedAuthority> auths =
        java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
    UsernamePasswordAuthenticationToken token =
        new UsernamePasswordAuthenticationToken("u", "N/A", auths);
    if (businessUnitType != null) {
      token.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType));
    }
    SecurityContextHolder.getContext().setAuthentication(token);
  }
}
