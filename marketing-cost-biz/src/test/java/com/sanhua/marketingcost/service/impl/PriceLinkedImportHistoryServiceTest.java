package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.PriceLinkedImportBatchDetailDto;
import com.sanhua.marketingcost.entity.ExcelAutoBindingImportLog;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorQuoteBaseMapping;
import com.sanhua.marketingcost.entity.FactorRowRef;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.FormulaValidator;
import com.sanhua.marketingcost.formula.normalize.VariableAliasIndex;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.mapper.ExcelAutoBindingImportLogMapper;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.mapper.FactorQuoteBaseMappingMapper;
import com.sanhua.marketingcost.mapper.FactorRowRefMapper;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceVariableBindingService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PriceLinkedImportHistoryServiceTest {

  private FactorUploadBatchMapper factorUploadBatchMapper;
  private FactorRowRefMapper factorRowRefMapper;
  private FactorIdentityMapper factorIdentityMapper;
  private FactorMonthlyPriceMapper factorMonthlyPriceMapper;
  private FactorQuoteBaseMappingMapper factorQuoteBaseMappingMapper;
  private ExcelAutoBindingImportLogMapper autoBindingImportLogMapper;
  private PriceLinkedItemServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FactorUploadBatch.class);
    TableInfoHelper.initTableInfo(assistant, FactorRowRef.class);
    TableInfoHelper.initTableInfo(assistant, FactorIdentity.class);
    TableInfoHelper.initTableInfo(assistant, FactorMonthlyPrice.class);
    TableInfoHelper.initTableInfo(assistant, FactorQuoteBaseMapping.class);
    TableInfoHelper.initTableInfo(assistant, ExcelAutoBindingImportLog.class);
  }

  @BeforeEach
  void setUp() throws Exception {
    factorUploadBatchMapper = mock(FactorUploadBatchMapper.class);
    factorRowRefMapper = mock(FactorRowRefMapper.class);
    factorIdentityMapper = mock(FactorIdentityMapper.class);
    factorMonthlyPriceMapper = mock(FactorMonthlyPriceMapper.class);
    factorQuoteBaseMappingMapper = mock(FactorQuoteBaseMappingMapper.class);
    autoBindingImportLogMapper = mock(ExcelAutoBindingImportLogMapper.class);
    service = new PriceLinkedItemServiceImpl(
        mock(PriceLinkedItemMapper.class),
        mock(PriceFixedItemMapper.class),
        mock(FinanceBasePriceMapper.class),
        mock(PriceVariableMapper.class),
        mock(PriceVariableBindingService.class),
        mock(FactorVariableRegistryImpl.class),
        new FormulaNormalizer(
            mock(VariableAliasIndex.class), mock(RowLocalPlaceholderRegistry.class)),
        new FormulaDisplayRenderer(
            mock(PriceVariableMapper.class), mock(RowLocalPlaceholderRegistry.class)),
        mock(FormulaValidator.class));
    inject("factorUploadBatchMapper", factorUploadBatchMapper);
    inject("factorRowRefMapper", factorRowRefMapper);
    inject("factorIdentityMapper", factorIdentityMapper);
    inject("factorMonthlyPriceMapper", factorMonthlyPriceMapper);
    inject("factorQuoteBaseMappingMapper", factorQuoteBaseMappingMapper);
    inject("autoBindingImportLogMapper", autoBindingImportLogMapper);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("listImportHistory：普通用户默认只按当前登录人查询当前 BU 批次")
  void listImportHistoryNormalUserScopesToCurrentUserAndBu() {
    login("alice", "COMMERCIAL", "price:linked-item:list");
    when(factorUploadBatchMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(batch(77L, "alice", "2026-05", "COMMERCIAL")));

    var rows = service.listImportHistory("2026-05", null, null, false, 10);

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().getId()).isEqualTo(77L);
    assertThat(rows.getFirst().getUploadedBy()).isEqualTo("alice");
    ArgumentCaptor<Wrapper<FactorUploadBatch>> captor = ArgumentCaptor.forClass(Wrapper.class);
    org.mockito.Mockito.verify(factorUploadBatchMapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("import_type", "price_month", "business_unit_type", "uploaded_by");
  }

  @Test
  @DisplayName("listImportHistory：管理员 includeAllUploaders=true 时不强制按本人过滤")
  void listImportHistoryAdminCanViewAllUploaders() {
    login("admin", "COMMERCIAL", "*:*:*");
    when(factorUploadBatchMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(
            batch(77L, "alice", "2026-05", "COMMERCIAL"),
            batch(78L, "bob", "2026-05", "COMMERCIAL")));

    var rows = service.listImportHistory("2026-05", "COMMERCIAL", null, true, 10);

    assertThat(rows).extracting("uploadedBy").containsExactly("alice", "bob");
    ArgumentCaptor<Wrapper<FactorUploadBatch>> captor = ArgumentCaptor.forClass(Wrapper.class);
    org.mockito.Mockito.verify(factorUploadBatchMapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("import_type", "price_month", "business_unit_type")
        .doesNotContain("uploaded_by");
  }

  @Test
  @DisplayName("listImportHistory：管理员查看全部上传人时仍可按指定上传人过滤")
  void listImportHistoryAdminCanFilterUploadedBy() {
    login("admin", "COMMERCIAL", "*:*:*");
    when(factorUploadBatchMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(batch(78L, "bob", "2026-05", "COMMERCIAL")));

    var rows = service.listImportHistory("2026-05", "COMMERCIAL", "bob", true, 10);

    assertThat(rows).extracting("uploadedBy").containsExactly("bob");
    ArgumentCaptor<Wrapper<FactorUploadBatch>> captor = ArgumentCaptor.forClass(Wrapper.class);
    org.mockito.Mockito.verify(factorUploadBatchMapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("import_type", "price_month", "business_unit_type", "uploaded_by");
  }

  @Test
  @DisplayName("getImportBatchDetail：批次明细从 row_ref/identity/monthly_price 恢复并带上传人和上传时间")
  void getImportBatchDetailRestoresFactorRowsWithUploader() {
    login("alice", "COMMERCIAL", "price:linked-item:list");
    FactorUploadBatch batch = batch(77L, "alice", "2026-05", "COMMERCIAL");
    batch.setStartedAt(LocalDateTime.of(2026, 5, 16, 9, 0));
    batch.setFinishedAt(LocalDateTime.of(2026, 5, 16, 9, 5));
    when(factorUploadBatchMapper.selectById(eq(77L))).thenReturn(batch);
    when(factorRowRefMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rowRef()));
    when(factorIdentityMapper.selectBatchIds(any())).thenReturn(List.of(identity()));
    when(factorMonthlyPriceMapper.selectBatchIds(any())).thenReturn(List.of(monthlyPrice()));
    when(factorQuoteBaseMappingMapper.selectList(any(Wrapper.class))).thenReturn(List.of(quoteBaseMapping()));
    when(autoBindingImportLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    PriceLinkedImportBatchDetailDto detail = service.getImportBatchDetail(77L);

    assertThat(detail).isNotNull();
    assertThat(detail.getBatchId()).isEqualTo("77");
    assertThat(detail.getFactorRows()).hasSize(1);
    var row = detail.getFactorRows().getFirst();
    assertThat(row.getFactorSeqNo()).isEqualTo("64");
    assertThat(row.getFactorName()).isEqualTo("SUS304全名");
    assertThat(row.getShortName()).isEqualTo("SUS304");
    assertThat(row.getPriceSource()).isEqualTo("出厂价");
    assertThat(row.getNewPrice()).isEqualByComparingTo("16.8");
    assertThat(row.getOriginalPrice()).isEqualByComparingTo("17.2");
    assertThat(row.getUnit()).isEqualTo("公斤");
    assertThat(row.getSourceSheetName()).isEqualTo("影响因素10");
    assertThat(row.getSourceRowNumber()).isEqualTo(64);
    assertThat(row.getUploadedBy()).isEqualTo("alice");
    assertThat(row.getUploadedAt()).isEqualTo(LocalDateTime.of(2026, 5, 16, 9, 5));
    assertThat(detail.getQuoteBaseRecognizedCount()).isEqualTo(1);
    assertThat(detail.getQuoteBaseUnrecognizedCount()).isZero();
    assertThat(row.getQuoteBaseDetectStatus()).isEqualTo("RECOGNIZED");
    assertThat(row.getQuoteBaseQuoteFieldCode()).isEqualTo("copper_price");
    assertThat(row.getQuoteBaseQuoteFieldName()).isEqualTo("铜基价");
    assertThat(row.getQuoteBaseVariableCode()).isEqualTo("Cu");
    assertThat(row.getQuoteBaseMatchedKeyword()).isEqualTo("电解铜");
    assertThat(row.getQuoteBaseMatchSource()).isEqualTo("AUTO");
  }

  private void inject(String fieldName, Object value) throws Exception {
    java.lang.reflect.Field field = PriceLinkedItemServiceImpl.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(service, value);
  }

  private static void login(String username, String businessUnitType, String... authorities) {
    var granted = java.util.Arrays.stream(authorities)
        .map(SimpleGrantedAuthority::new)
        .toList();
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

  private static FactorRowRef rowRef() {
    FactorRowRef ref = new FactorRowRef();
    ref.setId(3001L);
    ref.setFactorUploadBatchId(77L);
    ref.setSourceWorkbookName("monthly.xlsx");
    ref.setSourceSheetName("影响因素10");
    ref.setSourceRowNumber(64);
    ref.setFactorIdentityId(100L);
    ref.setFactorMonthlyPriceId(200L);
    ref.setFactorSeqNo("64");
    ref.setFactorName("SUS304全名");
    ref.setShortName("SUS304");
    ref.setPriceSource("出厂价");
    ref.setPrice(new BigDecimal("16.4"));
    ref.setOriginalPrice(new BigDecimal("17.2"));
    ref.setUnit("公斤");
    return ref;
  }

  private static FactorIdentity identity() {
    FactorIdentity identity = new FactorIdentity();
    identity.setId(100L);
    identity.setFactorSeqNo("64");
    identity.setFactorName("SUS304全名");
    identity.setShortName("SUS304");
    identity.setPriceSource("出厂价");
    return identity;
  }

  private static FactorMonthlyPrice monthlyPrice() {
    FactorMonthlyPrice price = new FactorMonthlyPrice();
    price.setId(200L);
    price.setFactorIdentityId(100L);
    price.setPriceMonth("2026-05");
    price.setPrice(new BigDecimal("16.8"));
    return price;
  }

  private static FactorQuoteBaseMapping quoteBaseMapping() {
    FactorQuoteBaseMapping mapping = new FactorQuoteBaseMapping();
    mapping.setId(500L);
    mapping.setFactorIdentityId(100L);
    mapping.setQuoteFieldCode("copper_price");
    mapping.setQuoteFieldName("铜基价");
    mapping.setVariableCode("Cu");
    mapping.setMatchedKeyword("电解铜");
    mapping.setMatchSource("AUTO");
    mapping.setEnabled(1);
    mapping.setDeleted(0);
    return mapping;
  }
}
