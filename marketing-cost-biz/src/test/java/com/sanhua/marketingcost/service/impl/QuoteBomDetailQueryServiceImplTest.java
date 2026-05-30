package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureLineDto;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingRowPageResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomPackageStructurePageResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSourceRef;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSourceRefMapper;
import com.sanhua.marketingcost.mapper.BomSettlementRuleMapper;
import com.sanhua.marketingcost.service.PackageComponentStructureReadService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class QuoteBomDetailQueryServiceImplTest {

  private PackageComponentStructureReadService packageStructureReadService;
  private BomCostingRowMapper costingRowMapper;
  private BomCostingRowSourceRefMapper sourceRefMapper;
  private BomSettlementRuleMapper settlementRuleMapper;
  private JdbcTemplate jdbcTemplate;
  private QuoteBomDetailQueryServiceImpl service;

  @BeforeEach
  void setUp() {
    packageStructureReadService = mock(PackageComponentStructureReadService.class);
    costingRowMapper = mock(BomCostingRowMapper.class);
    sourceRefMapper = mock(BomCostingRowSourceRefMapper.class);
    settlementRuleMapper = mock(BomSettlementRuleMapper.class);
    jdbcTemplate = mock(JdbcTemplate.class);
    service =
        new QuoteBomDetailQueryServiceImpl(
            packageStructureReadService,
            costingRowMapper,
            sourceRefMapper,
            settlementRuleMapper,
            jdbcTemplate);
  }

  @Test
  void packageStructureQueryFiltersByPackageParentAndKeepsGaps() {
    when(packageStructureReadService.readByReference("REF-001", "TOP-001", "2026-05"))
        .thenReturn(
            new PackageComponentStructureReadResult(
                "REF-001",
                "TOP-001",
                "2026-05",
                null,
                false,
                List.of(packageLine("PKG-A", "CHILD-A"), packageLine("PKG-B", "CHILD-B")),
                List.of("快照 1 字段不完整: 子件图号")));

    QuoteBomPackageStructurePageResponse response =
        service.pagePackageStructures(" REF-001 ", "TOP-001", "PKG-A", "2026-05", 1, 20);

    assertThat(response.found()).isFalse();
    assertThat(response.total()).isEqualTo(1);
    assertThat(response.list()).singleElement().satisfies(line -> {
      assertThat(line.packageParentCode()).isEqualTo("PKG-A");
      assertThat(line.packageChildCode()).isEqualTo("CHILD-A");
    });
    assertThat(response.gaps()).containsExactly("快照 1 字段不完整: 子件图号");
  }

  @Test
  void costingRowQueryReturnsSourceRefsAndRuleHit() {
    BomCostingRow row = costingRow();
    Page<BomCostingRow> page = new Page<>(1, 20);
    page.setTotal(1);
    page.setRecords(List.of(row));
    when(costingRowMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
    when(sourceRefMapper.selectList(any(Wrapper.class))).thenReturn(List.of(sourceRef()));
    when(settlementRuleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule()));

    QuoteBomCostingRowPageResponse response =
        service.pageCostingRows("OA-001", "FIN-001", "BOX-001", "2026-05", 1, 20);

    assertThat(response.total()).isEqualTo(1);
    assertThat(response.list()).singleElement().satisfies(dto -> {
      assertThat(dto.sourceSummary()).isEqualTo("REFERENCED_PACKAGE x1");
      assertThat(dto.matchedSettlementRuleId()).isEqualTo(7L);
      assertThat(dto.matchedSettlementRuleName()).isEqualTo("包装组件父件截断");
      assertThat(dto.settlementRowType()).isEqualTo("PACKAGE_PARENT");
      assertThat(dto.matchedRuleAction()).isEqualTo("STOP_AS_PACKAGE");
      assertThat(dto.matchedRuleRemark()).isEqualTo("包装父件作为结算边界");
      assertThat(dto.sourceRefs()).singleElement().satisfies(ref -> {
        assertThat(ref.packageReferenceId()).isEqualTo(801L);
        assertThat(ref.sourceSnapshotDetailId()).isEqualTo(902L);
        assertThat(ref.sourcePath()).isEqualTo("/REF-001/PKG-A/BOX-001/");
      });
    });
  }

  @Test
  void costingRowQueryAllowsRowsWithoutSourceRefsAndRuleHit() {
    BomCostingRow row = costingRow();
    row.setId(null);
    row.setMatchedSettlementRuleId(null);
    Page<BomCostingRow> page = new Page<>(1, 20);
    page.setTotal(1);
    page.setRecords(List.of(row));
    when(costingRowMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

    QuoteBomCostingRowPageResponse response =
        service.pageCostingRows(null, null, null, null, 1, 20);

    assertThat(response.total()).isEqualTo(1);
    assertThat(response.list()).singleElement().satisfies(dto -> {
      assertThat(dto.sourceSummary()).isEqualTo("未写入来源追溯");
      assertThat(dto.matchedRuleAction()).isNull();
      assertThat(dto.matchedRuleRemark()).isNull();
      assertThat(dto.sourceRefs()).isEmpty();
    });
  }

  @Test
  void costingProductQueryGroupsRowsByOaProductAndPeriod() {
    Page<Map<String, Object>> page = new Page<>(1, 20);
    page.setTotal(1);
    page.setRecords(
        List.of(
            Map.of(
                "oaNo", "OA-001",
                "topProductCode", "FIN-001",
                "periodMonth", "2026-05",
                "rowCount", 84L,
                "ruleHitCount", 3L,
                "subtreeCostRequiredCount", 1L,
                "buildBatchId", "qbp_20260528_001",
                "latestBuiltAt", LocalDateTime.parse("2026-05-28T10:00:00"))));
    when(costingRowMapper.selectMapsPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

    var response = service.pageCostingProducts("OA-001", "FIN-001", "BOX-001", "2026-05", 1, 20);

    assertThat(response.total()).isEqualTo(1);
    assertThat(response.list()).singleElement().satisfies(dto -> {
      assertThat(dto.oaNo()).isEqualTo("OA-001");
      assertThat(dto.topProductCode()).isEqualTo("FIN-001");
      assertThat(dto.periodMonth()).isEqualTo("2026-05");
      assertThat(dto.rowCount()).isEqualTo(84L);
      assertThat(dto.ruleHitCount()).isEqualTo(3L);
      assertThat(dto.subtreeCostRequiredCount()).isEqualTo(1L);
      assertThat(dto.buildBatchId()).isEqualTo("qbp_20260528_001");
      assertThat(dto.latestBuiltAt()).isEqualTo(LocalDateTime.parse("2026-05-28T10:00:00"));
    });
  }

  private PackageComponentStructureLineDto packageLine(String parentCode, String childCode) {
    return new PackageComponentStructureLineDto(
        1L,
        2L,
        "REF-001",
        "TOP-001",
        "2026-05",
        1,
        parentCode,
        "包装父件",
        "父规格",
        "父型号",
        "父图号",
        "虚拟件",
        "1515501",
        "PCS",
        new BigDecimal("1.00000000"),
        new BigDecimal("1.00000000"),
        new BigDecimal("1.00000000"),
        10L,
        "/TOP-001/" + parentCode + "/",
        childCode,
        "包装子件",
        "子规格",
        "子型号",
        "子图号",
        "采购件",
        "1515601",
        "PCS",
        new BigDecimal("2.00000000"),
        new BigDecimal("2.00000000"),
        new BigDecimal("1.00000000"),
        20L,
        parentCode,
        "/TOP-001/" + parentCode + "/" + childCode + "/",
        1);
  }

  private BomCostingRow costingRow() {
    BomCostingRow row = new BomCostingRow();
    row.setId(1001L);
    row.setOaNo("OA-001");
    row.setTopProductCode("FIN-001");
    row.setParentCode("PKG-A");
    row.setMaterialCode("BOX-001");
    row.setMaterialName("包装盒");
    row.setMaterialSpec("SPEC");
    row.setShapeAttr("采购件");
    row.setSourceCategory("外购");
    row.setCostElementCode("PACK");
    row.setBomPurpose("主制造");
    row.setBomVersion("V1");
    row.setLevel(2);
    row.setPath("/FIN-001/PKG-A/BOX-001/");
    row.setQtyPerParent(new BigDecimal("2.00000000"));
    row.setQtyPerTop(new BigDecimal("2.00000000"));
    row.setIsCostingRow(1);
    row.setSubtreeCostRequired(0);
    row.setRawHierarchyNodeId(3001L);
    row.setMatchedSettlementRuleId(7L);
    row.setSettlementRowType("PACKAGE_PARENT");
    row.setBuildBatchId("qbp_20260528_001");
    row.setBuiltAt(LocalDateTime.parse("2026-05-28T10:00:00"));
    row.setPeriodMonth("2026-05");
    row.setAsOfDate(LocalDate.parse("2026-05-31"));
    row.setRawVersionEffectiveFrom(LocalDate.parse("2026-05-31"));
    return row;
  }

  private BomCostingRowSourceRef sourceRef() {
    BomCostingRowSourceRef ref = new BomCostingRowSourceRef();
    ref.setId(2001L);
    ref.setCostingRowId(1001L);
    ref.setSourcePartType("REFERENCED_PACKAGE");
    ref.setSourceRawHierarchyId(3001L);
    ref.setSourceTaskId(501L);
    ref.setPreparationId(201L);
    ref.setPackageReferenceId(801L);
    ref.setPackageReferenceDetailId(802L);
    ref.setReferenceFinishedCode("REF-001");
    ref.setSourceTopProductCode("REF-001");
    ref.setSourceSnapshotId(901L);
    ref.setSourceSnapshotDetailId(902L);
    ref.setSourcePath("/REF-001/PKG-A/BOX-001/");
    return ref;
  }

  private BomSettlementRule rule() {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setId(7L);
    rule.setRuleCode("PACKAGE_STOP");
    rule.setRuleName("包装组件父件截断");
    rule.setRuleCategory("PACKAGE_STOP");
    rule.setSettlementAction("STOP_AS_PACKAGE");
    rule.setRemark("包装父件作为结算边界");
    return rule;
  }
}
