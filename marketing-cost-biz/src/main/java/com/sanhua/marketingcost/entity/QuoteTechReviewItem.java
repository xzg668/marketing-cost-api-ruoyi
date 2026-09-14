package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_tech_review_item")
public class QuoteTechReviewItem {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long taskId;
  private Integer reviewRound;
  private Long productId;
  private Long submittedVersionId;
  private String moduleType;
  private String decision;
  private String decisionReason;
  private Long inheritedFromReviewItemId;
  private String differenceSnapshotJson;
  private String validationSnapshotJson;
  private Long decidedBy;
  private String decidedByName;
  private LocalDateTime decidedAt;
  private Integer rowVersion;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;
}
