package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 电子图库 Excel 中不可变的原始工程节点及当前版本内的料号解析结果。 */
@Getter
@Setter
@TableName("lp_electronic_drawing_source_node")
public class ElectronicDrawingSourceNode {
  public static final String MATCH_UNMATCHED = "UNMATCHED";
  public static final String MATCH_AMBIGUOUS = "AMBIGUOUS";
  public static final String MATCH_AUTO = "AUTO_MATCHED";
  public static final String MATCH_MANUAL = "MANUALLY_SELECTED";

  @TableId(type = IdType.AUTO)
  private Long id;
  private Long supplementVersionId;
  private Integer sourceRowNo;
  private String sourceSequence;
  private String parentSourceSequence;
  private String drawingCode;
  private String sourceName;
  private BigDecimal qty;
  private String material;
  private String importanceClass;
  private String hsfRiskClass;
  private BigDecimal referenceWeight;
  private String sourceRemark;
  private String matchStatus;
  private String resolvedMaterialCode;
  private String resolvedBy;
  private LocalDateTime resolvedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
