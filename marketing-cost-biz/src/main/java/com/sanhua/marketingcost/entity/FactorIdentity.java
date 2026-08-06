package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_factor_identity")
public class FactorIdentity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String businessUnitType;
  private String factorSeqNo;
  private String factorName;
  private String shortName;
  private String priceSource;
  private String identityHash;

  @TableField("canonical_factor_key")
  private String canonicalFactorKey;

  @TableField("canonical_factor_identity_id")
  private Long canonicalFactorIdentityId;

  @TableField("identity_origin")
  private String identityOrigin;

  private String status;
  private String createdBy;
  private LocalDateTime createdAt;
  private String updatedBy;
  private LocalDateTime updatedAt;
}
