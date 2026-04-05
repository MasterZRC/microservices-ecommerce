package com.ecommerce.admin.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("admin_operation_log")
public class AdminOperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long adminId;

    private String username;

    private String module;

    private String operation;

    private String method;

    private String url;

    private String params;

    private String result;

    private String ip;

    private String userAgent;

    private Integer status;

    private String errorMessage;

    private Long duration;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
