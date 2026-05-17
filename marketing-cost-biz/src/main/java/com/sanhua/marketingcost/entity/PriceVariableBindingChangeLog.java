package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_price_variable_binding_change_log")
public class PriceVariableBindingChangeLog {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long bindingId;
  private Long linkedItemId;
  private String tokenName;
  private String action;
  private String oldSource;
  private String newSource;
  private String oldFactorCode;
  private String newFactorCode;
  private Long oldFactorIdentityId;
  private Long newFactorIdentityId;
  private Long oldFactorMonthlyPriceId;
  private Long newFactorMonthlyPriceId;
  private String oldPriceSource;
  private String newPriceSource;
  private String oldExcelFormula;
  private String newExcelFormula;
  private String changeSource;
  private String changedBy;
  private String message;
  private LocalDateTime createdAt;
}
