package com.aihub.common.result;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 统一分页请求参数契约，见 docs/API.md §4：page 从 1 开始，参数名统一为 page / size。
 *
 * <p>使用 record + 注解校验，由各服务 Controller 以 {@code @Valid} 触发。
 */
public record PageQuery(
        @Min(value = 1, message = "page must be greater than or equal to 1")
        Integer page,

        @Min(value = 1, message = "size must be greater than or equal to 1")
        @Max(value = 100, message = "size must be less than or equal to 100")
        Integer size
) {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 10;

    /** 归一化后的页码，未传时取默认值。 */
    public int pageOrDefault() {
        return page == null ? DEFAULT_PAGE : page;
    }

    /** 归一化后的每页条数，未传时取默认值。 */
    public int sizeOrDefault() {
        return size == null ? DEFAULT_SIZE : size;
    }
}
