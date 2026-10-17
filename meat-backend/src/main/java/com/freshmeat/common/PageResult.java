package com.freshmeat.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总记录数 */
    private Long total;

    /** 当前页列表 */
    private List<T> list;

    /** 当前页码 */
    private Long pageNum;

    /** 每页大小 */
    private Long pageSize;

    public static <T> PageResult<T> of(Long total, List<T> list, Long pageNum, Long pageSize) {
        return PageResult.<T>builder()
                .total(total)
                .list(list)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .build();
    }
}
