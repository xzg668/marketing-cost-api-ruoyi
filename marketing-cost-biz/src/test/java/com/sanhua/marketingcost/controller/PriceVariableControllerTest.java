package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.VariableCatalogResponse;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.service.PriceVariableService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PriceVariableController 单测 —— 沿用项目里 Controller 单测惯例：
 * 直接 new Controller + mock Service，不起 MockMvc。
 *
 * <p>核心断言 DoD：{@code /variables/catalog} 响应三组 key（{@code financeFactors}/
 * {@code partContexts}/{@code formulaRefs}）都齐全，并且 {@code partContexts.size() >= 9}
 * —— 这是 V24 seed 的内置部品上下文下限。
 */
class PriceVariableControllerTest {

  private PriceVariableService priceVariableService;
  private PriceVariableController controller;

  @BeforeEach
  void setUp() {
    priceVariableService = mock(PriceVariableService.class);
    controller = new PriceVariableController(
        priceVariableService, mock(RowLocalPlaceholderRegistry.class));
  }

  @Test
  @DisplayName("/variables/catalog：三组 key 齐全 + partContexts.size >= 9（V24 seed 下限）")
  void catalog_returnsThreeGroupsWithEnoughPartContexts() {
    VariableCatalogResponse mocked = buildCatalogWithAtLeastNinePartContexts();
    when(priceVariableService.catalog()).thenReturn(mocked);

    CommonResult<VariableCatalogResponse> result = controller.catalog();

    assertThat(result.isSuccess()).isTrue();
    VariableCatalogResponse data = result.getData();
    // 三组 key 都不是 null —— 前端可直接 .map/.length
    assertThat(data.getFinanceFactors()).isNotNull();
    assertThat(data.getPartContexts()).isNotNull();
    assertThat(data.getFormulaRefs()).isNotNull();
    // 部品上下文至少 9 条（与 V24 seed 约定一致）
    assertThat(data.getPartContexts()).hasSizeGreaterThanOrEqualTo(9);
    // 透传 —— 不做二次组装
    assertThat(data.getFinanceFactors().get(0).getCode()).isEqualTo("Cu");
    assertThat(data.getFinanceFactors().get(0).getCurrentPrice()).isEqualByComparingTo("90");
    assertThat(data.getFormulaRefs().get(0).getFormulaExpr()).isEqualTo("[Cu]/(1+[vat_rate])");
    verify(priceVariableService).catalog();
  }

  /** 构造一个满足 DoD 的响应：1 个 financeFactor + 9 个 partContext + 1 个 formulaRef。 */
  private VariableCatalogResponse buildCatalogWithAtLeastNinePartContexts() {
    VariableCatalogResponse resp = new VariableCatalogResponse();

    VariableCatalogResponse.FinanceFactor cu = new VariableCatalogResponse.FinanceFactor();
    cu.setCode("Cu");
    cu.setName("电解铜");
    cu.setCurrentPrice(new BigDecimal("90"));
    cu.setUnit("元/kg");
    cu.setSource("上海有色网");
    cu.setPricingMonth("2024-03");
    resp.getFinanceFactors().add(cu);

    // 9 条 PART_CONTEXT —— 与 V24 seed 一一对应，确保 DoD 下限
    String[][] parts = new String[][] {
        {"blank_weight", "下料重量"},
        {"net_weight", "产品净重"},
        {"process_fee", "加工费"},
        {"process_fee_incl", "含税加工费"},
        {"agent_fee", "代理费"},
        {"material_price_incl", "材料含税价格"},
        {"scrap_price_incl", "废料含税价格"},
        {"copper_scrap_price", "铜沫价格"},
        {"us_yellow_copper_price", "美国柜装黄铜价格"},
    };
    for (String[] row : parts) {
      VariableCatalogResponse.PartContext pc = new VariableCatalogResponse.PartContext();
      pc.setCode(row[0]);
      pc.setName(row[1]);
      pc.setBinding("{\"source\":\"ENTITY\"}");
      resp.getPartContexts().add(pc);
    }

    VariableCatalogResponse.FormulaRef cuExcl = new VariableCatalogResponse.FormulaRef();
    cuExcl.setCode("Cu_excl");
    cuExcl.setName("不含税电解铜");
    cuExcl.setFormulaExpr("[Cu]/(1+[vat_rate])");
    resp.getFormulaRefs().add(cuExcl);

    return resp;
  }
}
