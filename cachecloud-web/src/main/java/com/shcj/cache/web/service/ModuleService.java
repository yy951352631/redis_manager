package com.shcj.cache.web.service;

import com.shcj.cache.entity.ModuleInfo;
import com.shcj.cache.entity.ModuleVersion;
import com.shcj.cache.web.enums.SuccessEnum;

import java.util.List;

/**
 * Created by chenshi on 2021/4/28.
 */
public interface ModuleService {

    public List<ModuleInfo> getAllModules();

    public List<ModuleInfo> getAllModuleVersions();

    public ModuleInfo getModuleVersions(String moduleName);

    public ModuleVersion getModuleVersionById(int versionId);

    public SuccessEnum deleteModule(int moduleId);

    public SuccessEnum saveOrUpdateModule(ModuleInfo moduleInfo);

    public SuccessEnum saveOrUpdateVersion(ModuleVersion moduleVersion);

}
