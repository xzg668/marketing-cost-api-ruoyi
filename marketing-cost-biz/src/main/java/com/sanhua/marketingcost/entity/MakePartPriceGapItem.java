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
 * 制造件价格生成缺价清单。
 *
 * <p>本表只记录后续 OA 补价需要的结构化输入，不在当前阶段触发 OA 推送。
 */
@Getter
@Setter
@TableName("lp_make_part_price_gap_item")
public class MakePartPriceGapItem {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String calcBatchId;
  private String pricingMonth;
  private LocalDateTime generatedAt;
  private String oaNo;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  private String parentMaterialNo;
  private String parentMaterialName;
  private String childMaterialNo;
  private String childMaterialName;
  private String childMaterialSpec;
  private String scrapCode;
  private String scrapName;

  /** 缺价类型：RAW 原材料价 / SCRAP 废料价。 */
  private String missingPriceRole;

  /** 真正需要推给 OA 补价格的料号。 */
  private String missingMaterialNo;

  private String missingMaterialName;
  private String priceType;
  private String reason;
  private String oaPushStatus;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;
}
