package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_plate_material_mapping")
public class PlateMaterialMapping {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String businessUnitType;
  private String normalizedModel;
  private String plateMaterial;
  private Integer rowVersion;
  private String sourceType;
  private String sourceFileName;
  private String sourceFileSha256;
  private String sourceSheetName;
  private Integer sourceRowNumber;
  private String importBatchId;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;
}
