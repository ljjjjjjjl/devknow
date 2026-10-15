package com.devknow.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 流式对话的执行器。
 *
 * <p>SSE 必须"先返回 emitter，再在别的线程里写" —— 一个连接会全程占住一个线程
 * （等模型、等工具）。用虚拟线程让"一个连接一个线程"这种最好懂的写法在并发下依然成立，
 * 不需要为了省线程去写响应式。
 *
 * <p>与采集用的 {@code JobWorker} 执行器分开：采集任务动辄几分钟且要限制并发，
 * 对话是秒级交互且并发量由在线用户决定，两类负载的隔离需求不同，
 * 混在一个池子里会互相拖累。
 */
@Configuration
public class AgentStreamConfig {

    @Bean(destroyMethod = "close")
    public ExecutorService agentStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
