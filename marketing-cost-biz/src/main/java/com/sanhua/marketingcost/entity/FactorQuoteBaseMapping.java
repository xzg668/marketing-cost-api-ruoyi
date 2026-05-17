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
@TableName("lp_factor_quote_base_mapping")
public class FactorQuoteBaseMapping {

  @TableId(type = IdType.AUTO)
  private Long id;

  /** lp_factor_identity.id，代表稳定影响因素身份。 */
  private Long factorIdentityId;

  /** 自动识别命中的规则 id；人工绑定时可以为空。 */
  private Long ruleId;

  /** OA 报价单基价字段编码。 */
  private String quoteFieldCode;

  /** OA 报价单基价字段展示名。 */
  private String quoteFieldName;

  /** 兼容老公式变量编码，如 Cu/Zn/Al。 */
  private String variableCode;

  private String matchedKeyword;
  private String matchSource;
  private String confidence;
  private Integer enabled;

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
