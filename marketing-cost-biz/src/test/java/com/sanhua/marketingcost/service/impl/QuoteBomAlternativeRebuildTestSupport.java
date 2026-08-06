package com.sanhua.marketingcost.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.service.QuoteBomConfirmationService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroup;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolution;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolver;
import com.sanhua.marketingcost.service.bomalternative.BomChildType;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeRebuildCommand;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeWorkflowInvalidationResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeWorkflowInvalidationService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;

final class QuoteBomAlternativeRebuildTestSupport {

  final BomRawHierarchyMapper bomMapper =
      mock(BomRawHierarchyMapper.class);
  final BomAlternativeGroupResolver groupResolver =
      mock(BomAlternativeGroupResolver.class);
  final QuoteBomAlternativeSelectionService selectionService =
      mock(QuoteBomAlternativeSelectionService.class);
  final QuoteBomPreparationRecordMapper preparationMapper =
      mock(QuoteBomPreparationRecordMapper.class);
  final BomCostingRowMapper costingRowMapper =
      mock(BomCostingRowMapper.class);
  final QuoteBomConfirmationService confirmationService =
      mock(QuoteBomConfirmationService.class);
  final QuoteProductBomCostingBuildService buildService =
      mock(QuoteProductBomCostingBuildService.class);
  final QuoteBomAlternativeWorkflowInvalidationService
      invalidationService =
          mock(
              QuoteBomAlternativeWorkflowInvalidationService.class);
  final List<BomRawHierarchy> tree =
      Qba07FormalBomTestSupport.alternativeTree();
  final BomAlternativeGroup group =
      Qba07FormalBomTestSupport.mainGroup(tree);
  final QuoteBomAlternativeRebuildServiceImpl service =
      new QuoteBomAlternativeRebuildServiceImpl(
          bomMapper,
          groupResolver,
          selectionService,
          preparationMapper,
          costingRowMapper,
          confirmationService,
          buildService,
          invalidationService);

  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(
            new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(
        assistant, BomRawHierarchy.class);
    TableInfoHelper.initTableInfo(
        assistant, QuoteBomPreparationRecord.class);
    TableInfoHelper.initTableInfo(
        assistant, PricePrepareBatch.class);
  }

  void stubBase() {
    when(preparationMapper.selectOne(any()))
        .thenReturn(preparation());
    when(bomMapper.selectList(any())).thenReturn(tree);
    when(groupResolver.resolve(any()))
        .thenReturn(
            new BomAlternativeGroupResolution(
                List.of(group), List.of()));
    when(confirmationService.hasActiveConfirmation(
            any(), any(), any(), any()))
        .thenReturn(false);
    when(invalidationService.invalidate(
            any(), any(), any(), any()))
        .thenReturn(
            new QuoteBomAlternativeWorkflowInvalidationResult(
                1, 2, 3));
    when(buildService.buildByOaFormItem(
            any(), any(), any()))
        .thenReturn(buildResponse());
  }

  QuoteBomAlternativeRebuildCommand command(
      String selected,
      int expectedVersion,
      boolean confirmDiscard) {
    return new QuoteBomAlternativeRebuildCommand(
        "OA-QBA-08",
        801L,
        "TOP",
        "2026-07",
        "210",
        "COMMERCIAL",
        "COMMERCIAL",
        "主制造",
        LocalDate.of(2026, 7, 30),
        Qba07FormalBomTestSupport.GROUP_MAIN,
        selected,
        expectedVersion,
        "BUILD-1",
        confirmDiscard,
        "quote-user",
        "切换BOM分支");
  }

  QuoteBomAlternativeSelectionResult selection(
      String selected,
      int version,
      boolean idempotent) {
    return selection(
        selected, version, idempotent, "BUILD-1");
  }

  QuoteBomAlternativeSelectionResult selection(
      String selected,
      int version,
      boolean idempotent,
      String sourceBuildBatchId) {
    return new QuoteBomAlternativeSelectionResult(
        "SEL-" + version,
        Qba07FormalBomTestSupport.GROUP_MAIN,
        "STD",
        selected,
        "STD".equals(selected)
            ? BomChildType.STANDARD
            : BomChildType.ALTERNATIVE,
        "STD".equals(selected)
            ? "MANUAL_STANDARD"
            : "MANUAL_ALTERNATIVE",
        version,
        "ACTIVE",
        idempotent,
        false,
        true,
        "IMPORT-1",
        sourceBuildBatchId);
  }

  BomCostingRow costingRow(
      long id, String material, int manualModified) {
    BomCostingRow row = new BomCostingRow();
    row.setId(id);
    row.setOaNo("OA-QBA-08");
    row.setOaFormItemId(801L);
    row.setTopProductCode("TOP");
    row.setPeriodMonth("2026-07");
    row.setMaterialCode(material);
    row.setManualModified(manualModified);
    return row;
  }

  private QuoteBomPreparationRecord preparation() {
    QuoteBomPreparationRecord record =
        new QuoteBomPreparationRecord();
    record.setId(88L);
    record.setOaNo("OA-QBA-08");
    record.setOaFormItemId(801L);
    record.setQuoteProductCode("TOP");
    record.setProductType("NON_BARE");
    record.setPriceOrgCode("210");
    record.setMaterialOrganizationCode("COMMERCIAL");
    record.setPreparationStatus("READY");
    record.setActiveFlag(1);
    return record;
  }

  private QuoteBomCostingBuildResponse buildResponse() {
    return new QuoteBomCostingBuildResponse(
        88L,
        null,
        801L,
        "OA-QBA-08",
        "TOP",
        "NON_BARE",
        "2026-07",
        "BUILD-NEW",
        2,
        2,
        0,
        Map.of("RAW_PRODUCT_BOM", 2),
        List.of(),
        LocalDateTime.now());
  }
}
