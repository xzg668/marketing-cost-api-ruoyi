package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @TableName("lp_monthly_price_import_file")
public class MonthlyPriceImportFile {
  @TableId(type = IdType.AUTO) private Long id;
  private Long importId;
  private String relativePath;
  private String pathHash;
  private String fileSha256;
  @TableField(select = false) @com.fasterxml.jackson.annotation.JsonIgnore
  private byte[] sourceWorkbook;
  private String kind;
  private String status;
  private String parsedJson;
  private String publicationJson;
  private String errorMessage;
  private Long supersedesId;
  private Integer activeFlag;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
