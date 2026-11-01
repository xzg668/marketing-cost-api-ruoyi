package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.CostRunCacheLookupService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

class DepartmentFundCostMatchTest {
  static {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), DepartmentFundRate.class);
  }

  @Test
  void missingMasterUsesOaDivisionAndFinalQuoteRateWithoutAnotherUplift() {
    Fixture fixture = new Fixture(null, " 电子产品事业部 ");
    var items = fixture.calculate();
    var water = item(items, "WATER_POWER");
    BigDecimal minutes = new BigDecimal("4.418940")
        .divide(new BigDecimal("0.406900"), 6, RoundingMode.HALF_UP);
    assertThat(water.getBaseAmount()).isEqualByComparingTo(minutes);
    assertThat(water.getRate()).isEqualByComparingTo("0.062993");
    assertThat(water.getAmount()).isEqualByComparingTo(
        minutes.multiply(new BigDecimal("0.062993")).setScale(6, RoundingMode.HALF_UP));
    assertThat(water.getRemark()).contains("OA事业部", "电子产品事业部").doesNotContain("无配置");
    assertThat(item(items, "OVERHAUL").getRate()).isEqualByComparingTo("0.004304");
    assertThat(item(items, "TOOLING_REPAIR").getRate()).isEqualByComparingTo("0.025765");
    assertThat(item(items, "DEPT_OTHER").getAmount()).isNull();
  }

  @Test
  void existingMasterDivisionWinsOverOaDivision() {
    Fixture fixture = new Fixture("商用部品事业部", "电子产品事业部");
    fixture.configuredDivision = "商用部品事业部";
    var water = item(fixture.calculate(), "WATER_POWER");
    assertThat(water.getRate()).isEqualByComparingTo("0.062993");
    assertThat(water.getRemark()).isNull();
  }

  @Test
  void existingMasterWithMissingRateDoesNotUseAnotherOaDivision() {
    Fixture fixture = new Fixture("商用部品事业部", "电子产品事业部");
    var items = fixture.calculate();
    assertThat(item(items, "WATER_POWER").getRate()).isNull();
    assertThat(item(items, "WATER_POWER").getAmount()).isNull();
    assertThat(item(items, "TOTAL").getAmount()).isNull();
  }

  @Test
  void missingOaDivisionLeavesAmountsAndTotalUnknown() {
    var items = new Fixture(null, " ").calculate();
    assertThat(item(items, "WATER_POWER").getAmount()).isNull();
    assertThat(item(items, "WATER_POWER").getRemark()).contains("OA事业部为空");
    assertThat(item(items, "TOTAL").getAmount()).isNull();
  }

  @Test
  void missingRequiredSubjectDoesNotBecomeZeroAndOnlyMarksThatSubject() {
    Fixture fixture = new Fixture(null, "电子产品事业部");
    fixture.rates = Map.of("大修费用", "0.004304", "工装零星费用", "0.025765");
    var items = fixture.calculate();
    assertThat(item(items, "WATER_POWER").getAmount()).isNull();
    assertThat(item(items, "WATER_POWER").getRemark()).contains("水电费用", "无配置");
    assertThat(item(items, "OVERHAUL").getAmount()).isPositive();
    assertThat(item(items, "OVERHAUL").getRemark()).doesNotContain("水电费用", "无配置");
    assertThat(item(items, "TOTAL").getAmount()).isNull();
  }

  @Test
  void explicitlyConfiguredZeroRateIsStillAValidZeroAmount() {
    Fixture fixture = new Fixture(null, "电子产品事业部");
    fixture.rates = Map.of("大修费用", "0", "工装零星费用", "0", "水电费用", "0");
    var water = item(fixture.calculate(), "WATER_POWER");
    assertThat(water.getRate()).isZero();
    assertThat(water.getAmount()).isZero();
  }

  @Test
  void invalidManhourRateDoesNotBecomeZero() {
    Fixture fixture = new Fixture(null, "电子产品事业部");
    fixture.manhourRate = BigDecimal.ZERO;
    var items = fixture.calculate();
    assertThat(item(items, "WATER_POWER").getAmount()).isNull();
    assertThat(item(items, "WATER_POWER").getRemark()).contains("manhour_rate");
    assertThat(item(items, "TOTAL").getAmount()).isNull();
  }

  private static CostRunCostItemDto item(List<CostRunCostItemDto> items, String code) {
    return items.stream().filter(row -> code.equals(row.getCostCode())).findFirst().orElseThrow();
  }

  private static final class Fixture {
    final String masterDivision;
    final String oaDivision;
    String configuredDivision = "电子产品事业部";
    BigDecimal manhourRate = new BigDecimal("0.406900");
    Map<String, String> rates = Map.of(
        "大修费用", "0.004304", "工装零星费用", "0.025765", "水电费用", "0.062993");

    Fixture(String masterDivision, String oaDivision) {
      this.masterDivision = masterDivision;
      this.oaDivision = oaDivision;
    }

    List<CostRunCostItemDto> calculate() {
      var formMapper = mock(OaFormMapper.class);
      var itemMapper = mock(OaFormItemMapper.class);
      var rawMapper = mock(MaterialMasterRawMapper.class);
      var salaryMapper = mock(CmsCostSourceEffectiveMapper.class);
      var departmentMapper = mock(DepartmentFundRateMapper.class);
      var manufactureMapper = mock(ManufactureRateMapper.class);
      var form = new OaForm();
      form.setId(1L); form.setOaNo("OA-DEPT-FALLBACK");
      form.setBusinessUnitType("COMMERCIAL"); form.setSourceBusinessDivision(oaDivision);
      var product = new OaFormItem();
      product.setId(2L); product.setOaFormId(1L); product.setMaterialNo("P-1");
      product.setBusinessUnitType("COMMERCIAL");
      when(formMapper.selectOne(any())).thenReturn(form);
      when(itemMapper.selectList(any())).thenReturn(List.of(product));
      var raw = new MaterialMasterRaw(); raw.setMaterialCode("P-1");
      raw.setProductionDivision(masterDivision);
      when(rawMapper.selectByLatestBatchAndCodes(any(), any(), eq("COMMERCIAL")))
          .thenReturn(masterDivision == null ? List.of() : List.of(raw));
      var direct = salary("SALARY_DIRECT", "0301", "4.327240");
      var indirect = salary("SALARY_INDIRECT", "0302", "0.091700");
      when(salaryMapper.selectSalarySources(Year.now().getValue(), "COMMERCIAL", Set.of("P-1")))
          .thenReturn(List.of(direct, indirect));
      var manufacture = new ManufactureRate(); manufacture.setFeeRate(new BigDecimal("0.15"));
      when(manufactureMapper.selectOne(any())).thenReturn(manufacture);
      when(departmentMapper.selectOne(any())).thenAnswer(invocation -> {
        AbstractWrapper<DepartmentFundRate, ?, ?> query = invocation.getArgument(0);
        query.getSqlSegment(); var parameters = query.getParamNameValuePairs().values();
        assertThat(parameters).contains(Year.now().getValue(), "COMMERCIAL");
        if (!parameters.contains(configuredDivision)) return null;
        for (var entry : rates.entrySet()) if (parameters.contains(entry.getKey())) {
          var rate = new DepartmentFundRate(); rate.setId(1L);
          rate.setQuoteRatio(new BigDecimal(entry.getValue())); rate.setManhourRate(manhourRate);
          rate.setRateCalculationMode(DepartmentFundRate.RATE_CALCULATION_MODE_FINAL_QUOTE);
          rate.setUpliftRatio(new BigDecimal("1.05"));
          return rate;
        }
        return null;
      });
      var service = new CostRunCostItemServiceImpl(mock(CostRunCostItemMapper.class), formMapper,
          itemMapper, salaryMapper, departmentMapper, mock(AuxCostItemMapper.class),
          mock(CostRunPartItemMapper.class), mock(QualityLossRateMapper.class), manufactureMapper,
          mock(ThreeExpenseRateMapper.class), mock(OtherExpenseRateMapper.class),
          mock(MaterialMasterMapper.class), rawMapper, mock(BomRawHierarchyMapper.class),
          mock(CostRunCacheLookupService.class));
      var context = CostRunContext.quote(form.getOaNo(), 2L, "P-1", null, null,
          "COMMERCIAL", "2026-10", "TEST");
      context.setPriceOrgCode("210"); context.setMaterialOrganizationCode("COMMERCIAL");
      return service.listByMaterialCodes(form.getOaNo(), "P-1", Set.of("P-1"),
          context, List.of(), false, ignored -> {});
    }

    private CmsCostSourceEffective salary(String type, String subject, String amount) {
      var value = new CmsCostSourceEffective(); value.setCostYear(Year.now().getValue());
      value.setSourceType(type); value.setParentCode("P-1"); value.setSubjectCode(subject);
      value.setAmountYuan(new BigDecimal(amount)); value.setBusinessUnitType("COMMERCIAL");
      return value;
    }
  }
}
