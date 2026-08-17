package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_collaboration_review_item")
public class QuoteCollaborationReviewItem {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long reviewId;
  private Long productTaskId;
  private String itemType;
  private Long itemRefId;
  private Integer itemVersion;
  private String itemSummary;
  private String differenceSnapshotJson;
  private String validationSnapshotJson;
  private String decision;
  private String decisionReason;
  private Long decidedBy;
  private String decidedByName;
  private LocalDateTime decidedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
