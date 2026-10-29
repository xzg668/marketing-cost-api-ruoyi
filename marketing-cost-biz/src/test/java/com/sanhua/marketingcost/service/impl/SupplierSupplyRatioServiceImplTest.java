package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SupplierSupplyRatioServiceImplTest {
  SupplierSupplyRatioMapper mapper;SupplierSupplyRatioHistoryMapper history;SupplierSupplyRatioServiceImpl service;
  @BeforeEach void setup(){mapper=mock(SupplierSupplyRatioMapper.class);history=mock(SupplierSupplyRatioHistoryMapper.class);service=new SupplierSupplyRatioServiceImpl(mapper,history);var a=new UsernamePasswordAuthenticationToken("finance",null);a.setDetails(Map.of("businessUnitType","COMMERCIAL"));SecurityContextHolder.getContext().setAuthentication(a);when(mapper.selectPage(any(Page.class),any(Wrapper.class))).thenAnswer(i->i.getArgument(0));}
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  @Test void filtersAndPaginationUseExplicitBusinessUnit() {
    service.page("000M","物料","SPEC","供应商","000A","batch","EXCEL",1,-3,999,null);
    var page=ArgumentCaptor.forClass(Page.class);var query=ArgumentCaptor.forClass(Wrapper.class);verify(mapper).selectPage(page.capture(),query.capture());
    assertThat(page.getValue().getCurrent()).isEqualTo(1);assertThat(page.getValue().getSize()).isEqualTo(200);
    assertThat(query.getValue().getSqlSegment()).contains("business_unit_type","supplier_code","source_batch_no","is_active");
  }
  @Test void historyPassesMaterialSupplierAndBatchWithinTheSelectedUnit() {
    when(history.list("COMMERCIAL","000M","000A","batch",20,20)).thenReturn(List.of());
    service.changes("000M","000A","batch",2,20,null);
    verify(history).count("COMMERCIAL","000M","000A","batch");verify(history).list("COMMERCIAL","000M","000A","batch",20,20);
  }
  @Test void crossUnitListAndLogQueriesFailBeforeMapperAccess() {
    assertThatThrownBy(()->service.page(null,null,null,null,null,null,null,1,1,20,"HOUSEHOLD")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(()->service.changes(null,null,null,1,20,"HOUSEHOLD")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verifyNoInteractions(mapper,history);
  }
  @Test void missingUnitAndInvalidVersionFilterAreRejected() {
    SecurityContextHolder.clearContext();assertThatThrownBy(()->service.changes(null,null,null,1,20,null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(()->service.page(null,null,null,null,null,null,null,2,1,20,"COMMERCIAL")).isInstanceOf(IllegalArgumentException.class);
  }
}
