package com.nebula.common.datasource

import javax.sql.DataSource

interface DataSourceProvider {
    fun getDataSource(): DataSource
}
