package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("cms_subject_setting_raw")
public class CmsSubjectSettingRaw {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long importBatchId;
  private Integer rowNo;
  private String firstSubjectCode;
  private String firstSubjectName;
  private String secondSubjectCode;
  private String secondSubjectName;
  private String thirdSubjectCode;
  private String thirdSubjectName;
  private String businessUnitType;
  private LocalDateTime createdAt;
}
