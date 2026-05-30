package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("T3 月度调价对象展开 Mapper 契约")
class MonthlyRepriceObjectExpandMapperContractTest {

  @Test
  @DisplayName("展开范围按 oa_form.calc_status = 已核算，不依赖 lp_cost_run_result")
  void selectMonthlyRepriceCalcObjectsUsesOaCalcStatusNotCostRunResult() throws Exception {
    Method method = OaFormItemMapper.class.getMethod(
        "selectMonthlyRepriceCalcObjects", String.class, String.class);
    String sql = String.join("\n", method.getAnnotation(Select.class).value());

    assertThat(sql)
        .contains("f.calc_status = #{calcStatus}")
        .contains("f.business_unit_type = #{businessUnitType}")
        .contains("i.material_no AS productCode")
        .contains("i.package_method AS packageMethod")
        .contains("f.customer AS customerName")
        .doesNotContain("lp_cost_run_result");
  }
}
