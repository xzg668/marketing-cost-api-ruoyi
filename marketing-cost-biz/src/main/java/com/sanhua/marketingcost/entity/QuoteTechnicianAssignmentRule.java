package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("lp_quote_technician_assignment_rule")
public class QuoteTechnicianAssignmentRule {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String ruleCode;
  private String ruleName;
  private String businessUnitType;
  private String processCode;
  private String sourceBusinessDivision;
  private String applicantDepartment;
  private String applicantOffice;
  private Long technicianUserId;
  private String technicianOaUserId;
  private String technicianJobNo;
  private Integer priority;
  private String status;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String sourceType;
  private String sourceRecordId;
  private String createdBy;
  private LocalDateTime createdAt;
  private String updatedBy;
  private LocalDateTime updatedAt;
  private String remark;
  @TableLogic
  private Integer deleted;
}
