package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** U9 BOM 母项副产品档案。 */
@Data
@TableName("lp_u9_bom_byproduct_master")
public class U9BomByproductMaster {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String parentMaterialNo;
  private String parentMaterialName;
  private String parentMaterialSpec;
  private String bomPurpose;
  private String versionNo;
  private String outputType;
  private String byproductMaterialNo;
  private String byproductMaterialName;
  private String operationNo;
  private BigDecimal outputQty;
  private String unit;
  private String status;
  private String productionDeptCode;
  private String productionDeptName;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String u9CreatedBy;
  private LocalDateTime u9CreatedTime;
  private String sourceType;
  private String sourceFileName;
  private String importedBy;
  private LocalDateTime importedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
