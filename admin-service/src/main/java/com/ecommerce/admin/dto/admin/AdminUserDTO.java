package com.ecommerce.admin.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "管理员信息响应")
public class AdminUserDTO {

    @Schema(description = "管理员ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "角色名称")
    private String roleName;

    @Schema(description = "权限列表")
    private String permissions;

    @Schema(description = "最后登录时间")
    private String lastLoginTime;

    @Schema(description = "最后登录IP")
    private String lastLoginIp;
}
