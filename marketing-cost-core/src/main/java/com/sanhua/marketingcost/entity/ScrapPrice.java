package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 一个业务单元、月份、料号和供应商的当前废料价格；冻结报价另存输入快照。 */
@Getter
@Setter
@TableName("lp_scrap_price")
public class ScrapPrice {
  @TableId(type = IdType.AUTO)
  private Long id;

  private String businessUnitType;
  private String priceMonth;
  private String materialCode;
  private String materialName;
  private String specification;
  private String unit;
  private String currency;
  private Integer taxIncluded;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String supplierCode;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String supplierName;

  @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
  private String supplierKey;

  private String priceType;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  @JsonFormat(shape = JsonFormat.Shape.STRING)
  private BigDecimal fixedPrice;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String sourceFormula;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String expression;

  private String factorBindingsJson;
  private String originType;
  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String supersededIdentityKeysJson;
  private String ruleSourceMonth;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long sourceRuleId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Integer sourceRuleRevision;

  private Integer ruleRevision;
  private Long rowVersion;
  private String contentHash;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  @JsonFormat(shape = JsonFormat.Shape.STRING)
  private BigDecimal unitPrice;

  private String calcStatus;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String calcMessage;

  private String financeOrgCode;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String financeMonth;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long financeBatchId;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Integer financeRevision;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String schemeCode;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String calculationJson;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String inputFingerprint;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private LocalDateTime calculatedAt;

  private String importBatchNo;
  private String sourceFileName;
  private String sourceFileSha256;
  private String sourceSheet;
  private Integer sourceRow;
  private String sourceJson;
  private String importedBy;
  private LocalDateTime importedAt;
  private String createdBy;
  private LocalDateTime createdAt;
  private String updatedBy;
  private LocalDateTime updatedAt;
}
