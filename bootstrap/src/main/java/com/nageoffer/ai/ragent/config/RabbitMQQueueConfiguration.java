/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 队列声明配置
 * 通过声明 Queue Bean，Spring Boot 自动配置的 RabbitAdmin 会在启动时自动在 Broker 上创建这些队列
 */
@Configuration
public class RabbitMQQueueConfiguration {

    @Bean
    public Queue knowledgeBaseCleanupQueue(
            @Value("${rag.mq.cleanup-queue:knowledge-base-cleanup_queue${unique-name:}}") String name) {
        return QueueBuilder.durable(name).build();
    }

    @Bean
    public Queue knowledgeDocumentChunkQueue(
            @Value("${rag.mq.chunk-queue:knowledge-document-chunk_queue${unique-name:}}") String name) {
        return QueueBuilder.durable(name).build();
    }

    @Bean
    public Queue messageFeedbackQueue(
            @Value("${rag.mq.feedback-queue:message-feedback_queue${unique-name:}}") String name) {
        return QueueBuilder.durable(name).build();
    }
}
