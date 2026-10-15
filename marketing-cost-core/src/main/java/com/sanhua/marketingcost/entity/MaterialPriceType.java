package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 一个业务单元、月份、组织和料号下的一项取价顺序。五项共同构成配置。 */
@Getter
@Setter
@TableName("lp_material_price_type")
public class MaterialPriceType {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String materialCode;
  private String materialName;
  private String priceType;
  private String period;
  private String priceOrgCode;
  private Integer priority;
  private Integer manualPriority;
  private Long priorityUpdatedBy;
  private LocalDateTime priorityUpdatedAt;
  private String source;
  private String sourceSystem;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;
  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
