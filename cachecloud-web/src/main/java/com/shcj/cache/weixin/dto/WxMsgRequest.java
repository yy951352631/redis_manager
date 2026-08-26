package com.shcj.cache.weixin.dto;

import com.alibaba.fastjson.annotation.JSONField;

/**
 * 发送消息请求体
 *
 * @author zoushunqing 2023/5/25 9:42
 * @since Dev_1.0.1
 */
public class WxMsgRequest {

    //    data = {
    //        "touser": touser,
    //        "msgtype": "text",
    //        "agentid": agent_id,
    //        "text": {
    //            "content": content
    //        }
    //    }
    @JSONField(name = "touser")
    private String toUser;

    @JSONField(name = "msgtype")
    private String msgType = "text";

    @JSONField(name = "agentid")
    private String agentId;

    @JSONField(name = "text")
    private TextContent textContent;

    public static WxMsgRequest buildWxMsgRequest(String toUser, String agentId, String content) {
        WxMsgRequest wxMsgRequest = new WxMsgRequest();
        wxMsgRequest.setToUser(toUser);
        wxMsgRequest.setAgentId(agentId);
        TextContent textContent = new TextContent();
        textContent.setContent(content);
        wxMsgRequest.setTextContent(textContent);
        return wxMsgRequest;
    }

    public String getToUser() {
        return toUser;
    }

    public void setToUser(String toUser) {
        this.toUser = toUser;
    }

    public String getMsgType() {
        return msgType;
    }

    public void setMsgType(String msgType) {
        this.msgType = msgType;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public TextContent getTextContent() {
        return textContent;
    }

    public void setTextContent(TextContent textContent) {
        this.textContent = textContent;
    }
}


class TextContent {
    private String content;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
