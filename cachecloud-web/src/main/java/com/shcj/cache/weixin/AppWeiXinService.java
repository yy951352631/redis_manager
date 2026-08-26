package com.shcj.cache.weixin;

import com.alibaba.fastjson.JSON;
import com.shcj.cache.entity.AppAudit;
import com.shcj.cache.entity.AppDesc;
import com.shcj.cache.entity.AppUser;
import com.shcj.cache.util.HttpUtil;
import com.shcj.cache.web.service.UserService;
import com.shcj.cache.web.util.DateUtil;
import com.shcj.cache.weixin.dto.WxMsgRequest;
import com.shcj.cache.weixin.dto.WxResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 企业微信通知服务
 *
 * @author zoushunqing 2023/5/24 16:42
 * @since Dev_1.0.1
 */
@Slf4j
@Component
public class AppWeiXinService {

    @Autowired
    private UserService userService;

    @Value("${cachecloud.weixin.url}")
    private String url;

    @Value("${cachecloud.weixin.corpId}")
    private String corpId;

    @Value("${cachecloud.weixin.agentId}")
    private String agentId;

    @Value("${cachecloud.weixin.corpSecret}")
    private String corpSecret;

    @Value("${cachecloud.weixin.adminIds}")
    private String adminIds;

    private String getToken() {
        HashMap<String, String> params = new HashMap<>();
        params.put("corpid", corpId);
        params.put("corpsecret", corpSecret);
        String resp = "";
        try {
            resp = HttpUtil.sendGetRequest(url + "/cgi-bin/gettoken", params);
        } catch (Exception ex) {
            log.error("getToken error {}, {}, {}", url, corpId, corpSecret, ex);
        }
        log.info("getToken resp-> {}", resp);
        WxResponse wxResponse = JSON.parseObject(resp, WxResponse.class);
        return wxResponse.getAccessToken();
    }

    private void sendMsg(String token, String userId, String msg) {
        HashMap<String, String> params = new HashMap<>();
        params.put("access_token", token);
        WxMsgRequest wxMsgRequest = WxMsgRequest.buildWxMsgRequest(userId, agentId, msg);
        String content = JSON.toJSONString(wxMsgRequest);
        String postResponse = "";
        try {
            postResponse = HttpUtil.sendPostRequest(url + "/cgi-bin/message/send", params, content);
            log.info("weixin sendMsg-> {}  response->{}", content, postResponse);
        } catch (Exception ex) {
            log.info("weixin sendMsg-> {} ", content, ex);
        }
    }

    /**
     * 发送审批消息
     */
    public void sendApproveMsg(AppDesc appDesc, AppAudit appAudit) {
        try {

            String officerName = userService.getOfficerName(appDesc.getOfficer());
            Set<AppUser> noticeUsers = new HashSet<>();

            List<AppUser> officerUsers = userService.getOfficerUserByUserIds(appDesc.getOfficer());
            for (AppUser officerUser : officerUsers) {
                if (!officerUser.getName().equals("admin")) {
                    noticeUsers.add(officerUser);
                }
            }

            for (String adminId : adminIds.split(",")) {
                AppUser byName = userService.getByName(adminId);
                if (byName != null && !noticeUsers.stream().anyMatch(e -> e.getName().equals(byName.getName())) && !byName.getName().equals("admin")) {
                    noticeUsers.add(byName);
                }
            }

            String token = getToken();
            for (AppUser appUser : noticeUsers) {
                String content = "审批提醒：<br>"
                        + " 系统: " + appDesc.getName() + "<br>"
                        + " 类型: " + appAudit.getTypeDesc() + "<br>"
                        + " 描述: " + appAudit.getInfo() + "<br>"
                        + " 状态: " + appAudit.getStatusDesc() + "<br>"
                        + " 处理: " + appAudit.getRefuseReason() + "<br>"
                        + " 负责人: " + officerUsers.get(0).getName() + "(" + officerUsers.get(0).getChName() + ")" + "<br>"
                        + " 时间: " + DateUtil.formatYYYYMMddHHMMSS(new Date());

                sendMsg(token, appUser.getName(), content);
            }
        } catch (Exception ex) {
            log.error("sendApproveMsg error", ex);
        }
    }


}
