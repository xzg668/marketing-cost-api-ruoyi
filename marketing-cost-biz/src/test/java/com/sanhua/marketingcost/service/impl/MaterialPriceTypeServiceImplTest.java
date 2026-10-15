package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.MaterialPriceTypeRequest;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.enums.MaterialPriceSourceType;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.service.MaterialPricePriorityService;
import com.sanhua.marketingcost.service.MaterialPricePriorityService.Scope;
import com.sanhua.marketingcost.service.SysUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class MaterialPriceTypeServiceImplTest {
  private final MaterialPriceTypeMapper mapper=mock(MaterialPriceTypeMapper.class);
  private final MaterialPricePriorityService priorities=mock(MaterialPricePriorityService.class);
  private final SysUserService users=mock(SysUserService.class);
  private final MaterialPriceTypeServiceImpl service=new MaterialPriceTypeServiceImpl(mapper,priorities,users,mock(JdbcTemplate.class));

  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  @Test void missingBusinessUnitCannotReadOrModifyAnotherBusinessUnit() {
    assertThatThrownBy(()->service.page(null,"2026-10",null,1,20)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(mapper);
  }

  @Test void deletedOrInaccessibleScopeCannotBeModified() {
    login();
    assertThatThrownBy(()->service.update(7L,new MaterialPriceTypeRequest("v",MaterialPriceSourceType.DEFAULT_ORDER,false)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无权");
    verifyNoInteractions(priorities);
  }

  @Test void invalidMonthsAreRejectedBeforeQuerying() {
    login();
    for (String month : new String[] {null, "", "2026-1", "2026-13", "2026-00", "+2026-10"}) {
      assertThatThrownBy(() -> service.page(null, month, null, 1, 20))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("价格月份");
    }
    verifyNoInteractions(mapper, priorities);
  }

  @Test void omittedOrderDoesNotAccidentallyRestoreDefault() {
    assertThatThrownBy(()->service.update(7L,new MaterialPriceTypeRequest("v",null,false)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->service.update(7L,new MaterialPriceTypeRequest("v",MaterialPriceSourceType.DEFAULT_ORDER,true)))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(priorities);
  }

  @Test void editingUsesStoredMonthAndOrganizationAndAuthenticatedOperator() {
    login();
    var row=new MaterialPriceType();row.setId(7L);row.setMaterialCode("M1");row.setPeriod("2026-10");
    row.setPriceOrgCode("220");row.setBusinessUnitType("COMMERCIAL");
    when(mapper.selectOne(any())).thenReturn(row);when(mapper.selectById(7L)).thenReturn(row);
    var user=new SysUser();user.setUserId(9L);when(users.findByUsername("finance")).thenReturn(user);
    var scope=new Scope("COMMERCIAL","2026-10","220","M1");
    when(priorities.read(scope)).thenReturn(new MaterialPricePriorityService.Configuration(scope,List.of(),true,"new"));
    service.update(7L,new MaterialPriceTypeRequest("v",MaterialPriceSourceType.DEFAULT_ORDER,false));
    verify(priorities).change(scope,MaterialPriceSourceType.DEFAULT_ORDER,"v",9L);
  }

  private void login(){
    var auth=new UsernamePasswordAuthenticationToken("finance","",List.of());
    auth.setDetails(Map.of("businessUnitType","COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
}
