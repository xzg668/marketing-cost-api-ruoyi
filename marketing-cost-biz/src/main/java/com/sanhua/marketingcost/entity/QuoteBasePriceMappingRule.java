package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_base_price_mapping_rule")
public class QuoteBasePriceMappingRule {

  @TableId(type = IdType.AUTO)
  private Long id;

  /** 空串表示全业务单元默认规则，避免唯一键里的 NULL 放过重复数据。 */
  private String businessUnitType;

  /** OA 报价单基价字段编码，如 copper_price/zinc_price/aluminum_price。 */
  private String quoteFieldCode;

  /** 页面展示名，如铜基价/锌基价/铝基价。 */
  private String quoteFieldName;

  /** 兼容老公式变量编码，如 Cu/Zn/Al。 */
  private String variableCode;

  /** 关键词 JSON 数组，用于从影响因素名称/简称中识别公共基价。 */
  private String matchKeywordsJson;

  private String matchMode;
  private Integer priority;
  private Integer enabled;
  private String remark;

  @TableField(fill = FieldFill.INSERT)
  private String createdBy;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private String updatedBy;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  @TableLogic
  private Integer deleted;
}
