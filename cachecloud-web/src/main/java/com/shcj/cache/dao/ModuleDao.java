package com.shcj.cache.dao;

import com.shcj.cache.entity.ModuleInfo;
import com.shcj.cache.entity.ModuleVersion;
import com.shcj.cache.web.vo.ModuleVersionDetailVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ModuleDao {

    public List<ModuleInfo> getAllModules();

    public ModuleInfo getModule(@Param("moduleName") String moduleName);

    public ModuleVersion getVersion(@Param("versionId") int versionId);

    public List<ModuleVersion> getAllVersions(@Param("moduleId") int moduleId);

    public void delModule(@Param("moduleId") int moduleId);

    public void saveOrUpdate(ModuleInfo moduleInfo);

    public void saveOrUpdateVersion(ModuleVersion moduleVersion);

    public ModuleVersionDetailVo getModuleVersionDetail(@Param("soName") String soName);

    public ModuleVersionDetailVo getModuleDetail(@Param("versionId") int versionId);

}
