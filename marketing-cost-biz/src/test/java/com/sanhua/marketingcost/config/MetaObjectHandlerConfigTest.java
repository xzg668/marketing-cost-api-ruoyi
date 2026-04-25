package com.sanhua.marketingcost.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * MetaObjectHandlerConfig V21 业务单元自动填充测试。
 *
 * <p>覆盖 4 种情况：
 * <ol>
 *   <li>实体含 businessUnitType 字段 + 未手动赋值 + 有登录态 → 自动补齐</li>
 *   <li>实体含 businessUnitType 字段 + 已手动赋值 → 不被覆盖</li>
 *   <li>实体含 businessUnitType 字段 + 无登录态 → 保留 null</li>
 *   <li>实体不含 businessUnitType 字段（如 sys_* / rate 参考表）→ 不抛异常、跳过</li>
 * </ol>
 */
class MetaObjectHandlerConfigTest {

  private final MetaObjectHandlerConfig handler = new MetaObjectHandlerConfig();

  @AfterEach
  void cleanSecurityContext() {
    // 防止测试之间相互污染 SecurityContextHolder
    SecurityContextHolder.clearContext();
  }

  /** 模拟一个带 businessUnitType 字段的业务实体（不依赖生产 entity，避免改动牵连）。 */
  public static class TenantAwareEntity {
    private String businessUnitType;

    public String getBusinessUnitType() {
      return businessUnitType;
    }

    public void setBusinessUnitType(String businessUnitType) {
      this.businessUnitType = businessUnitType;
    }
  }

  /** 模拟一个不含 businessUnitType 字段的系统表实体（如 sys_dict）。 */
  public static class TenantFreeEntity {
    private String name;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }

  // ============================ 测试用例 ============================

  @Test
  @DisplayName("Case 1：实体有字段 + 未赋值 + 有登录态 → 自动写入 businessUnitType")
  void autoFillsWhenFieldExistsAndContextPresent() {
    setCurrentBusinessUnit("HOUSEHOLD");
    TenantAwareEntity entity = new TenantAwareEntity();

    // 直接调 V21 新增的 package-private 方法，避免 strictInsertFill 对 MyBatis TableInfo 的依赖
    handler.fillBusinessUnitTypeIfAbsent(toMetaObject(entity));

    assertEquals("HOUSEHOLD", entity.getBusinessUnitType(),
        "未手动赋值时应从 BusinessUnitContext 自动补齐");
  }

  @Test
  @DisplayName("Case 2：实体已显式赋值 → 保留原值不覆盖")
  void doesNotOverrideExplicitlySetValue() {
    setCurrentBusinessUnit("COMMERCIAL");
    TenantAwareEntity entity = new TenantAwareEntity();
    entity.setBusinessUnitType("HOUSEHOLD"); // 调用方显式指定

    // 直接调 V21 新增的 package-private 方法，避免 strictInsertFill 对 MyBatis TableInfo 的依赖
    handler.fillBusinessUnitTypeIfAbsent(toMetaObject(entity));

    assertEquals("HOUSEHOLD", entity.getBusinessUnitType(),
        "已手动赋值时不应被上下文覆盖（否则破坏调用方语义）");
  }

  @Test
  @DisplayName("Case 3：实体有字段但无登录态 → 保留 null（由种子 / 调用方显式指定）")
  void leavesNullWhenNoSecurityContext() {
    SecurityContextHolder.clearContext();
    TenantAwareEntity entity = new TenantAwareEntity();

    // 直接调 V21 新增的 package-private 方法，避免 strictInsertFill 对 MyBatis TableInfo 的依赖
    handler.fillBusinessUnitTypeIfAbsent(toMetaObject(entity));

    assertNull(entity.getBusinessUnitType(),
        "无登录态时不应强制写值，保留 null 由调用方显式处理");
  }

  @Test
  @DisplayName("Case 4：实体无 businessUnitType 字段 → 跳过，不抛异常")
  void skipsWhenFieldAbsent() {
    setCurrentBusinessUnit("COMMERCIAL");
    TenantFreeEntity entity = new TenantFreeEntity();

    // 不应抛异常
    // 直接调 V21 新增的 package-private 方法，避免 strictInsertFill 对 MyBatis TableInfo 的依赖
    handler.fillBusinessUnitTypeIfAbsent(toMetaObject(entity));
  }

  @Test
  @DisplayName("Case 5：空字符串上下文应视作无值 → 保留 null")
  void treatsEmptyContextAsAbsent() {
    setCurrentBusinessUnit(""); // 异常情况：details 里 key 存在但值为空串
    TenantAwareEntity entity = new TenantAwareEntity();

    // 直接调 V21 新增的 package-private 方法，避免 strictInsertFill 对 MyBatis TableInfo 的依赖
    handler.fillBusinessUnitTypeIfAbsent(toMetaObject(entity));

    assertNull(entity.getBusinessUnitType(),
        "空字符串上下文应视作缺失，避免写入空串污染数据");
  }

  // ============================ 工具方法 ============================

  /** 构造一个 MyBatis MetaObject，用于驱动 handler.insertFill 的内部 hasGetter/setValue 调用。 */
  private static MetaObject toMetaObject(Object target) {
    return SystemMetaObject.forObject(target);
  }

  /** 在 SecurityContextHolder 里伪造一个带 businessUnitType 的 Authentication。 */
  private static void setCurrentBusinessUnit(String buType) {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            "test-user", null, java.util.List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTANT")));
    Map<String, Object> details = new HashMap<>();
    details.put(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, buType);
    auth.setDetails(details);
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
}
