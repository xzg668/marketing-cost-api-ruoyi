package com.sanhua.marketingcost.vo;

import lombok.Data;

import java.util.List;

@Data
public class DeptTreeSelectVO {
    private Long id;
    private String label;
    private List<DeptTreeSelectVO> children;
}
