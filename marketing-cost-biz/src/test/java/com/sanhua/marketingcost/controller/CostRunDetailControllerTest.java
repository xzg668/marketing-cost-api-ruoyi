package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.service.jjb.JjbReadService;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostRunDetailControllerTest {
  @Test void explicitSelectorsAreForwardedTogetherAndSavedPrecisionIsPreserved() {
    var reader=mock(JjbReadService.class);var sheet=sheet();
    when(reader.resolve("OA-1","P-1",9L,7L,"RUN-7")).thenReturn(new JjbReadService.View(sheet,List.of(),List.of()));
    var result=new CostRunDetailController(reader).getDetail("OA-1","P-1","RUN-7",9L,7L).getData();
    assertThat(result.getSheet()).isSameAs(sheet);assertThat(result.getCostRunNo()).isEqualTo("RUN-7");
    assertThat(result.getFinalQuoteAmount()).isEqualByComparingTo("999999999973.140060");
    assertThat(result.getCuMaterialAdjustment()).isEqualByComparingTo("0.000000");
    verify(reader).resolve("OA-1","P-1",9L,7L,"RUN-7");verifyNoMoreInteractions(reader);
  }
  @Test void legacyUrlUsesTheSameResolverWithoutInventingAnExplicitVersion() {
    var reader=mock(JjbReadService.class);when(reader.resolve("OA-1","P-1",null,null,null)).thenReturn(new JjbReadService.View(sheet(),List.of(),List.of()));
    new CostRunDetailController(reader).getDetail("OA-1","P-1",null,null,null);
    verify(reader).resolve("OA-1","P-1",null,null,null);
  }
  @Test void invalidExplicitVersionDoesNotRetryOrReadLatest() {
    var reader=mock(JjbReadService.class);when(reader.resolve("OA-1","P-1",9L,8L,"RUN-7")).thenThrow(new org.springframework.security.access.AccessDeniedException("scope"));
    assertThatThrownBy(()->new CostRunDetailController(reader).getDetail("OA-1","P-1","RUN-7",9L,8L)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verify(reader).resolve("OA-1","P-1",9L,8L,"RUN-7");verifyNoMoreInteractions(reader);
  }
  private JjbReadModel sheet() {return new JjbReadModel(new JjbReadModel.Identity(7L,"RUN-7","V7","OA-1",9L,"P-1","COMMERCIAL","2026-10","MONTHLY_PRODUCT_COST_V1","FEE_PROFILE_OPTIONAL_V2","SUCCESS",true),
      new JjbReadModel.Header("产品","型号","客户","客户码","图号","保存版本","90.000000","100.000000","元/公斤","标准品","1"),List.of(),List.of(),
      new JjbReadModel.Totals("999999999973.140060","0.000000","999999999973.140060"),List.of());}
}
