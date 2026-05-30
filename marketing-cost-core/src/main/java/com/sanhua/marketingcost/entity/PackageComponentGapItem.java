package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 包装组件缺结构/缺价记录。
 *
 * <p>当前阶段只落库，OA 推送状态字段预留给后续流程。
 */
@Getter
@Setter
@TableName("lp_package_component_gap_item")
public class PackageComponentGapItem {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String periodMonth;
  private String quoteNo;
  private String oaNo;
  private String topProductCode;
  private String packageMaterialCode;
  private String packageMaterialName;
  private Integer lineNo;
  private String childMaterialCode;
  private String childMaterialName;
  private String gapType;
  private String priceType;
  private String missingMaterialCode;
  private String missingReason;
  private String status;
  private String oaPushStatus;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
