package com.sanhua.marketingcost.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.time.LocalDateTime;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/**
 * MyBatis-Plus 通用字段自动填充处理器。
 *
 * <p>职责：
 * <ul>
 *   <li>insert 时自动填充 createdAt / updatedAt</li>
 *   <li>update 时自动刷新 updatedAt</li>
 *   <li>V21：insert 时，如果实体含 businessUnitType 字段且调用方未手动赋值，
 *       则从 {@link BusinessUnitContext} 获取当前登录用户的业务单元并写入，
 *       保证写入数据天然带上租户标识，配合 {@code @DataScope} 实现完整隔离。</li>
 * </ul>
 */
@Component
public class MetaObjectHandlerConfig implements MetaObjectHandler {

  /** 实体上的业务单元字段名（与实体 getter/setter 命名保持一致） */
  private static final String FIELD_BUSINESS_UNIT_TYPE = "businessUnitType";

  @Override
  public void insertFill(MetaObject metaObject) {
    LocalDateTime now = LocalDateTime.now();
    this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
    this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);

    // V21：自动补齐业务单元（多租户数据隔离核心写入口）
    fillBusinessUnitTypeIfAbsent(metaObject);
  }

  @Override
  public void updateFill(MetaObject metaObject) {
    this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
  }

  /**
   * 当实体含 businessUnitType 字段且当前值为空时，从安全上下文填充。
   *
   * <p>三种情况：
   * <ul>
   *   <li>实体无此字段（如 sys_* 表、rate 参考表）：跳过</li>
   *   <li>调用方已显式赋值：保留调用方的值，不覆盖</li>
   *   <li>无登录态（定时任务 / 启动期 / 测试）：保留 null，由调用方/种子数据显式指定</li>
   * </ul>
   *
   * <p>package-private 便于单元测试直接调用（绕过 strictInsertFill 对 TableInfo 的依赖）。
   */
  void fillBusinessUnitTypeIfAbsent(MetaObject metaObject) {
    if (!metaObject.hasGetter(FIELD_BUSINESS_UNIT_TYPE)) {
      return;
    }
    Object current = metaObject.getValue(FIELD_BUSINESS_UNIT_TYPE);
    if (current != null && !current.toString().isEmpty()) {
      return;
    }
    String buType = BusinessUnitContext.getCurrentBusinessUnitType();
    if (buType != null && !buType.isEmpty() && metaObject.hasSetter(FIELD_BUSINESS_UNIT_TYPE)) {
      metaObject.setValue(FIELD_BUSINESS_UNIT_TYPE, buType);
    }
  }
}
