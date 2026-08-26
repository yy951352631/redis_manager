package com.shcj.cache.web.vo;

import com.alibaba.fastjson.JSONObject;
import com.shcj.cache.web.enums.SuccessEnum;

/**
 * 前端操作消息提醒
 *
 * @author zoushunqing 2023/2/7 12:28
 * @since Dev_1.0.1
 */
public class AjaxResult extends JSONObject {

    public static final String STATUS = "status";
    public static final String MSG = "message";
    public static final String DATA = "data";

    private AjaxResult() {

    }

    public static AjaxResult ok(String message) {
        AjaxResult ajaxResult = new AjaxResult();
        ajaxResult.put(STATUS, SuccessEnum.SUCCESS.value());
        ajaxResult.put(MSG, message);
        return ajaxResult;
    }

    public static AjaxResult ok(Object o) {
        AjaxResult ajaxResult = new AjaxResult();
        ajaxResult.put(STATUS, SuccessEnum.SUCCESS.value());
        ajaxResult.put(MSG, "success");
        ajaxResult.put(DATA, o);
        return ajaxResult;
    }

    public static AjaxResult ok(Object o, String message) {
        AjaxResult ajaxResult = new AjaxResult();
        ajaxResult.put(STATUS, SuccessEnum.SUCCESS.value());
        ajaxResult.put(MSG, message);
        ajaxResult.put(DATA, o);
        return ajaxResult;
    }

    public static AjaxResult ok() {
        return AjaxResult.ok("操作成功");
    }

    public static String okString(String message) {
        return AjaxResult.ok(message).toString();
    }

    public static AjaxResult error(String message) {
        AjaxResult ajaxResult = new AjaxResult();
        ajaxResult.put(STATUS, SuccessEnum.ERROR.value());
        ajaxResult.put(MSG, message);
        return ajaxResult;
    }

    public static String errorString(String message) {
        return AjaxResult.error(message).toString();
    }


}
