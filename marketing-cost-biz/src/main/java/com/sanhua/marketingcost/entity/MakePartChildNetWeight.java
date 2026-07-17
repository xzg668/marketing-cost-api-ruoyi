package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 制造件直接子材料净重，按组织、父子料号、BOM 版本和月份维护。 */
@Getter
@Setter
@TableName("lp_make_part_child_net_weight")
public class MakePartChildNetWeight {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String materialOrganizationCode;
  private String parentMaterialNo;
  private String childMaterialNo;
  private String bomVersion;
  private String periodMonth;
  private BigDecimal netWeightG;
  private String sourceType;
  private String sourceReference;
  private String remark;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
