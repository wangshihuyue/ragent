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

package com.nageoffer.ai.ragent.infra.chat;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BaiLianChatClientTest {

    @Test
    void chatUsesDashScopeAgentEndpointWhenCandidateProtocolIsAgent() {
        AtomicReference<Request> capturedRequest = new AtomicReference<>();
        BaiLianChatClient client = new BaiLianChatClient();
        ReflectionTestUtils.setField(client, "syncHttpClient", capturingClient(capturedRequest));

        String content = client.chat(chatRequest(), agentTarget());

        assertThat(content).isEqualTo("agent response");
        Request request = capturedRequest.get();
        assertThat(request.url().encodedPath()).isEqualTo("/api/v1/apps/474342a986cb4e99b3fc44fe744d2639/completion");
        assertThat(request.header("Authorization")).isEqualTo("Bearer test-key");
        assertThat(request.header("X-DashScope-SSE")).isNull();
    }

    @Test
    void agentStreamParserIgnoresSseControlLines() {
        BaiLianChatClient client = new BaiLianChatClient();

        Object idEvent = ReflectionTestUtils.invokeMethod(client, "parseAgentStreamLine", "id:1");
        Object eventName = ReflectionTestUtils.invokeMethod(client, "parseAgentStreamLine", "event:result");
        Object comment = ReflectionTestUtils.invokeMethod(client, "parseAgentStreamLine", ":HTTP_STATUS/200");

        assertThat(idEvent).isNotNull();
        assertThat(eventName).isNotNull();
        assertThat(comment).isNotNull();
    }

    @Test
    void agentStreamParserDoesNotCompleteOnStringNullFinishReason() {
        BaiLianChatClient client = new BaiLianChatClient();

        Object event = ReflectionTestUtils.invokeMethod(
                client,
                "parseAgentStreamLine",
                "data:{\"output\":{\"text\":\"\",\"finish_reason\":\"null\"},\"request_id\":\"req-1\"}"
        );

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(event, "completed")).isFalse();
    }

    private OkHttpClient capturingClient(AtomicReference<Request> capturedRequest) {
        return new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    capturedRequest.set(chain.request());
                    return response(chain.request(), "{\"output\":{\"text\":\"agent response\"},\"request_id\":\"req-1\"}");
                })
                .build();
    }

    private Response response(Request request, String body) throws IOException {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, HttpMediaTypes.JSON))
                .build();
    }

    private ChatRequest chatRequest() {
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.user("hello")))
                .build();
    }

    private ModelTarget agentTarget() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("https://dashscope.aliyuncs.com");
        provider.setApiKey("test-key");
        provider.setEndpoints(Map.of("chat", "/compatible-mode/v1/chat/completions"));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("bailian-ziyoubiaoshu-agent");
        candidate.setProvider("bailian");
        candidate.setModel("474342a986cb4e99b3fc44fe744d2639");
        candidate.setProtocol("agent");

        return new ModelTarget(candidate.getId(), candidate, provider);
    }
}
