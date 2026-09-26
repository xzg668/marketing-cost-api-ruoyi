package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Manufacturing;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.ManufacturingEvidence;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.ManufacturingNodeEvidence;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.RawMaterial;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TechnicalManufacturingInputsTest {
  private final TechnicalDataCostingSources sources = mock(TechnicalDataCostingSources.class);
  private final TechnicalDataVersionContentCodec codec =
      mock(TechnicalDataVersionContentCodec.class);
  private final QuoteBomSupplementDetailMapper details = mock(QuoteBomSupplementDetailMapper.class);
  private final TechnicalManufacturingInputs service =
      new TechnicalManufacturingInputs(sources, codec, details);
  private final List<QuoteBomSupplementDetail> rows = new ArrayList<>();
  private QuoteBomSupplementDetail raw;

  @BeforeEach
  void prepareSharedManufacturingSource() {
    var product = new QuoteTechProduct();
    product.setOaFormItemId(11L);
    product.setAccountingMonth("2026-09");
    var version = new QuoteTechDataVersion();
    version.setId(84L);
    var task = new com.sanhua.marketingcost.entity.QuoteTechTask();
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("210");
    var source =
        new TechnicalDataCostingSources.Source("MANUFACTURING", task, product, version, 83L);
    when(sources.preparationSources(99L, "2026-10")).thenReturn(Map.of("MANUFACTURING", source));
    var evidence =
        new ManufacturingNodeEvidence(
            "制造件",
            "DRAW-M",
            new BigDecimal("2"),
            new BigDecimal("10"),
            "g",
            "铜棒",
            null,
            "NO_SCRAP_CONFIRMED",
            List.of());
    var material =
        new RawMaterial(
            "RAW:7",
            "7",
            31L,
            "MAKE",
            "RAW",
            "DRAW-R",
            new BigDecimal(".010"),
            new BigDecimal(".015"),
            "kg",
            null,
            new BigDecimal(".015"),
            new BigDecimal("120"),
            evidence);
    when(codec.manufacturing(version))
        .thenReturn(
            new Manufacturing(
                List.of(material),
                new ManufacturingEvidence(
                    11L, "2026-09", 31L, "a".repeat(64), LocalDateTime.now())));
    var parent = detail("MAKE", "PRODUCT", "/PRODUCT/ED:7/", 1, "2", "2", "只");
    parent.setSourceElectronicNodeId(7L);
    raw = detail("RAW", "MAKE", "/PRODUCT/ED:7/TECH:83:RAW:7/", 2, ".01500000", ".03000000", "kg");
    rows.add(parent);
    rows.add(raw);
    when(details.selectList(any())).thenReturn(rows);
  }

  @Test
  void acceptsEquivalentDecimalQuantitiesAndKeepsOriginalApprovedVersion() {
    var result = service.byParentPath(99L, "2026-10");
    assertThat(result).hasSize(1);
    var input = result.get("/PRODUCT/ED:7/");
    assertThat(input.versionId()).isEqualTo(84L);
    assertThat(input.material().quantityPerParent()).isEqualByComparingTo(".015");
  }

  @ParameterizedTest
  @ValueSource(strings = {"RAW", "OTHER-RAW"})
  void rejectsDuplicateOrAdditionalRawChildren(String material) {
    rows.add(detail(material, "MAKE", "/PRODUCT/ED:7/EXTRA/", 2, ".015", ".030", "kg"));
    assertThatThrownBy(() -> service.byParentPath(99L, "2026-10")).hasMessageContaining("唯一原材料节点");
  }

  @Test
  void rejectsChangedQuantityDespiteUnchangedMaterialCode() {
    raw.setQtyPerParent(new BigDecimal(".020"));
    assertThatThrownBy(() -> service.byParentPath(99L, "2026-10"))
        .hasMessageContaining("单件用量与已确认补录不一致");
  }

  @Test
  void rejectsChangedUnit() {
    raw.setUnit("g");
    assertThatThrownBy(() -> service.byParentPath(99L, "2026-10")).hasMessageContaining("单位或单件用量");
  }

  @Test
  void rejectsInconsistentExtendedQuantity() {
    raw.setQtyPerTop(new BigDecimal(".015"));
    assertThatThrownBy(() -> service.byParentPath(99L, "2026-10"))
        .hasMessageContaining("总用量与父件数量不一致");
  }

  @Test
  void doesNotBorrowRawChildFromAnotherOccurrenceOfTheSameMaterial() {
    raw.setPath("/PRODUCT/ED:70/TECH:83:RAW:70/");
    assertThatThrownBy(() -> service.byParentPath(99L, "2026-10")).hasMessageContaining("唯一原材料节点");
  }

  private static QuoteBomSupplementDetail detail(
      String code,
      String parent,
      String path,
      int level,
      String perParent,
      String perTop,
      String unit) {
    var row = new QuoteBomSupplementDetail();
    row.setMaterialCode(code);
    row.setParentCode(parent);
    row.setPath(path);
    row.setLevel(level);
    row.setQtyPerParent(new BigDecimal(perParent));
    row.setQtyPerTop(new BigDecimal(perTop));
    row.setUnit(unit);
    return row;
  }
}
