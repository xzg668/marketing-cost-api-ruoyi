package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @TableName("lp_monthly_price_import")
public class MonthlyPriceImport {
  @TableId(type = IdType.AUTO) private Long id;
  private String businessUnitType;
  private String pricingMonth;
  private String requestKey;
  private String status;
  private Long revision;
  private String resultJson;
  private String createdBy;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
