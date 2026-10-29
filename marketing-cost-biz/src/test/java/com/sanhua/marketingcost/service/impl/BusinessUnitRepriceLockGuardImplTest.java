package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.reprice.MonthlyQuotationControl;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class BusinessUnitRepriceLockGuardImplTest {
  @BeforeAll static void tables() {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),OaForm.class);
  }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @ParameterizedTest @ValueSource(strings={"ROLE_ADMIN","ROLE_BU_DIRECTOR","price:monthly-reprice:operate","ingest:quote:cost-run:execute"})
  void quotationControlHasNoRoleOrGenericPermissionBypass(String authority) {
    var forms=mock(OaFormMapper.class);var control=mock(MonthlyQuotationControl.class);
    var form=new OaForm();form.setOaNo("OA");form.setBusinessUnitType("COMMERCIAL");
    when(forms.selectOne(any())).thenReturn(form);
    doThrow(new MonthlyQuotationControl.QuotationBlocked()).when(control).requireAllowed("COMMERCIAL");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("actor",null,List.of(new SimpleGrantedAuthority(authority))));
    var guard=new BusinessUnitRepriceLockGuardImpl(forms,control);
    assertThatThrownBy(()->guard.assertCostRunAllowed("OA")).isInstanceOf(MonthlyQuotationControl.QuotationBlocked.class);
  }

  @Test void confirmedUnitCanQuoteAndMissingObjectDoesNotSilentlyPass() {
    var forms=mock(OaFormMapper.class);var control=mock(MonthlyQuotationControl.class);
    var form=new OaForm();form.setBusinessUnitType("HOUSEHOLD");when(forms.selectOne(any())).thenReturn(form,null);
    var guard=new BusinessUnitRepriceLockGuardImpl(forms,control);
    guard.assertCostRunAllowed("OA");verify(control).requireAllowed("HOUSEHOLD");
    assertThatThrownBy(()->guard.assertCostRunAllowed("MISSING")).hasMessageContaining("报价单不存在");
  }
}
