package com.shcj.cache.web.controller.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class AppAddUsersRequestDto implements Serializable {
    private static final long serialVersionUID = 1L;
    private List<Long> userIds = new ArrayList<>();
}
