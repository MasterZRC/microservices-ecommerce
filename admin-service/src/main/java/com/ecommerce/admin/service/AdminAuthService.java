package com.ecommerce.admin.service;

import com.ecommerce.admin.dto.admin.*;

public interface AdminAuthService {

    LoginResponse login(LoginRequest request);

    void logout(String token);

    AdminUserDTO getAdminInfo(Long adminId);

    void updatePassword(Long adminId, PasswordChangeRequest request);
}
