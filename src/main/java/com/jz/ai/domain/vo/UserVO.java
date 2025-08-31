package com.jz.ai.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserVO {
    private Long id;
    private String username;
    private Boolean isAdmin;
}

