package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.PriceLinkedType2FactorIdentityResolution;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.enums.PriceLinkedType2FactorIdentityResolutionStatus;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorIdentityReadRepository;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-05 类型2影响因素统一身份解析")
class PriceLinkedType2FactorIdentityResolverTest {

  private static final Path TYPE2_SAMPLE = Path.of(
      "/Users/xiexicheng/Desktop/price/采购价表二次开发导入模板-股份251115联动价格导入类型2.xls");

  private PriceLinkedType2FactorIdentityReadRepository repository;
  private PriceLinkedType2FactorIdentityResolverImpl resolver;

  @BeforeEach
  void setUp() {
    repository = mock(PriceLinkedType2FactorIdentityReadRepository.class);
    PriceLinkedType2TextNormalizerImpl textNormalizer =
        new PriceLinkedType2TextNormalizerImpl();
    resolver = new PriceLinkedType2FactorIdentityResolverImpl(
        new FactorCanonicalKeyServiceImpl(textNormalizer),
        textNormalizer,
        repository);
    when(repository.findActiveIdentities(anyString())).thenReturn(List.of());
    when(repository.findActiveMonthlyPrices(anyCollection(), anyString()))
        .thenReturn(List.of());
    when(repository.countActiveLegacyBindings(anyCollection())).thenReturn(Map.of());
  }

  @Test
  @DisplayName("完整名称、简称和取价来源精确命中")
  void resolvesExactFullName() {
    FactorIdentity identity = identity(
        7011L, "BU-A", "长江铜完整名称", "1#Cu", "平均价");
    givenIdentities(identity);
    givenPrices(monthlyPrice(8011L, 7011L, "2026-07", "90"));

    PriceLinkedType2FactorIdentityResolution result = resolver.resolve(
        factor("长江铜完整名称", "1#Cu", "平均价", "90"),
        "BU-A",
        "2026-07");

    assertThat(result.getStatus())
        .isEqualTo(PriceLinkedType2FactorIdentityResolutionStatus.EXACT_MATCH);
    assertThat(result.getSelectedFactorIdentityId()).isEqualTo(7011L);
    assertThat(result.getSelectedTargetMonthPrice()).isEqualByComparingTo("90");
  }

  @Test
  @DisplayName("长江完整名称优先命中长江身份")
  void resolvesChangjiangNameBeforeCanonicalFallback() {
    FactorIdentity changjiang = identity(
        7111L, "BU-A", "上月16日至本月15日长江现货市场1#电解铜含税平均价格",
        "1#Cu", "平均价");
    FactorIdentity smm = identity(
        7112L, "BU-A", "上月16日至本月15日上海有色网SMM1#电解铜含税平均价格",
        "1#Cu", "平均价");
    givenIdentities(changjiang, smm);
    givenPrices(
        monthlyPrice(8111L, 7111L, "2026-07", "90"),
        monthlyPrice(8112L, 7112L, "2026-07", "90"));

    PriceLinkedType2FactorIdentityResolution result = resolver.resolve(
        factor(changjiang.getFactorName(), "1#Cu", "平均价", "90"),
        "BU-A",
        "2026-07");

    assertThat(result.getStatus())
        .isEqualTo(PriceLinkedType2FactorIdentityResolutionStatus.EXACT_MATCH);
    assertThat(result.getSelectedFactorIdentityId()).isEqualTo(7111L);
  }

  @Test
  @DisplayName("SMM 完整名称优先命中 SMM 身份")
  void resolvesSmmNameBeforeCanonicalFallback() {
    FactorIdentity changjiang = identity(
        7211L, "BU-A", "长江现货市场1#电解锌含税平均价格", "1#Zn", "平均价");
    FactorIdentity smm = identity(
        7212L, "BU-A", "上海有色网（SMM）1#电解锌含税平均价格", "1#Zn", "平均价");
    givenIdentities(changjiang, smm);
    givenPrices(
        monthlyPrice(8211L, 7211L, "2026-07", "21.68"),
        monthlyPrice(8212L, 7212L, "2026-07", "21.680000"));

    PriceLinkedType2FactorIdentityResolution result = resolver.resolve(
        factor(" 上海有色网（ｓｍｍ）1#电解锌含税平均价格 ",
            "１＃Ｚｎ", " 平均价 ", "21.680"),
        "bu-a",
        "2026-07");

    assertThat(result.getStatus())
        .isEqualTo(PriceLinkedType2FactorIdentityResolutionStatus.EXACT_MATCH);
    assertThat(result.getSelectedFactorIdentityId()).isEqualTo(7212L);
  }

  @Test
  @DisplayName("名称未命中时按统一因素键和有效绑定使用量稳定选择")
  void resolvesCanonicalKeyByBindingUsage() {
    FactorIdentity lowerUsage = identity(
        9301L, "BU-A", "旧长江铜名称", "1#Cu", "平均价");
    FactorIdentity higherUsage = identity(
        9402L, "BU-A", "旧SMM铜名称", "1#Cu", "平均价");
    givenIdentities(lowerUsage, higherUsage);
    givenPrices(
        monthlyPrice(8301L, 9301L, "2026-07", "90"),
        monthlyPrice(8402L, 9402L, "2026-07", "90.000"));
    when(repository.countActiveLegacyBindings(anyCollection()))
        .thenReturn(Map.of(9301L, 1L, 9402L, 7L));

    PriceLinkedType2FactorIdentityResolution result = resolver.resolve(
        factor("本次 Excel 的新铜名称", "1#Cu", "平均价", "90"),
        "BU-A",
        "2026-07");

    assertThat(result.getStatus())
        .isEqualTo(PriceLinkedType2FactorIdentityResolutionStatus.CANONICAL_MATCH);
    assertThat(result.getCanonicalFactorKey()).isEqualTo("AVG|1#CU");
    assertThat(result.getSelectedFactorIdentityId()).isEqualTo(9402L);
    assertThat(result.getCanonicalMetadataRequiredIdentityIds())
        .containsExactly(9301L, 9402L);
  }

  @Test
  @DisplayName("已经确定统一主身份后固定复用主身份")
  void reusesEstablishedCanonicalMaster() {
    FactorIdentity alias = identity(
        7301L, "BU-A", "长江铜", "1#Cu", "平均价");
    FactorIdentity master = identity(
        7302L, "BU-A", "SMM铜", "1#Cu", "平均价");
    alias.setCanonicalFactorKey("AVG|1#CU");
    alias.setCanonicalFactorIdentityId(7302L);
    master.setCanonicalFactorKey("AVG|1#CU");
    master.setCanonicalFactorIdentityId(7302L);
    givenIdentities(alias, master);
    givenPrices(
        monthlyPrice(8301L, 7301L, "2026-07", "90"),
        monthlyPrice(8302L, 7302L, "2026-07", "90"));
    when(repository.countActiveLegacyBindings(anyCollection()))
        .thenReturn(Map.of(7301L, 99L, 7302L, 1L));

    PriceLinkedType2FactorIdentityResolution result = resolver.resolve(
        factor("长江铜", "1#Cu", "平均价", "90"), "BU-A", "2026-07");

    assertThat(result.getStatus())
        .isEqualTo(PriceLinkedType2FactorIdentityResolutionStatus.EXACT_MATCH);
    assertThat(result.getSelectedFactorIdentityId()).isEqualTo(7302L);
    assertThat(result.getSelectedCanonicalFactorIdentityId()).isEqualTo(7302L);
    assertThat(result.getCanonicalMetadataRequiredIdentityIds()).isEmpty();
  }

  @Test
  @DisplayName("多候选同月价格一致时允许稳定复用")
  void acceptsMultipleCandidatesWithSameMonthPrice() {
    FactorIdentity first = identity(
        7401L, "BU-A", "铜来源甲", "1#Cu", "平均价");
    FactorIdentity second = identity(
        7402L, "BU-A", "铜来源乙", "1#Cu", "平均价");
    givenIdentities(first, second);
    givenPrices(
        monthlyPrice(8401L, 7401L, "2026-07", "90.000000"),
        monthlyPrice(8402L, 7402L, "2026-07", "90"));

    PriceLinkedType2FactorIdentityResolution result = resolver.resolve(
        factor("新的铜名称", "1#Cu", "平均价", "90.0"), "BU-A", "2026-07");

    assertThat(result.isResolved()).isTrue();
    assertThat(result.getCandidates()).hasSize(2);
  }

  @Test
  @DisplayName("多候选同月价格不同则阻断且不任选")
  void blocksDifferentCandidatePrices() {
    FactorIdentity first = identity(
        7501L, "BU-A", "铜来源甲", "1#Cu", "平均价");
    FactorIdentity second = identity(
        7502L, "BU-A", "铜来源乙", "1#Cu", "平均价");
    givenIdentities(first, second);
    givenPrices(
        monthlyPrice(8501L, 7501L, "2026-07", "90"),
        monthlyPrice(8502L, 7502L, "2026-07", "91"));

    PriceLinkedType2FactorIdentityResolution result = resolver.resolve(
        factor("新的铜名称", "1#Cu", "平均价", "90"), "BU-A", "2026-07");

    assertThat(result.getStatus())
        .isEqualTo(PriceLinkedType2FactorIdentityResolutionStatus.PRICE_CONFLICT);
    assertThat(result.getSelectedFactorIdentityId()).isNull();
    assertThat(result.getMessage()).contains("90", "91");
  }

  @Test
  @DisplayName("系统同月价格与本次 Excel 不同同样阻断")
  void blocksIncomingPriceConflict() {
    FactorIdentity identity = identity(
        7601L, "BU-A", "锌名称", "1#Zn", "平均价");
    givenIdentities(identity);
    givenPrices(monthlyPrice(8601L, 7601L, "2026-07", "21.68"));

    PriceLinkedType2FactorIdentityResolution result = resolver.resolve(
        factor("锌名称", "1#Zn", "平均价", "22.215"), "BU-A", "2026-07");

    assertThat(result.getStatus())
        .isEqualTo(PriceLinkedType2FactorIdentityResolutionStatus.PRICE_CONFLICT);
    assertThat(result.isBlocked()).isTrue();
  }

  @Test
  @DisplayName("系统不存在因素时只提出创建要求，不生成数据库 ID")
  void proposesCreateWhenIdentityDoesNotExist() {
    PriceLinkedType2FactorIdentityResolution result = resolver.resolve(
        factor("新因素", "ABC", "供应商季度价", "12.34"), "BU-A", "2026-07");

    assertThat(result.getStatus())
        .isEqualTo(PriceLinkedType2FactorIdentityResolutionStatus.CREATE_REQUIRED);
    assertThat(result.getCanonicalFactorKey()).isEqualTo("供应商季度价|ABC");
    assertThat(result.getSelectedFactorIdentityId()).isNull();
    assertThat(result.getMessage()).contains("需要", "创建");
  }

  @Test
  @DisplayName("解析结果严格按业务单元隔离")
  void isolatesBusinessUnits() {
    FactorIdentity otherBusinessUnit = identity(
        7701L, "BU-B", "铜名称", "1#Cu", "平均价");
    givenIdentities(otherBusinessUnit);

    PriceLinkedType2FactorIdentityResolution result = resolver.resolve(
        factor("铜名称", "1#Cu", "平均价", "90"), "BU-A", "2026-07");

    assertThat(result.getStatus())
        .isEqualTo(PriceLinkedType2FactorIdentityResolutionStatus.CREATE_REQUIRED);
  }

  @Test
  @DisplayName("同一统一因素存在多个主身份配置时阻断")
  void blocksConflictingCanonicalMasters() {
    FactorIdentity first = identity(
        7801L, "BU-A", "铜甲", "1#Cu", "平均价");
    FactorIdentity second = identity(
        7802L, "BU-A", "铜乙", "1#Cu", "平均价");
    first.setCanonicalFactorKey("AVG|1#CU");
    first.setCanonicalFactorIdentityId(7801L);
    second.setCanonicalFactorKey("AVG|1#CU");
    second.setCanonicalFactorIdentityId(7802L);
    givenIdentities(first, second);
    givenPrices(
        monthlyPrice(8801L, 7801L, "2026-07", "90"),
        monthlyPrice(8802L, 7802L, "2026-07", "90"));

    PriceLinkedType2FactorIdentityResolution result = resolver.resolve(
        factor("铜新名称", "1#Cu", "平均价", "90"), "BU-A", "2026-07");

    assertThat(result.getStatus()).isEqualTo(
        PriceLinkedType2FactorIdentityResolutionStatus.CANONICAL_MASTER_CONFLICT);
    assertThat(result.getSelectedFactorIdentityId()).isNull();
  }

  @Test
  @DisplayName("真实类型2 Excel 的 Cu=90、Zn=21.68 均复用动态候选")
  void resolvesRealExcelCuAndZnWithoutFixedIds() throws Exception {
    assertThat(Files.exists(TYPE2_SAMPLE)).as("真实类型2样例存在").isTrue();
    PriceLinkedType2WorkbookParserImpl parser =
        new PriceLinkedType2WorkbookParserImpl(new PriceLinkedWorkbookTypeDetectorImpl());
    PriceLinkedType2WorkbookParseResult workbook = parser.parse(
        new ByteArrayInputStream(Files.readAllBytes(TYPE2_SAMPLE)),
        TYPE2_SAMPLE.getFileName().toString());
    FactorIdentity cu = identity(
        99101L, "BU-A",
        "上月16日至本月15日中华商务网长江现货市场1#电解铜含税平均价格",
        "1#Cu", "平均价");
    FactorIdentity zn = identity(
        99102L, "BU-A",
        "上月16日至本月15日中华商务网长江现货市场1#电解锌含税平均价格",
        "1#Zn", "平均价");
    givenIdentities(cu, zn);
    givenPrices(
        monthlyPrice(99201L, 99101L, "2026-07", "90"),
        monthlyPrice(99202L, 99102L, "2026-07", "21.68"));

    List<PriceLinkedType2FactorIdentityResolution> results =
        resolver.resolve(workbook.getFactorRows(), "BU-A", "2026-07");

    assertThat(results).hasSize(2).allSatisfy(result -> assertThat(result.isResolved()).isTrue());
    assertThat(results).anySatisfy(result -> {
      assertThat(result.getCanonicalFactorKey()).isEqualTo("AVG|1#CU");
      assertThat(result.getSourceRow().getPrice()).isEqualByComparingTo("90");
      assertThat(result.getSelectedFactorIdentityId()).isEqualTo(99101L);
    });
    assertThat(results).anySatisfy(result -> {
      assertThat(result.getCanonicalFactorKey()).isEqualTo("AVG|1#ZN");
      assertThat(result.getSourceRow().getPrice()).isEqualByComparingTo("21.68");
      assertThat(result.getSelectedFactorIdentityId()).isEqualTo(99102L);
    });
  }

  private void givenIdentities(FactorIdentity... identities) {
    when(repository.findActiveIdentities(anyString())).thenReturn(List.of(identities));
  }

  private void givenPrices(FactorMonthlyPrice... prices) {
    when(repository.findActiveMonthlyPrices(anyCollection(), anyString()))
        .thenReturn(List.of(prices));
  }

  private PriceLinkedType2FactorRow factor(
      String name, String shortName, String source, String price) {
    return new PriceLinkedType2FactorRow(
        "Sheet1",
        2,
        "1",
        name,
        shortName,
        source,
        new BigDecimal(price),
        "公斤",
        "E2");
  }

  private FactorIdentity identity(
      Long id,
      String businessUnitType,
      String name,
      String shortName,
      String source) {
    FactorIdentity identity = new FactorIdentity();
    identity.setId(id);
    identity.setBusinessUnitType(businessUnitType);
    identity.setFactorName(name);
    identity.setShortName(shortName);
    identity.setPriceSource(source);
    identity.setStatus("ACTIVE");
    return identity;
  }

  private FactorMonthlyPrice monthlyPrice(
      Long id,
      Long identityId,
      String month,
      String price) {
    FactorMonthlyPrice monthlyPrice = new FactorMonthlyPrice();
    monthlyPrice.setId(id);
    monthlyPrice.setFactorIdentityId(identityId);
    monthlyPrice.setPriceMonth(month);
    monthlyPrice.setPrice(new BigDecimal(price));
    monthlyPrice.setStatus("ACTIVE");
    return monthlyPrice;
  }
}
