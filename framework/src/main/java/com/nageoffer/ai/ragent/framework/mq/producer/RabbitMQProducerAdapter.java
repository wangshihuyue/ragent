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

package com.nageoffer.ai.ragent.framework.mq.producer;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 基于 RabbitMQ 的消息生产者
 */
@Slf4j
@RequiredArgsConstructor
public class RabbitMQProducerAdapter implements MessageQueueProducer {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void send(String topic, String keys, String bizDesc, Object body) {
        keys = StrUtil.isEmpty(keys) ? UUID.randomUUID().toString() : keys;

        MessageWrapper<Object> wrapper = MessageWrapper.builder()
                .keys(keys)
                .body(body)
                .build();

        try {
            rabbitTemplate.convertAndSend(topic, wrapper);
        } catch (Throwable ex) {
            log.error("[生产者] {} - 消息发送失败，queue: {}, keys: {}", bizDesc, topic, keys, ex);
            throw ex;
        }

        log.info("[生产者] {} - 消息发送成功，Keys: {}", bizDesc, keys);
    }

    @Override
    public void sendInTransaction(String topic, String keys, String bizDesc, Object body,
                                  Consumer<Object> localTransaction) {
        keys = StrUtil.isEmpty(keys) ? UUID.randomUUID().toString() : keys;

        try {
            localTransaction.accept(body);
            send(topic, keys, bizDesc, body);
        } catch (Exception e) {
            log.error("[生产者] {} - 本地事务执行失败，消息不发送，queue: {}, keys: {}", bizDesc, topic, keys, e);
            throw e;
        }
    }
}
