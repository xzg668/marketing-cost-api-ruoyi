package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.BuildHierarchyRequest;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupKeyGeneratorImpl;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("QBA-03 U9层级构建替代元数据透传")
class U9SourceBuilderAlternativeMetadataTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), BomU9Source.class);
  }

  @Test
  @DisplayName("标准和替代保留不同路径与来源行ID，但写入相同稳定组键")
  void writesStandardAndAlternativeWithSameStableGroupKey() {
    Fixture fixture = fixture();
    when(fixture.u9Mapper.selectList(any(Wrapper.class)))
        .thenReturn(realAlternativeRows(1L, 2L, 3L));

    fixture.builder.build(request("BATCH-001", "主制造"));

    List<BomRawHierarchy> written = fixture.singleWrittenBatch();
    BomRawHierarchy top = row(written, "TOP", null);
    BomRawHierarchy standard = row(written, "STD", "主制造");
    BomRawHierarchy alternative = row(written, "ALT", "主制造");

    assertThat(top.getChildType()).isNull();
    assertThat(top.getAlternativeGroupKey()).isNull();
    assertThat(standard.getChildType()).isEqualTo("STANDARD");
    assertThat(alternative.getChildType()).isEqualTo("ALTERNATIVE");
    assertThat(standard.getAlternativeGroupKey())
        .isNotBlank()
        .hasSize(64)
        .isEqualTo(alternative.getAlternativeGroupKey());
    assertThat(standard.getSourceU9RowId()).isEqualTo(2L);
    assertThat(alternative.getSourceU9RowId()).isEqualTo(3L);
    assertThat(standard.getPath()).isEqualTo("/TOP/PARENT@10@030/STD@10@010/");
    assertThat(alternative.getPath()).isEqualTo("/TOP/PARENT@10@030/ALT@10@010/");
    assertThat(standard.getLevel()).isEqualTo(2);
    assertThat(standard.getQtyPerParent()).isEqualByComparingTo("0.5");
    assertThat(standard.getQtyPerTop()).isEqualByComparingTo("1.0");
  }

  @Test
  @DisplayName("重新导入来源ID和构建批次变化时，业务位置组键保持稳定")
  void rebuildKeepsGroupKeyStableAcrossSourceAndBuildBatches() {
    Fixture fixture = fixture();
    when(fixture.u9Mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            realAlternativeRows(1L, 2L, 3L),
            realAlternativeRows(101L, 102L, 103L));

    fixture.builder.build(request("BATCH-OLD", "主制造"));
    fixture.builder.build(request("BATCH-NEW", "主制造"));

    List<List<BomRawHierarchy>> batches = fixture.writtenBatches();
    BomRawHierarchy first = row(batches.get(0), "STD", "主制造");
    BomRawHierarchy rebuilt = row(batches.get(1), "STD", "主制造");

    assertThat(first.getAlternativeGroupKey()).isEqualTo(rebuilt.getAlternativeGroupKey());
    assertThat(first.getSourceU9RowId()).isEqualTo(2L);
    assertThat(rebuilt.getSourceU9RowId()).isEqualTo(102L);
    assertThat(first.getSourceImportBatchId()).isEqualTo("BATCH-OLD");
    assertThat(rebuilt.getSourceImportBatchId()).isEqualTo("BATCH-NEW");
    assertThat(first.getBuildBatchId()).isNotEqualTo(rebuilt.getBuildBatchId());
  }

  @Test
  @DisplayName("同一父件出现在不同父路径时不混组，每条路径内标准替代仍同组")
  void sameParentAtDifferentPathsGetsDifferentGroupKeys() {
    Fixture fixture = fixture();
    List<BomU9Source> rows =
        List.of(
            source(1L, "TOP", "PARENT", "标准", "主制造", 10, "030", "1"),
            source(2L, "TOP", "PARENT", "标准", "主制造", 20, "040", "1"),
            source(3L, "PARENT", "STD", "标准", "主制造", 10, "010", "1"),
            source(4L, "PARENT", "ALT", "替代", "主制造", 10, "010", "1"));
    when(fixture.u9Mapper.selectList(any(Wrapper.class))).thenReturn(rows);

    fixture.builder.build(request("BATCH-PATH", "主制造"));

    List<BomRawHierarchy> candidates =
        fixture.singleWrittenBatch().stream()
            .filter(row -> "STD".equals(row.getMaterialCode()) || "ALT".equals(row.getMaterialCode()))
            .toList();
    Map<String, List<BomRawHierarchy>> byParentPath =
        candidates.stream().collect(Collectors.groupingBy(row -> parentPath(row.getPath())));

    assertThat(byParentPath).hasSize(2);
    assertThat(byParentPath.values())
        .allSatisfy(
            members -> {
              assertThat(members).hasSize(2);
              assertThat(members)
                  .extracting(BomRawHierarchy::getAlternativeGroupKey)
                  .containsOnly(members.get(0).getAlternativeGroupKey());
            });
    assertThat(byParentPath.values().stream()
            .map(members -> members.get(0).getAlternativeGroupKey())
            .distinct())
        .hasSize(2);
  }

  @Test
  @DisplayName("主制造、半自动、自动相互隔离，普通空类型行仍按原层级计算")
  void isolatesBomPurposesAndKeepsNormalHierarchyMath() {
    Fixture fixture = fixture();
    List<BomU9Source> rows =
        List.of(
            source(1L, "TOP", "STD", "标准", "主制造", 10, "010", "2"),
            source(2L, "TOP", "ALT", "替代", "主制造", 10, "010", "2"),
            source(3L, "TOP", "STD", "标准", "半自动", 10, "010", "2"),
            source(4L, "TOP", "ALT", "替代", "半自动", 10, "010", "2"),
            source(5L, "TOP", "STD", "标准", "自动", 10, "210", "2"),
            source(6L, "TOP", "ALT", "替代", "自动", 10, "210", "2"),
            source(7L, "TOP", "NORMAL", null, "主制造", 20, "020", "3"));
    when(fixture.u9Mapper.selectList(any(Wrapper.class))).thenReturn(rows);

    fixture.builder.build(request("BATCH-PURPOSE", null));

    List<BomRawHierarchy> written = fixture.singleWrittenBatch();
    Map<String, BomRawHierarchy> standardByPurpose =
        written.stream()
            .filter(row -> "STD".equals(row.getMaterialCode()))
            .collect(Collectors.toMap(BomRawHierarchy::getBomPurpose, Function.identity()));
    assertThat(standardByPurpose).containsOnlyKeys("主制造", "半自动", "自动");
    assertThat(standardByPurpose.values())
        .extracting(BomRawHierarchy::getAlternativeGroupKey)
        .doesNotHaveDuplicates();

    BomRawHierarchy normal = row(written, "NORMAL", "主制造");
    assertThat(normal.getChildType()).isEqualTo("NORMAL");
    assertThat(normal.getLevel()).isEqualTo(1);
    assertThat(normal.getPath()).isEqualTo("/TOP/NORMAL@20@020/");
    assertThat(normal.getQtyPerParent()).isEqualByComparingTo("3");
    assertThat(normal.getQtyPerTop()).isEqualByComparingTo("3");
  }

  @Test
  @DisplayName("含替代成员的位置出现空类型时标为UNKNOWN，不擅自猜成标准件")
  void blankTypeInsideAlternativePositionBecomesUnknown() {
    Fixture fixture = fixture();
    List<BomU9Source> rows =
        List.of(
            source(1L, "TOP", "STD", "标准", "主制造", 10, "010", "1"),
            source(2L, "TOP", "ALT", "替代", "主制造", 10, "010", "1"),
            source(3L, "TOP", "UNKNOWN", null, "主制造", 10, "010", "1"));
    when(fixture.u9Mapper.selectList(any(Wrapper.class))).thenReturn(rows);

    fixture.builder.build(request("BATCH-UNKNOWN", "主制造"));

    List<BomRawHierarchy> written = fixture.singleWrittenBatch();
    BomRawHierarchy standard = row(written, "STD", "主制造");
    BomRawHierarchy unknown = row(written, "UNKNOWN", "主制造");
    assertThat(unknown.getChildType()).isEqualTo("UNKNOWN");
    assertThat(unknown.getAlternativeGroupKey())
        .isEqualTo(standard.getAlternativeGroupKey());
  }

  @Test
  @DisplayName("1145900000302真实结构保留201850659标准和201850522替代两条候选")
  void realPressureTransmitterStructureKeepsBothCandidates() {
    Fixture fixture = fixture();
    List<BomU9Source> rows =
        List.of(
            source(
                100L,
                "1145900000302",
                "101850644",
                "标准",
                "主制造",
                10,
                "030",
                "1"),
            source(
                287987L,
                "101850644",
                "201850659",
                "标准",
                "主制造",
                10,
                "010",
                "1"),
            source(
                283417L,
                "101850644",
                "201850522",
                "替代",
                "主制造",
                10,
                "010",
                "1"));
    when(fixture.u9Mapper.selectList(any(Wrapper.class))).thenReturn(rows);

    fixture.builder.build(
        request("BATCH-REAL", "主制造", "1145900000302"));

    List<BomRawHierarchy> written = fixture.singleWrittenBatch();
    BomRawHierarchy standard = row(written, "201850659", "主制造");
    BomRawHierarchy alternative = row(written, "201850522", "主制造");
    assertThat(standard.getAlternativeGroupKey())
        .isEqualTo(alternative.getAlternativeGroupKey());
    assertThat(standard.getSourceU9RowId()).isEqualTo(287987L);
    assertThat(alternative.getSourceU9RowId()).isEqualTo(283417L);
    assertThat(written)
        .filteredOn(
            row ->
                "201850659".equals(row.getMaterialCode())
                    || "201850522".equals(row.getMaterialCode()))
        .hasSize(2);
  }

  private static List<BomU9Source> realAlternativeRows(
      long parentId, long standardId, long alternativeId) {
    return List.of(
        source(parentId, "TOP", "PARENT", "标准", "主制造", 10, "030", "2"),
        source(standardId, "PARENT", "STD", "标准", "主制造", 10, "010", "0.5"),
        source(alternativeId, "PARENT", "ALT", "替代", "主制造", 10, "010", "0.5"));
  }

  private static BomU9Source source(
      long id,
      String parent,
      String child,
      String childType,
      String purpose,
      int childSeq,
      String processSeq,
      String qty) {
    BomU9Source row = new BomU9Source();
    row.setId(id);
    row.setPriceOrgCode("210");
    row.setParentMaterialNo(parent);
    row.setParentMaterialName("NAME-" + parent);
    row.setChildMaterialNo(child);
    row.setChildMaterialName("NAME-" + child);
    row.setChildMaterialSpec("SPEC-" + child);
    row.setChildType(childType);
    row.setBomPurpose(purpose);
    row.setBomVersion("F006");
    row.setBomStatus("已核准");
    row.setChildSeq(childSeq);
    row.setProcessSeq(processSeq);
    row.setQtyPerParent(new BigDecimal(qty));
    row.setEffectiveFrom(LocalDate.of(2026, 5, 21));
    row.setEffectiveTo(LocalDate.of(9999, 12, 31));
    return row;
  }

  private static BuildHierarchyRequest request(String importBatchId, String purpose) {
    return request(importBatchId, purpose, "TOP");
  }

  private static BuildHierarchyRequest request(
      String importBatchId, String purpose, String topProductCode) {
    BuildHierarchyRequest request = new BuildHierarchyRequest();
    request.setImportBatchId(importBatchId);
    request.setPriceOrgCode("210");
    request.setBomPurpose(purpose);
    request.setMode("BY_PRODUCT");
    request.setTopProductCode(topProductCode);
    return request;
  }

  private static BomRawHierarchy row(
      List<BomRawHierarchy> rows, String materialCode, String purpose) {
    return rows.stream()
        .filter(row -> materialCode.equals(row.getMaterialCode()))
        .filter(row -> purpose == null || purpose.equals(row.getBomPurpose()))
        .findFirst()
        .orElseThrow();
  }

  private static String parentPath(String path) {
    String withoutTrailingSlash = path.substring(0, path.length() - 1);
    return withoutTrailingSlash.substring(0, withoutTrailingSlash.lastIndexOf('/') + 1);
  }

  private static Fixture fixture() {
    BomU9SourceMapper u9Mapper = mock(BomU9SourceMapper.class);
    BomRawHierarchyMapper hierarchyMapper = mock(BomRawHierarchyMapper.class);
    PlateCommercialMakeBomExpansionService expansionService =
        mock(PlateCommercialMakeBomExpansionService.class);
    when(hierarchyMapper.batchUpsert(any()))
        .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
    U9SourceBuilder builder =
        new U9SourceBuilder(
            u9Mapper,
            hierarchyMapper,
            expansionService,
            new BomAlternativeGroupKeyGeneratorImpl());
    return new Fixture(u9Mapper, hierarchyMapper, builder);
  }

  private record Fixture(
      BomU9SourceMapper u9Mapper,
      BomRawHierarchyMapper hierarchyMapper,
      U9SourceBuilder builder) {

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<List<BomRawHierarchy>> writtenBatches() {
      ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
      org.mockito.Mockito.verify(hierarchyMapper, org.mockito.Mockito.atLeastOnce())
          .batchUpsert(captor.capture());
      return (List) captor.getAllValues();
    }

    private List<BomRawHierarchy> singleWrittenBatch() {
      assertThat(writtenBatches()).hasSize(1);
      return writtenBatches().get(0);
    }
  }
}
