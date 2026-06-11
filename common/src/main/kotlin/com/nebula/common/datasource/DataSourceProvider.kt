package com.nebula.common.datasource

import javax.sql.DataSource

/**
 * 数据源提供者接口。
 *
 * 定义获取 [DataSource] 的契约，允许不同实现切换连接池策略（如 HikariCP、Druid 等）。
 */
interface DataSourceProvider {
    /**
     * 获取 [DataSource] 实例。
     *
     * 调用方无需关心连接池的生命周期，由实现类负责初始化和关闭。
     */
    fun getDataSource(): DataSource
}
