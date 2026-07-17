package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 报价成本版本下的单个材料计价场景快照。 */
@Getter
@Setter
@TableName("lp_quote_cost_price_scenario")
public class QuoteCostPriceScenario {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String scenarioNo;
  private Long costRunVersionId;
  private String costRunNo;
  private String scenarioType;
  private String pricePrepareNo;
  private String pricingMonth;
  private BigDecimal cuPrice;
  private String cuPriceSource;
  private Long cuSourceRefId;
  private BigDecimal materialCost;
  private BigDecimal totalCost;
  private String inputSnapshotHash;
  private String status;
  private String message;

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
