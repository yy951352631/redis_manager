package com.shcj.cache.web.controller.api;

import com.shcj.cache.web.controller.api.dto.WikiContentDto;
import com.shcj.cache.web.service.WikiApiService;
import com.shcj.cache.web.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/wiki")
public class WikiApiController extends AbstractAdminApiController {

    @Autowired
    private WikiApiService wikiApiService;

    @GetMapping("/access/client")
    public ApiResponse<WikiContentDto> getClientAccessDoc(HttpServletRequest request) {
        ApiResponse<WikiContentDto> denied = requireAdmin(request);
        if (denied != null) {
            return denied;
        }
        try {
            return ApiResponse.ok(wikiApiService.getClientAccessDoc());
        } catch (Exception e) {
            return ApiResponse.fail(500, "加载文档失败: " + e.getMessage());
        }
    }
}
