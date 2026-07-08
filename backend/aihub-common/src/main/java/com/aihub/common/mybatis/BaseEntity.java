package com.aihub.common.mybatis;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 业务表通用字段基类（docs/DATA.md §2）。
 *
 * <p>{@code deleted} 列为删除时间戳（非 0/1 双态），当前未加 {@code @TableLogic}：
 * 逻辑删除对自定义删除值支持有限，等实现第一个删除操作时再引入。查询需显式过滤 {@link #NOT_DELETED}。
 */
public abstract class BaseEntity implements Serializable {

    /** deleted 列未删除的取值 */
    public static final long NOT_DELETED = 0L;

    /** 雪花 ID，由 MyBatis-Plus 生成（docs/API.md §5） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Long deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public Long getDeleted() {
        return deleted;
    }

    public void setDeleted(Long deleted) {
        this.deleted = deleted;
    }
}
