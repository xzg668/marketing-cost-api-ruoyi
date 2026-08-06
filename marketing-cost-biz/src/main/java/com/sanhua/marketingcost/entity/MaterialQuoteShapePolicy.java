package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 报价 BOM 使用的料品形态规则。 */
@Data
@TableName("lp_material_quote_shape_policy")
public class MaterialQuoteShapePolicy {

  public static final String MODE_FIXED = "FIXED";
  public static final String MODE_SUPPLIER_RATIO = "SUPPLIER_RATIO";
  public static final Integer ENABLED = 1;
  public static final Integer DISABLED = 0;

  @TableId(type = IdType.AUTO)
  private Long id;

  private String materialOrgCode;
  private String materialCode;
  private String materialName;
  private String materialSpec;
  private String materialModel;
  private String policyMode;
  private String fixedTargetShape;

  @TableField("condition_config_json")
  private String conditionConfigJson;

  @TableField("action_config_json")
  private String actionConfigJson;

  private String effectiveFromMonth;
  private String effectiveToMonth;
  private Integer enabled;
  private String remark;
  private LocalDateTime createdAt;
  private Long createdBy;
  private LocalDateTime updatedAt;
  private Long updatedBy;
}
