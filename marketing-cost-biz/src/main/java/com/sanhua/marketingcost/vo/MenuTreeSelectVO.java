package com.sanhua.marketingcost.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MenuTreeSelectVO {
    private Long id;
    private String label;
    private List<MenuTreeSelectVO> children;
}
