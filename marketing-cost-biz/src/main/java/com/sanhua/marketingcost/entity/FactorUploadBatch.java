package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_factor_upload_batch")
public class FactorUploadBatch {
  /** PRC-01：原稿/财务版本契约；旧数据的未知来源保持为空。 */
  private String priceOrgCode;

  private Integer publicationRevision;
  private String defaultSchemeCode;
  private String parserVersion;
  private LocalDateTime publishedAt;
  private String publicationNote;

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long technicalVersionId;
  private String technicalResultJson;
  private String batchNo;
  private String importType;
  private String importPurpose;
  private String effectiveStrategy;
  private String priceMonth;
  private String businessUnitType;
  private String fileName;
  private String fileSha256;

  /** 只在原稿发布绑定时显式读取，批次列表和API不返回原始文件内容。 */
  @com.baomidou.mybatisplus.annotation.TableField(select = false)
  @com.fasterxml.jackson.annotation.JsonIgnore
  private byte[] sourceWorkbook;

  private String contentHash;
  private String uploadedBy;
  private String status;
  private Integer factorSheetCount;
  private Integer linkedSheetCount;
  private Integer factorRowCount;
  private Integer linkedRowCount;
  private Integer autoBindingCount;
  private Integer warningCount;
  private Integer errorCount;
  private String errorMessage;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
