package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_material_factor_binding_std")
public class MaterialFactorBindingStd {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String businessUnitType;
  private String materialCode;
  private String supplierCode;
  private String tokenName;
  private Long factorIdentityId;
  private String source;
  private String status;
  private Long firstImportBatchId;
  private Long lastImportBatchId;
  private String lastFormula;
  private String createdBy;
  private LocalDateTime createdAt;
  private String updatedBy;
  private LocalDateTime updatedAt;
}
