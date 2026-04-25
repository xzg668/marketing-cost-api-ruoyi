package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 行局部占位符配置 —— 把 {@code __material} / {@code __scrap} 之类的"行局部宏"
 * 从 Java 硬编码挪到 DB 管理，支持运维/财务自助扩展。
 *
 * <p>每条记录：
 * <ul>
 *   <li>{@code code}           —— 公式里的占位符，如 {@code __material}（两下划线前缀约定）</li>
 *   <li>{@code displayName}    —— 中文公式回显名，供
 *       {@link com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer} 使用</li>
 *   <li>{@code tokenNamesJson} —— 在 {@code lp_price_variable_binding.token_name} 列里
 *       哪些字面值会被识别为该占位符；JSON 字符串数组；供
 *       {@link com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl} 使用</li>
 * </ul>
 *
 * <p>JSON 列保持 {@link String} 原文存储 —— 和项目里 {@code aliasesJson}、
 * {@code contextBindingJson} 同风格，解析放在应用层做（避免引入 TypeHandler 开销）。
 */
@TableName("lp_row_local_placeholder")
public class RowLocalPlaceholder {

  @TableId(type = IdType.AUTO)
  private Long id;

  /** 占位符 code，如 __material；公式里直接用 [code] */
  private String code;

  /** 中文公式回显名，如 "材料含税价格" */
  private String displayName;

  /** binding.token_name 候选字面值清单 —— JSON 数组字符串 */
  private String tokenNamesJson;

  /** 占位符的业务含义描述（给运维 / 财务看） */
  private String description;

  /** 显示排序 */
  private Integer sortOrder;

  /** active / inactive */
  private String status;

  @TableField(fill = FieldFill.INSERT)
  private String createdBy;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private String updatedBy;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  /** MyBatis Plus 软删：0=未删 / 1=已删 */
  @TableLogic
  private Integer deleted;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }

  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }

  public String getDisplayName() { return displayName; }
  public void setDisplayName(String displayName) { this.displayName = displayName; }

  public String getTokenNamesJson() { return tokenNamesJson; }
  public void setTokenNamesJson(String tokenNamesJson) { this.tokenNamesJson = tokenNamesJson; }

  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }

  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }

  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

  public String getUpdatedBy() { return updatedBy; }
  public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

  public Integer getDeleted() { return deleted; }
  public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
