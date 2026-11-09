package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.supplier.SupplierSupplyRatioEditService;
import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SupplierSupplyRatioControllerTest {
  SupplierSupplyRatioEditService edits;SupplierSupplyRatioService service;SupplierSupplyRatioImportService imports;SupplierSupplyRatioController controller;
  @BeforeEach void setup(){service=mock(SupplierSupplyRatioService.class);imports=mock(SupplierSupplyRatioImportService.class);edits=mock(SupplierSupplyRatioEditService.class);controller=new SupplierSupplyRatioController(service,imports,edits);var a=new UsernamePasswordAuthenticationToken("finance",null);a.setDetails(Map.of("businessUnitType","COMMERCIAL"));SecurityContextHolder.getContext().setAuthentication(a);}
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  @Test void importUsesSelectedUnitAndAuthenticatedOperator() {
    var result=new SupplierSupplyRatioImportResponse();result.setBatchNo("batch");when(imports.importExcel(any(InputStream.class),eq("ratio.xlsx"),eq("COMMERCIAL"),eq("finance"),isNull())).thenReturn(result);
    var response=controller.importExcel(new MockMultipartFile("file","ratio.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",new byte[]{1}),null,null,SecurityContextHolder.getContext().getAuthentication());
    assertThat(response.getData().getBatchNo()).isEqualTo("batch");
  }
  @Test void pageAndChangesForwardSupplierAndBatchFilters() {
    when(service.page("M",null,null,null,"S","batch",null,1,1,20,"COMMERCIAL")).thenReturn(new SupplierSupplyRatioPageResponse(0,List.of()));
    assertThat(controller.page("M",null,null,null,"S","batch",null,1,null,1,20).isSuccess()).isTrue();
    controller.changes("M","S","batch",null,2,30);verify(service).changes("M","S","batch",2,30,"COMMERCIAL");
  }
  @Test void formerPatchAndDeleteEndpointsHaveNoWriteHandler() throws Exception {
    var mvc=MockMvcBuilders.standaloneSetup(controller).build();
    assertThat(mvc.perform(patch("/api/v1/supplier-supply-ratios/1").contentType("application/json").content("{\"supplyRatio\":0}")).andReturn().getResponse().getStatus()).isIn(404,405);
    assertThat(mvc.perform(delete("/api/v1/supplier-supply-ratios/1")).andReturn().getResponse().getStatus()).isIn(404,405);
    verifyNoInteractions(service,imports);
  }
  @Test void readsAndImportsKeepExistingPermissions() {
    for(var method:SupplierSupplyRatioController.class.getDeclaredMethods()) {
      var permission=method.getAnnotation(PreAuthorize.class);if(permission==null)continue;
      assertThat(permission.value()).isEqualTo("@ss.hasPermi('base:supplier-supply-ratio:"+(Set.of("importExcel","update").contains(method.getName())?"import":"list")+"')");
    }
  }
  @Test void invalidOrEmptyWorkbookCannotReachAWrite() {
    assertThat(controller.importExcel(new MockMultipartFile("file",new byte[0]),null,null,null).isSuccess()).isFalse();
    verifyNoInteractions(imports);
  }
  @Test void editUsesAuthenticatedUnitAndVersionAndReturnsValidationErrors() throws Exception {
    var row = new com.sanhua.marketingcost.entity.SupplierSupplyRatio();
    row.setId(2L);row.setSupplyRatio(new java.math.BigDecimal("0.75"));
    when(edits.update(eq(1L),any(),eq("COMMERCIAL"),eq("finance"))).thenReturn(row);
    var mvc=MockMvcBuilders.standaloneSetup(controller).build();
    var result=mvc.perform(put("/api/v1/supplier-supply-ratios/1")
        .principal(SecurityContextHolder.getContext().getAuthentication())
        .contentType("application/json").content("{\"supplyRatio\":0.75,\"selectionRule\":null}"))
        .andReturn().getResponse();
    assertThat(result.getStatus()).isEqualTo(200);
    assertThat(result.getContentAsString()).contains("\"supplyRatio\":0.75");
    verify(edits).update(1L,new SupplierSupplyRatioUpdateRequest(new java.math.BigDecimal("0.75"),null),"COMMERCIAL","finance");
    when(edits.update(eq(1L),any(),any(),any())).thenThrow(new IllegalArgumentException("请刷新列表后重新编辑"));
    assertThat(controller.update(1L,new SupplierSupplyRatioUpdateRequest(null,null),null).isSuccess()).isFalse();
  }
  @Test void incompleteEditCannotAccidentallyClearRatioOrRule() throws Exception {
    var mvc=MockMvcBuilders.standaloneSetup(controller).build();
    for (String body : List.of("{}", "{\"supplyRatio\":0.5}", "{\"selectionRule\":null}")) {
      assertThat(mvc.perform(put("/api/v1/supplier-supply-ratios/1").contentType("application/json").content(body))
          .andReturn().getResponse().getStatus()).isEqualTo(400);
    }
    verifyNoInteractions(edits);
  }
}
