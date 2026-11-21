package com.codemem.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * JdbcTemplate for the MySQL source-of-truth database.
 *
 * Spring Boot 的 JdbcTemplateAutoConfiguration 会因为已有 postgresJdbc（手工声明的
 * JdbcTemplate）而整体跳过，所以主库这里显式声明一个，避免按类型注入时无候选。
 */
@Configuration
public class MySqlJdbcConfig {

    @Bean(name = "mysqlJdbc")
    public JdbcTemplate mysqlJdbc(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}