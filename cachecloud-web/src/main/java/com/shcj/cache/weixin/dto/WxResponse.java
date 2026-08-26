package com.shcj.cache.weixin.dto;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

/**
 * 企业微信返回类
 *
 * @author zoushunqing 2023/5/25 9:28
 * @since Dev_1.0.1
 */
@Data
public class WxResponse {

    @JSONField(name = "errcode")
    private int errCode;

    @JSONField(name = "errmsg")
    private String errMsg;

    @JSONField(name = "access_token")
    private String accessToken;

    @JSONField(name = "expires_in")
    private String expiresIn;

    @JSONField(name = "invaliduser")
    private String invalidUser;

    private boolean isSuccess() {
        return errCode == 0;
    }
}
