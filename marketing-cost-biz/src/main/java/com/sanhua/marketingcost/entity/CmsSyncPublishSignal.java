package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("cms_sync_publish_signal")
public class CmsSyncPublishSignal {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String batchNo;
  private LocalDate dataDate;
  private Integer costYear;
  private String businessUnitType;
  private String status;
  private String message;
  private LocalDateTime readyAt;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
