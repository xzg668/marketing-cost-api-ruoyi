package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class TechnicalDataTaskMapperSqlContractTest {
  @Test
  void taskAndProductPublicationUseDatabaseIdempotencyKeys() throws Exception {
    String taskSql = sql(QuoteTechTaskMapper.class.getMethod(
        "insertOrGetActive", com.sanhua.marketingcost.entity.QuoteTechTask.class));
    String productSql = sql(QuoteTechProductMapper.class.getMethod(
        "insertOrGetActive", com.sanhua.marketingcost.entity.QuoteTechProduct.class));
    String moduleSql = sql(QuoteTechModuleMapper.class.getMethod(
        "insertOrGet", com.sanhua.marketingcost.entity.QuoteTechModule.class));

    assertThat(taskSql).contains("ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)");
    assertThat(productSql).contains("ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)");
    assertThat(moduleSql).contains("ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)");
  }

  @Test
  void workbenchQueriesScopeRowsByActiveProductAndAssignee() throws Exception {
    String productCountSql = sql(QuoteTechProductMapper.class.getMethod(
        "countAccessibleWorkbenchRows", String.class, Long.class, String.class,
        String.class, String.class, String.class));
    String productPageSql = sql(QuoteTechProductMapper.class.getMethod(
        "selectAccessibleWorkbenchPage", String.class, Long.class, String.class,
        String.class, String.class, String.class, int.class, int.class));
    assertThat(productCountSql)
        .contains("JOIN lp_quote_tech_task")
        .contains("product.active_flag=1 AND task.active_flag=1")
        .contains("task.assignee_user_id=#{userId}")
        .contains("product.material_no LIKE CONCAT");
    assertThat(productPageSql)
        .contains("SELECT product.*")
        .contains("ORDER BY task.updated_at DESC")
        .contains("LIMIT #{offset},#{size}");
  }

  private static String sql(Method method) {
    Insert insert = method.getAnnotation(Insert.class);
    if (insert != null) return String.join(" ", insert.value());
    Select select = method.getAnnotation(Select.class);
    if (select != null) return String.join(" ", select.value());
    Update update = method.getAnnotation(Update.class);
    if (update != null) return String.join(" ", update.value());
    throw new IllegalArgumentException("方法没有SQL注解：" + method);
  }
}
