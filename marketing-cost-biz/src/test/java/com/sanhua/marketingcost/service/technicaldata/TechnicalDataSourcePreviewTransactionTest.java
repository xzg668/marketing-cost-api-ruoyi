package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCalculationResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.*;

/** Exercise the same preview -> price calculation -> transaction commit boundary as the workbench. */
class TechnicalDataSourcePreviewTransactionTest {
  private final PricePrepareService prices = mock(PricePrepareService.class);
  private final OaFormItemMapper items = mock(OaFormItemMapper.class);
  private final OaFormMapper forms = mock(OaFormMapper.class);
  private final QuoteCostingWorkspaceMapper workspaces = mock(QuoteCostingWorkspaceMapper.class);
  private final QuoteBomContextResolver organizations = mock(QuoteBomContextResolver.class);
  private final TechnicalDataSourceCheckStore store = mock(TechnicalDataSourceCheckStore.class);
  private final TechnicalDataSharedModuleQuery shared = mock(TechnicalDataSharedModuleQuery.class);
  private final java.sql.Connection connection = mock(java.sql.Connection.class);
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  private TechnicalDataQuoteSourceReader reader() throws Exception {
    return reader(prices);
  }

  private TechnicalDataQuoteSourceReader reader(PricePrepareService calculator) throws Exception {
    var dataSource = mock(javax.sql.DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    var manager = new DataSourceTransactionManager(dataSource);
    var priceAttributes = new NameMatchTransactionAttributeSource();
    var readOnly = new DefaultTransactionAttribute();
    readOnly.setReadOnly(true);
    priceAttributes.addTransactionalMethod("calculate*", readOnly);
    var priceProxy = new ProxyFactory(calculator);
    priceProxy.addAdvice(new TransactionInterceptor(manager, priceAttributes));
    var publicSources = mock(TechnicalDataPublicSourceCheck.class);
    when(publicSources.checkDataSources(any(), any())).thenReturn(List.of());
    var checks = new TechnicalDataSourceCheckService(publicSources, (PricePrepareService) priceProxy.getProxy(),
        new OaMessageCodec(json), mock(QuoteTechnicalDataRepository.class),
        mock(TechnicalDataPriceRequirements.class), mock(TechnicalDataPriceOwnership.class));
    var target = new TechnicalDataQuoteSourceReader(items, forms, mock(OaFormItemExtraFieldMapper.class),
        workspaces, organizations, checks, new TechnicalDataModuleRequirementEvaluator(), store,
        new TechnicalDataSourceSnapshotFactory(json), new OaMessageCodec(json),
        mock(TechnicalDataRequirementRefreshService.class), shared);
    var item = new OaFormItem(); item.setId(7L); item.setOaFormId(1L); item.setMaterialNo("PRODUCT");
    var form = new OaForm(); form.setId(1L); form.setOaNo("OA"); form.setBusinessUnitType(null);
    var workspace = new QuoteCostingWorkspace(); workspace.setLastCheckedAt(LocalDateTime.now());
    workspace.setCurrentBomBuildBatchId("BOM");
    when(items.selectById(7L)).thenReturn(item);
    when(forms.selectById(1L)).thenReturn(form);
    when(workspaces.selectByItemAndMonth(7L, "2026-10")).thenReturn(workspace);
    when(organizations.resolveOrganization(form, item)).thenReturn(new QuoteDataOrganization("210", "211"));
    var time = LocalDateTime.now();
    when(store.read(workspace)).thenReturn(new TechnicalDataSourceCheckResponse(7L, "2026-10", "saved", time,
        List.of(new TechnicalDataModuleRequirement("PRICE", true, "PRICE_SOURCE_MISSING", "原缺价",
            TechnicalDataAvailability.MISSING, "old", time)), null, List.of()));
    when(shared.describe(any(), any(), any())).thenReturn(List.of());
    var readerProxy = new ProxyFactory(target);
    readerProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    return (TechnicalDataQuoteSourceReader) readerProxy.getProxy();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void historicalPreviewUsesSavedMonthWhileOrdinaryPriceChecksStillRequireTheActiveMonth(boolean mixedPrice)
      throws Exception {
    var batches = mock(com.sanhua.marketingcost.mapper.PricePrepareBatchMapper.class);
    var priceItems = mock(com.sanhua.marketingcost.mapper.PricePrepareItemMapper.class);
    var gaps = mock(com.sanhua.marketingcost.mapper.PricePrepareGapMapper.class);
    var loader = mock(com.sanhua.marketingcost.service.PricePrepareBomItemLoader.class);
    var adoption = mock(com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionExecutor.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    var calculator = spy(new com.sanhua.marketingcost.service.impl.PricePrepareServiceImpl(
        batches, priceItems, gaps, loader,
        mock(com.sanhua.marketingcost.service.PricePrepareItemClassifier.class),
        mock(com.sanhua.marketingcost.service.NormalMaterialPricePrepareStrategy.class),
        mock(com.sanhua.marketingcost.service.PackageComponentPricePrepareStrategy.class),
        mock(com.sanhua.marketingcost.service.MakePartPricePrepareStrategy.class),
        mock(com.sanhua.marketingcost.service.PricePrepareCurrentStateService.class),
        mock(com.sanhua.marketingcost.service.pricing.monthly.MonthlyQuotationSessions.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE)), adoption,
        mock(com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceStorage.class, withSettings().mockMaker(org.mockito.MockMakers.INLINE)),
        new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec(json),
        mock(com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomRepository.class),
        mock(com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService.class), unit -> "2026-11"));
    var reader = reader(calculator);
    if (mixedPrice) {
      var time = LocalDateTime.now();
      when(store.read(any())).thenReturn(new TechnicalDataSourceCheckResponse(7L, "2026-10", "saved", time,
          List.of(new TechnicalDataModuleRequirement("PRICE", false, "PRICE_BASIS_UNCONFIRMED", "原混合缺项",
              TechnicalDataAvailability.UNCONFIRMED, "old", time)), null, List.of()));
    }

    var checked = reader.preview(7L, "2026-10");

    assertThat(checked.accountingMonth()).isEqualTo("2026-10");
    assertThat(checked.modules()).filteredOn(m -> "PRICE".equals(m.moduleType()))
        .singleElement().satisfies(m -> {
          assertThat(m.availability()).isEqualTo(TechnicalDataAvailability.UNCONFIRMED);
          assertThat(m.reasonCode()).isEqualTo("PRICE_BASIS_UNCONFIRMED");
        });
    verify(loader).loadByQuoteItem("OA", 7L, "PRODUCT", "2026-10");
    verify(calculator).calculateForTechnical(any());
    verify(calculator, never()).calculate(any());
    verify(connection).commit();
    verify(connection, never()).rollback();
    verifyNoInteractions(batches, priceItems, gaps, adoption);
    verify(store, never()).save(any(), any(), any());

    var request = new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest();
    request.setOaNo("OA"); request.setOaFormItemId(7L); request.setTopProductCode("PRODUCT");
    request.setPeriodMonth("2026-10");
    assertThatThrownBy(() -> calculator.calculate(request)).isInstanceOf(IllegalArgumentException.class)
        .hasMessage("当前生效核算月份为 2026-11，请刷新后按生效月份核算；调价确认后才切换月份");
    assertThatThrownBy(() -> calculator.generate(request)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("当前生效核算月份为 2026-11");
  }

  @ParameterizedTest
  @ValueSource(strings = {"TECHNICAL", "INVALID_INPUT", "DATABASE"})
  void failedPriceTransactionPreservesOriginalCauseAndDoesNotBecomeUnexpectedRollback(String kind) throws Exception {
    RuntimeException failure = switch (kind) {
      case "TECHNICAL" -> new com.sanhua.marketingcost.service.EffectiveTechnicalDataException(
          "TECH_DATA_TASK_NOT_SUBMITTED", 7L, "2026-10", List.of("SOLDER"), "焊料尚未提交");
      case "INVALID_INPUT" -> new IllegalArgumentException("历史价格来源不完整");
      default -> new org.springframework.dao.DataAccessResourceFailureException("价格来源读取失败");
    };
    when(prices.calculateForTechnical(any())).thenThrow(failure);
    var reader = reader();
    assertThatThrownBy(() -> reader.preview(7L, "2026-10")).isSameAs(failure);
    verify(connection).rollback();
    verify(connection, never()).commit();
    verify(store, never()).save(any(), any(), any());
  }

  @Test void successfulPreviewStillReadsCurrentPricesWithoutSavingTheWorkspace() throws Exception {
    var result = new PricePrepareCalculationResult();
    var summary = new PricePrepareGenerateResult(); summary.setStatus("SUCCESS"); result.setSummary(summary);
    var price = new PricePrepareItem(); price.setMaterialCode("RAW"); price.setStatus("READY");
    price.setUnitPrice(BigDecimal.ONE); result.setItems(List.of(price)); result.setGaps(List.of());
    when(prices.calculateForTechnical(any())).thenReturn(result);
    var checked = reader().preview(7L, "2026-10");
    assertThat(checked.modules()).filteredOn(m -> "PRICE".equals(m.moduleType()))
        .allSatisfy(m -> assertThat(m.availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE));
    verify(connection).commit();
    verify(connection, never()).rollback();
    verify(store, never()).save(any(), any(), any());
  }
}
