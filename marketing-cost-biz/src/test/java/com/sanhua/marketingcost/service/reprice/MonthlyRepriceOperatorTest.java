package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.config.MonthlyRepriceProperties;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.MonthlyRepriceContractMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceWorkflowMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

class MonthlyRepriceOperatorTest {
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @ParameterizedTest @ValueSource(strings={"ROLE_ADMIN","ROLE_admin","ROLE_BU_DIRECTOR","ROLE_bu_director"})
  void operatorQualificationAndPageViewUseTheSameRoleAndBusinessUnit(String role) {
    auth(role, "COMMERCIAL");
    assertThat(MonthlyRepriceContractService.requireOperator("COMMERCIAL")).isEqualTo("operator");
    assertThat(MonthlyRepriceContractService.canOperate("HOUSEHOLD")).isFalse();
    assertThatThrownBy(() -> MonthlyRepriceContractService.requireOperator("HOUSEHOLD"))
        .isInstanceOf(AccessDeniedException.class);
    var workflow=mock(MonthlyRepriceWorkflowMapper.class);
    var control=mock(MonthlyQuotationControl.class);
    var batch=new MonthlyRepriceBatch();
    batch.setRepriceNo("saved-batch");batch.setCheckResultJson("{\"problems\":[]}");
    when(workflow.active("COMMERCIAL")).thenReturn(batch);
    when(workflow.problems("saved-batch")).thenReturn(List.of());
    var service=new MonthlyRepriceWorkflowService(control,mock(MonthlyRepriceContractService.class),
        mock(MonthlyRepriceContractMapper.class),workflow,mock(MonthlyRepriceCheckService.class),
        new MonthlyRepriceProperties(),mock(PlatformTransactionManager.class));
    var view=service.current();
    assertThat(view.canOperate()).isTrue();
    assertThat(view.batch().getCheckResultJson()).isEqualTo("{\"problems\":[]}");
    verify(workflow).problems("saved-batch");
  }

  @ParameterizedTest @ValueSource(strings={"*:*:*","price:monthly-reprice:operate","ROLE_BU_STAFF"})
  void genericPermissionsAndOrdinaryStaffDoNotGrantOperationAuthority(String role) {
    auth(role,"COMMERCIAL");
    assertThat(MonthlyRepriceContractService.canOperate("COMMERCIAL")).isFalse();
    assertThatThrownBy(() -> MonthlyRepriceContractService.requireOperator("COMMERCIAL"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test void missingOrUnauthenticatedOperatorIsRejected() {
    assertThat(MonthlyRepriceContractService.canOperate("COMMERCIAL")).isFalse();
    var token=auth("ROLE_ADMIN","COMMERCIAL");token.setAuthenticated(false);
    assertThat(MonthlyRepriceContractService.canOperate("COMMERCIAL")).isFalse();
    assertThatThrownBy(() -> MonthlyRepriceContractService.requireOperator("COMMERCIAL"))
        .isInstanceOf(AccessDeniedException.class);
  }

  private static UsernamePasswordAuthenticationToken auth(String role,String unit) {
    var token=new UsernamePasswordAuthenticationToken("operator",null,List.of(new SimpleGrantedAuthority(role)));
    token.setDetails(Map.of("businessUnitType",unit));
    SecurityContextHolder.getContext().setAuthentication(token);return token;
  }
}
