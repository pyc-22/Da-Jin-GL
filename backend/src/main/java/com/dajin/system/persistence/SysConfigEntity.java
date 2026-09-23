package com.dajin.system.persistence;

import com.baomidou.mybatisplus.annotation.*;

@TableName("sys_config")
public class SysConfigEntity {
    @TableId(value = "config_id", type = IdType.AUTO) private Long configId;
    private Long storeId;
    private String configGroup;
    private String configKey;
    private String configValue;
    private String description;
    private Integer configSort;
    private Integer enabled;
    public Long getConfigId(){return configId;} public Long getStoreId(){return storeId;} public String getConfigGroup(){return configGroup;} public String getConfigKey(){return configKey;} public String getConfigValue(){return configValue;} public String getDescription(){return description;} public Integer getConfigSort(){return configSort;} public Integer getEnabled(){return enabled;}
}
