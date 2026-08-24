package com.sanhua.marketingcost.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
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
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;

final class QuoteBomAlternativeRebuildTestSupport {

  final BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
  final BomAlternativeGroupResolver groupResolver = mock(BomAlternativeGroupResolver.class);
  final QuoteBomAlternativeSelectionService selectionService =
      mock(QuoteBomAlternativeSelectionService.class);
  final QuoteBomPreparationRecordMapper preparationMapper =
      mock(QuoteBomPreparationRecordMapper.class);
  final QuoteBomAlternativeWorkflowInvalidationService invalidationService =
      mock(QuoteBomAlternativeWorkflowInvalidationService.class);
  final List<BomRawHierarchy> tree = Qba07FormalBomTestSupport.alternativeTree();
  final BomAlternativeGroup group = Qba07FormalBomTestSupport.mainGroup(tree);
  final QuoteBomAlternativeRebuildServiceImpl service =
      new QuoteBomAlternativeRebuildServiceImpl(
          bomMapper, groupResolver, selectionService, preparationMapper, invalidationService);

  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, BomRawHierarchy.class);
    TableInfoHelper.initTableInfo(assistant, QuoteBomPreparationRecord.class);
  }

  void stubBase() {
    when(preparationMapper.selectOne(any())).thenReturn(preparation());
    when(bomMapper.selectList(any())).thenReturn(tree);
    when(groupResolver.resolve(any()))
        .thenReturn(new BomAlternativeGroupResolution(List.of(group), List.of()));
    when(invalidationService.invalidate(any(), any(), any(), any()))
        .thenReturn(new QuoteBomAlternativeWorkflowInvalidationResult(0, 0, 1));
  }

  QuoteBomAlternativeRebuildCommand command(String selected, int expectedVersion) {
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
        "quote-user",
        "切换BOM分支");
  }

  QuoteBomAlternativeSelectionResult selection(
      String selected, int version, boolean idempotent) {
    return selection(selected, version, idempotent, "BUILD-1");
  }

  QuoteBomAlternativeSelectionResult selection(
      String selected, int version, boolean idempotent, String sourceBuildBatchId) {
    return new QuoteBomAlternativeSelectionResult(
        "SEL-" + version,
        Qba07FormalBomTestSupport.GROUP_MAIN,
        "STD",
        selected,
        "STD".equals(selected) ? BomChildType.STANDARD : BomChildType.ALTERNATIVE,
        "STD".equals(selected) ? "MANUAL_STANDARD" : "MANUAL_ALTERNATIVE",
        version,
        "ACTIVE",
        idempotent,
        false,
        true,
        "IMPORT-1",
        sourceBuildBatchId);
  }

  private QuoteBomPreparationRecord preparation() {
    QuoteBomPreparationRecord record = new QuoteBomPreparationRecord();
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
}
