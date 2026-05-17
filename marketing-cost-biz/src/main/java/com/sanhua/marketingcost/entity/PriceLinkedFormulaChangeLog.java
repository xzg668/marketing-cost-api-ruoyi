package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_price_linked_formula_change_log")
public class PriceLinkedFormulaChangeLog {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long linkedItemId;
  private String materialCode;
  private String oldFormulaExpr;
  private String newFormulaExpr;
  private String oldFormulaExprCn;
  private String newFormulaExprCn;
  private String changeSource;
  private String changedBy;
  private String remark;
  private LocalDateTime createdAt;
}
