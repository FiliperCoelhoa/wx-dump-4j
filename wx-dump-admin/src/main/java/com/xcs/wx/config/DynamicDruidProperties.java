package com.xcs.wx.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 动态数据源（SQLite）Druid 连接池配置
 * 替代 DatabaseServiceImpl.registerDataSource() 中的硬编码参数
 *
 * @author xcs
 */
@Data
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "dynamic-druid-pool")
public class DynamicDruidProperties {

    /**
     * 初始连接数
     */
    private int initialSize = 5;

    /**
     * 最小空闲连接数
     */
    private int minIdle = 5;

    /**
     * 最大活跃连接数
     */
    private int maxActive = 20;

    /**
     * 获取连接最大等待时间（毫秒）
     */
    private long maxWait = 60000;

    /**
     * 校验查询 SQL
     */
    private String validationQuery = "SELECT 1";

    /**
     * 是否空闲时校验
     */
    private boolean testWhileIdle = true;

    /**
     * 是否借出时校验
     */
    private boolean testOnBorrow = false;

    /**
     * 是否归还时校验
     */
    private boolean testOnReturn = false;

    /**
     * 是否缓存 PreparedStatement
     */
    private boolean poolPreparedStatements = true;
}
