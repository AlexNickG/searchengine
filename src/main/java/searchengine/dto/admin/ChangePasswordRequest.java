package searchengine.dto.admin;

import lombok.Data;

@Data
public class ChangePasswordRequest {
    private String newPassword;
}
