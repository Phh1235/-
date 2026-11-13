package com.phh.procure;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 采购比价与供应商尽调 Agent —— Java 编排侧入口。
 *
 * <p>职责边界：Java 持有状态机、事务、幂等、鉴权、限流、补偿；
 * Python 是无状态执行器，只做解析/检索/抓取/归一化/打分/报告。</p>
 */
@EnableAsync
@EnableScheduling
@EnableFeignClients
@MapperScan("com.phh.procure.mapper")
@SpringBootApplication
public class ProcureAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcureAgentApplication.class, args);
    }
}
