package com.codemem.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class PostgresConfig {

    @Bean(name = "postgresJdbc")
    public JdbcTemplate postgresJdbc(
            @Value("${codemem.postgres.url:jdbc:postgresql://127.0.0.1:5432/codemem}") String url,
            @Value("${codemem.postgres.username:codemem}") String username,
            @Value("${codemem.postgres.password:codemem}") String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return new JdbcTemplate(dataSource);
    }
}