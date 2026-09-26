package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("技术资料明细 Mapper 不可变 SQL 契约")
class QuoteTechnicalDataMapperSqlContractTest {

  @Test
  @DisplayName("包装、辅料、工资的新增修改删除都限定DRAFT版本")
  void allDetailWritesRequireDraftVersion() throws Exception {
    for (Class<?> mapper : new Class<?>[] {
        QuoteTechPackageItemMapper.class,
        QuoteTechAuxItemMapper.class,
        QuoteTechSalaryItemMapper.class
    }) {
      assertThat(sql(mapper.getMethod("insertBatchIfDraft", Long.class, java.util.List.class)))
          .contains("version.version_status='DRAFT'");
      assertThat(sql(mapper.getMethod("deleteAllIfDraft", Long.class)))
          .contains("version.version_status='DRAFT'");
    }
  }

  @Test
  @DisplayName("版本内容更新同时限定DRAFT和乐观锁")
  void versionUpdateRequiresDraftAndOptimisticLock() throws Exception {
    String sql = sql(QuoteTechDataVersionMapper.class.getMethod(
        "updateDraftWithVersion",
        com.sanhua.marketingcost.entity.QuoteTechDataVersion.class,
        int.class,
        java.time.LocalDateTime.class));
    assertThat(sql)
        .contains("version_status = 'DRAFT'")
        .contains("row_version = #{expectedVersion}")
        .contains("row_version = row_version + 1");
  }

  @Test
  @DisplayName("状态SQL仅允许草稿提交和已提交版本的审核分支")
  void versionTransitionCannotDowngradeFrozenVersion() throws Exception {
    String sql = sql(QuoteTechDataVersionMapper.class.getMethod(
        "transitionStatus",
        Long.class,
        String.class,
        String.class,
        int.class,
        String.class,
        String.class,
        Long.class,
        java.time.LocalDateTime.class));
    assertThat(sql)
        .contains("#{expectedStatus} = 'DRAFT'")
        .contains("#{targetStatus} IN ('SUBMITTED', 'VOIDED')")
        .contains("#{expectedStatus} = 'SUBMITTED'")
        .contains("#{targetStatus} IN ('APPROVED', 'RETURNED', 'VOIDED')")
        .doesNotContain("'APPROVED' AND #{targetStatus} = 'DRAFT'");
  }

  private static String sql(Method method) {
    Insert insert = method.getAnnotation(Insert.class);
    if (insert != null) return String.join(" ", insert.value());
    Update update = method.getAnnotation(Update.class);
    if (update != null) return String.join(" ", update.value());
    Delete delete = method.getAnnotation(Delete.class);
    if (delete != null) return String.join(" ", delete.value());
    throw new IllegalArgumentException("方法没有SQL注解：" + method);
  }
}
