package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 图库来源及本单修正版节点；图库原文和修正前版本始终保留。 */
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
  /** 财务修正后的用量单位；图库原始件数为 null。 */
  private String quantityUnit;
  /** 修正版节点对应的上一版节点；原始图库节点为 null。 */
  private Long correctedFromSourceNodeId;
  private String material;
  private String importanceClass;
  private String hsfRiskClass;
  private BigDecimal referenceWeight;
  /** 源重量单位 g/kg；历史未记录单位的行保持 null，不重写原始重量。 */
  private String referenceWeightUnit;
  private String sourceRemark;
  private String matchStatus;
  private String resolvedMaterialCode;
  private String resolvedBy;
  private LocalDateTime resolvedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
