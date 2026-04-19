package com.sanhua.marketingcost.vo;

import lombok.Data;

/**
 * 简化版字典数据 VO，供登录页、下拉框等无需鉴权的场景使用。
 * T16 新增：登录页按 dictType 拉取业务单元选项。
 */
@Data
public class DictDataSimpleVO {
    /** 字典键值（如 COMMERCIAL / HOUSEHOLD） */
    private String value;
    /** 字典展示标签（如 商用部品 / 家用部品） */
    private String label;
}
