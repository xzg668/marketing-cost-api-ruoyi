package com.sanhua.marketingcost.dto;

import java.time.LocalDateTime;

/** 联动原稿导入弹窗中的已发布文件清单。 */
public record LinkedImportedFileDto(
    Long id,
    String fileName,
    String priceOrgCode,
    Integer rowCount,
    String uploadedBy,
    LocalDateTime publishedAt) {}
