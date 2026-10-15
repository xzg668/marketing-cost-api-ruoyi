package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.entity.FactorUploadRowError;
import com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.FormulaValidator;
import com.sanhua.marketingcost.formula.normalize.VariableAliasIndex;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.mapper.FactorUploadRowErrorMapper;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceVariableBindingService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class LinkedImportedFilesServiceTest {

  private FactorUploadBatchMapper factorUploadBatchMapper;
  private FactorUploadRowErrorMapper factorUploadRowErrorMapper;
  private PriceLinkedItemServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FactorUploadBatch.class);
    TableInfoHelper.initTableInfo(assistant, FactorUploadRowError.class);
  }

  @BeforeEach
  void setUp() throws Exception {
    factorUploadBatchMapper = mock(FactorUploadBatchMapper.class);
    factorUploadRowErrorMapper = mock(FactorUploadRowErrorMapper.class);
    service =
        new PriceLinkedItemServiceImpl(
            mock(PriceLinkedItemMapper.class),
            mock(PriceFixedItemMapper.class),
            mock(PriceVariableMapper.class),
            mock(PriceVariableBindingService.class),
            new FormulaNormalizer(
                mock(VariableAliasIndex.class), mock(RowLocalPlaceholderRegistry.class)),
            new FormulaDisplayRenderer(
                mock(PriceVariableMapper.class), mock(RowLocalPlaceholderRegistry.class)),
            mock(FormulaValidator.class));
    inject("factorUploadBatchMapper", factorUploadBatchMapper);
    inject("factorUploadRowErrorMapper", factorUploadRowErrorMapper);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void importedFilesPagesPublishedOriginalsAndIncludesOrganization() {
    login("admin", "COMMERCIAL", "*:*:*");
    var batch = batch(77L, "alice", "2026-10", "COMMERCIAL");
    batch.setPriceOrgCode("220");
    batch.setLinkedRowCount(123);
    batch.setPublishedAt(LocalDateTime.of(2026, 10, 13, 8, 0));
    when(factorUploadBatchMapper.selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class), any(Wrapper.class)))
        .thenAnswer(invocation -> {
          com.baomidou.mybatisplus.extension.plugins.pagination.Page<FactorUploadBatch> page = invocation.getArgument(0);
          assertThat(page.getCurrent()).isEqualTo(4);
          assertThat(page.getSize()).isEqualTo(20);
          page.setRecords(List.of(batch)); page.setTotal(75);
          return page;
        });
    var result = service.importedFiles("2026-10", "COMMERCIAL", 4, 20);
    assertThat(result.getTotal()).isEqualTo(75);
    assertThat(result.getList()).singleElement().satisfies(row -> {
      assertThat(row.priceOrgCode()).isEqualTo("220");
      assertThat(row.rowCount()).isEqualTo(123);
      assertThat(row.uploadedBy()).isEqualTo("alice");
      assertThat(row.publishedAt()).isEqualTo(batch.getPublishedAt());
    });
    ArgumentCaptor<Wrapper<FactorUploadBatch>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
    org.mockito.Mockito.verify(factorUploadBatchMapper).selectPage(any(), wrapper.capture());
    assertThat(wrapper.getValue().getCustomSqlSegment())
        .contains("price_month", "business_unit_type", "import_type", "status", "technical_version_id IS NULL", "ORDER BY published_at DESC,id DESC")
        .doesNotContain("uploaded_by", "LIMIT");
    assertThat(((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FactorUploadBatch>) wrapper.getValue()).getParamNameValuePairs().values())
        .contains("LINKED_ORIGINAL", "PUBLISHED", "2026-10", "COMMERCIAL");
    org.mockito.Mockito.verifyNoInteractions(factorUploadRowErrorMapper);
  }

  @Test
  void importedFilesKeepsUploaderAndBusinessUnitPermissions() {
    login("alice", "COMMERCIAL", "price:linked-item:import");
    when(factorUploadBatchMapper.selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class), any(Wrapper.class)))
        .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<FactorUploadBatch>());
    service.importedFiles("2026-10", "COMMERCIAL", 1, 20);
    ArgumentCaptor<Wrapper<FactorUploadBatch>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
    org.mockito.Mockito.verify(factorUploadBatchMapper).selectPage(any(), wrapper.capture());
    assertThat(wrapper.getValue().getCustomSqlSegment()).contains("uploaded_by");
    assertThat(((com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FactorUploadBatch>) wrapper.getValue()).getParamNameValuePairs().values()).contains("alice");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.importedFiles("2026-10", "HOUSEHOLD", 1, 20))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.importedFiles("bad-month", "COMMERCIAL", 1, 20))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void inject(String fieldName, Object value) throws Exception {
    java.lang.reflect.Field field = PriceLinkedItemServiceImpl.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(service, value);
  }

  private static void login(String username, String businessUnitType, String... authorities) {
    var granted = java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
    var auth = new UsernamePasswordAuthenticationToken(username, "N/A", granted);
    auth.setDetails(Map.of("businessUnitType", businessUnitType));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  private static FactorUploadBatch batch(
      Long id, String uploadedBy, String priceMonth, String businessUnitType) {
    FactorUploadBatch batch = new FactorUploadBatch();
    batch.setId(id);
    batch.setBatchNo("FUB" + id);
    batch.setImportType("MONTHLY_LINKED_FACTOR");
    batch.setPriceMonth(priceMonth);
    batch.setBusinessUnitType(businessUnitType);
    batch.setFileName("monthly.xlsx");
    batch.setUploadedBy(uploadedBy);
    batch.setStatus("SUCCESS");
    batch.setFactorRowCount(1);
    batch.setLinkedRowCount(1);
    batch.setAutoBindingCount(1);
    batch.setStartedAt(LocalDateTime.of(2026, 5, 16, 9, 0));
    return batch;
  }

}
