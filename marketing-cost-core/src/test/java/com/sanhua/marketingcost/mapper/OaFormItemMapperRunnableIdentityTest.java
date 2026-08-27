package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class OaFormItemMapperRunnableIdentityTest {

  @Test
  void runnableCountsAcceptMaterialModelOrDrawing() throws Exception {
    assertRunnableIdentitySql("countRunnableItems");
    assertRunnableIdentitySql("countCalculatedRunnableItems");
  }

  private void assertRunnableIdentitySql(String methodName) throws Exception {
    Method method = OaFormItemMapper.class.getMethod(methodName, Long.class);
    String sql = String.join(" ", method.getAnnotation(Select.class).value()).toLowerCase();
    assertThat(sql)
        .contains("material_no")
        .contains("sunl_model")
        .contains("customer_drawing")
        .contains(" or ");
  }
}
