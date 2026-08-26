package com.shcj.cache.web.service;

import com.shcj.cache.entity.AppImport;

import java.util.List;

/**
 * @Author: rucao
 * @Date: 2021/1/8 上午10:48
 */
public interface AppImportService {
    List<AppImport> getImportAppList(Integer status);

    int save(AppImport appImport);

    int update(AppImport appImport);

    AppImport appImport(Long id);

    AppImport get(Long id);
}
