package com.shcj.cache.util;

import okhttp3.*;

import java.io.IOException;
import java.util.HashMap;

/**
 * http工具类
 *
 * @author zoushunqing 2023/5/24 16:43
 * @since Dev_1.0.1
 */
public class HttpUtil {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static String sendGetRequest(String url, HashMap<String, String> params) throws IOException {
        String finalUrl = assembleUrl(url, params);
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(finalUrl)
                .build();
        Response response = client.newCall(request).execute();
        return response.body().string();
    }

    public static String sendPostRequest(String url, HashMap<String, String> params, String jsonReq) throws IOException {
        OkHttpClient client = new OkHttpClient();
        String finalUrl = assembleUrl(url, params);
        Request request = new Request.Builder()
                .url(finalUrl)
                .post(RequestBody.create(JSON, jsonReq))
                .build();
        Response response = client.newCall(request).execute();
        return response.body().string();
    }

    private static String assembleUrl(String url, HashMap<String, String> params) {
        if (params == null || params.isEmpty()) {
            return url;
        }
        HttpUrl.Builder urlBuilder = HttpUrl.parse(url).newBuilder();
        for (String key : params.keySet()) {
            urlBuilder.addQueryParameter(key, params.get(key));
        }
        return urlBuilder.build().toString();
    }


}
