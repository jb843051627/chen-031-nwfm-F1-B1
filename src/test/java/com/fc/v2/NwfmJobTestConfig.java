package com.fc.v2;

import javax.sql.DataSource;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fc.v2.mapper.custom.NwfmJobLockDao;
import com.fc.v2.service.impl.NwfmJobRowProcessor;
import com.fc.v2.service.impl.TNwfmDeviceRuleServiceImpl;
import com.fc.v2.service.impl.TNwfmJobServiceImpl;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.core.io.ClassPathResource;

import java.util.Date;

/**
 * 核验作业生命周期：H2(MODE=MySQL) 真库集成测试装配。
 * 不引整个 Spring 上下文（避免 Shiro/Druid/Quartz），只装要测的两个 Bean 与相关 Mapper。
 */
@Configuration
@EnableTransactionManagement
@MapperScan(basePackages = {"com.fc.v2.mapper.auto", "com.fc.v2.mapper.custom"})
public class NwfmJobTestConfig {

    @Bean
    public DataSource dataSource() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.h2.Driver.class);
        ds.setUrl("jdbc:h2:mem:nwfm;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }

    @Bean
    public DataSourceInitializer initializer(DataSource ds) {
        DataSourceInitializer init = new DataSourceInitializer();
        init.setDataSource(ds);
        ResourceDatabasePopulator pop = new ResourceDatabasePopulator();
        pop.addScript(new ClassPathResource("schema-test.sql"));
        init.setDatabasePopulator(pop);
        return init;
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
        MybatisSqlSessionFactoryBean f = new MybatisSqlSessionFactoryBean();
        f.setDataSource(ds);
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        f.setConfiguration(cfg);
        GlobalConfig gc = new GlobalConfig();
        gc.setMetaObjectHandler(new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject m) {
                fillStrategy(m, "createBy", "tester");
                strictInsertFill(m, "createTime", Date.class, new Date());
            }

            @Override
            public void updateFill(MetaObject m) {
                fillStrategy(m, "updateBy", "tester");
                strictUpdateFill(m, "updateTime", Date.class, new Date());
            }
        });
        f.setGlobalConfig(gc);
        return f.getObject();
    }

    @Bean
    public PlatformTransactionManager txManager(DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean
    public NwfmJobRowProcessor rowProcessor() {
        return new NwfmJobRowProcessor();
    }

    @Bean
    public TNwfmJobServiceImpl jobService() {
        return new TNwfmJobServiceImpl();
    }

    @Bean
    public TNwfmDeviceRuleServiceImpl deviceRuleService() {
        return new TNwfmDeviceRuleServiceImpl();
    }
}
