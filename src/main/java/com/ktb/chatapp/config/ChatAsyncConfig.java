package com.ktb.chatapp.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * chatMessage 처리 중 서로 독립적인 DB 조회(발신자 조회, 방 조회)를
 * 병렬로 실행하기 위한 전용 스레드풀.
 *
 * netty-socketio의 워커 스레드 풀과는 별개의 풀이다. 이 풀에서 두 조회를
 * 동시에 실행해도 최종적으로는 둘 다 끝날 때까지 호출한 워커 스레드가
 * join()으로 대기하므로, "기다리는 총 시간"이 (A 시간 + B 시간)에서
 * max(A 시간, B 시간)으로 줄어드는 것이 목적이다. 워커 스레드 자체를
 * 대기에서 완전히 풀어주는 것은 아니다(그러려면 리액티브 드라이버로의
 * 전면 전환이 필요함).
 *
 * 풀 크기는 Mongo 커넥션 풀을 넘어서지 않도록 보수적으로 잡는다
 * (조회 1건당 최대 2개의 동시 커넥션을 추가로 점유하게 되므로).
 */
@Configuration
public class ChatAsyncConfig {

    @Bean(name = "chatMessageLookupExecutor")
    Executor chatMessageLookupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("chat-msg-lookup-");
        executor.initialize();
        return executor;
    }
}
