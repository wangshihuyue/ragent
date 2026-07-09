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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.framework.trace.RagStreamTraceSupport.StreamSpan;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class BaiLianChatClient extends AbstractOpenAIStyleChatClient {

    private static final String AGENT_PROTOCOL = "agent";
    private static final String AGENT_COMPLETION_PATH = "/api/v1/apps/%s/completion";
    private static final String SSE_HEADER = "X-DashScope-SSE";
    private static final String SSE_HEADER_VALUE = "enable";

    @Override
    public String provider() {
        return ModelProvider.BAI_LIAN.getId();
    }

    @Override
    @RagTraceNode(name = "bailian-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        if (isAgent(target)) {
            return doAgentChat(request, target);
        }
        return doChat(request, target);
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        if (isAgent(target)) {
            return doAgentStreamChat(request, callback, target);
        }
        return doStreamChat(request, callback, target);
    }

    private String doAgentChat(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProviderAndApiKey(target);
        Request requestHttp = newAgentRequest(provider, target, false)
                .post(RequestBody.create(buildAgentRequestBody(request, false).toString(), HttpMediaTypes.JSON))
                .build();

        JsonObject respJson;
        try (Response response = syncHttpClient.newCall(requestHttp).execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} agent 同步请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " agent 同步请求失败: HTTP " + response.code() + " - " + body,
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            respJson = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(
                    provider() + " agent 同步请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        return extractAgentText(respJson);
    }

    private StreamCancellationHandle doAgentStreamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = requireProviderAndApiKey(target);
        Request streamRequest = newAgentRequest(provider, target, true)
                .post(RequestBody.create(buildAgentRequestBody(request, true).toString(), HttpMediaTypes.JSON))
                .addHeader("Accept", "text/event-stream")
                .build();

        Call call = streamingHttpClient.newCall(streamRequest);
        StreamSpan span = streamTraceSupport.beginStreamNode(provider() + "-agent-stream-chat", "LLM_PROVIDER");
        StreamSpanCallback wrappedCallback;
        try {
            wrappedCallback = new StreamSpanCallback(callback, span);
            StreamCancellationHandle inner = StreamAsyncExecutor.submit(
                    modelStreamExecutor,
                    call,
                    wrappedCallback,
                    cancelled -> doAgentStream(call, wrappedCallback, cancelled)
            );
            return () -> {
                try {
                    inner.cancel();
                } finally {
                    wrappedCallback.onCancel();
                }
            };
        } finally {
            span.detach();
        }
    }

    private void doAgentStream(Call call, StreamCallback callback, AtomicBoolean cancelled) {
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                throw new ModelClientException(
                        provider() + " agent 流式请求失败: HTTP " + response.code() + " - " + body,
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ModelClientException(provider() + " agent 流式响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
            }

            BufferedSource source = body.source();
            boolean completed = false;
            while (!cancelled.get()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                try {
                    AgentStreamEvent event = parseAgentStreamLine(line);
                    if (event.hasContent()) {
                        callback.onContent(event.content());
                    }
                    if (event.completed()) {
                        callback.onComplete();
                        completed = true;
                        break;
                    }
                } catch (Exception parseEx) {
                    log.warn("{} agent 流式响应解析失败: line={}", provider(), line, parseEx);
                }
            }
            if (cancelled.get()) {
                log.info("{} agent 流式响应已被取消", provider());
                return;
            }
            if (!completed) {
                throw new ModelClientException(provider() + " agent 流式响应异常结束", ModelClientErrorType.INVALID_RESPONSE, null);
            }
        } catch (Exception e) {
            if (!cancelled.get()) {
                callback.onError(e);
            } else {
                log.info("{} agent 流式响应取消期间产生异常（可忽略）: {}", provider(), e.getMessage());
            }
        }
    }

    private AIModelProperties.ProviderConfig requireProviderAndApiKey(ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        HttpResponseHelper.requireApiKey(provider, provider());
        return provider;
    }

    private boolean isAgent(ModelTarget target) {
        return target != null
                && target.candidate() != null
                && AGENT_PROTOCOL.equalsIgnoreCase(target.candidate().getProtocol());
    }

    private Request.Builder newAgentRequest(AIModelProperties.ProviderConfig provider, ModelTarget target, boolean stream) {
        Request.Builder builder = new Request.Builder()
                .url(resolveAgentUrl(provider, target))
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .addHeader("X-DashScope-OpenAPISource", "CloudSDK");
        if (stream) {
            builder.addHeader(SSE_HEADER, SSE_HEADER_VALUE);
        }
        return builder;
    }

    private String resolveAgentUrl(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        String appId = HttpResponseHelper.requireModel(target, provider());
        String baseUrl = provider.getUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(provider() + " Provider baseUrl is missing");
        }
        String path = String.format(AGENT_COMPLETION_PATH, appId);
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + path;
        }
        return baseUrl + path;
    }

    private JsonObject buildAgentRequestBody(ChatRequest request, boolean stream) {
        JsonObject body = new JsonObject();
        JsonObject input = new JsonObject();
        if (hasSingleUserMessage(request)) {
            input.addProperty("prompt", request.getMessages().get(0).getContent());
        } else {
            input.add("messages", buildAgentMessages(request));
        }
        body.add("input", input);

        JsonObject parameters = new JsonObject();
        if (stream) {
            parameters.addProperty("incremental_output", true);
        }
        body.add("parameters", parameters);
        return body;
    }

    private boolean hasSingleUserMessage(ChatRequest request) {
        List<ChatMessage> messages = request.getMessages();
        return messages != null
                && messages.size() == 1
                && ChatMessage.Role.USER.equals(messages.get(0).getRole());
    }

    private JsonArray buildAgentMessages(ChatRequest request) {
        JsonArray messages = new JsonArray();
        if (request.getMessages() == null) {
            return messages;
        }
        request.getMessages().forEach(message -> {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.getRole().name().toLowerCase());
            item.addProperty("content", message.getContent());
            messages.add(item);
        });
        return messages;
    }

    private String extractAgentText(JsonObject respJson) {
        if (respJson == null || !respJson.has("output") || !respJson.get("output").isJsonObject()) {
            throw new ModelClientException(provider() + " agent 响应缺少 output", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject output = respJson.getAsJsonObject("output");
        JsonElement text = output.get("text");
        if (text == null || text.isJsonNull()) {
            throw new ModelClientException(provider() + " agent 响应缺少 output.text", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return text.getAsString();
    }

    private AgentStreamEvent parseAgentStreamLine(String line) {
        String payload = line.trim();
        if (payload.startsWith("data:")) {
            payload = payload.substring("data:".length()).trim();
        } else if (!payload.startsWith("{")) {
            return AgentStreamEvent.empty();
        }
        if ("[DONE]".equalsIgnoreCase(payload)) {
            return AgentStreamEvent.done();
        }
        if (payload.isEmpty()) {
            return AgentStreamEvent.empty();
        }

        JsonObject obj = gson.fromJson(payload, JsonObject.class);
        if (obj != null && obj.has("code") && !obj.get("code").isJsonNull()) {
            throw new ModelClientException(
                    provider() + " agent 流式请求失败: " + payload,
                    ModelClientErrorType.INVALID_RESPONSE,
                    null
            );
        }
        String text = null;
        boolean completed = false;
        if (obj != null && obj.has("output") && obj.get("output").isJsonObject()) {
            JsonObject output = obj.getAsJsonObject("output");
            JsonElement textElement = output.get("text");
            if (textElement != null && !textElement.isJsonNull()) {
                text = textElement.getAsString();
            }
            JsonElement finishReason = output.get("finish_reason");
            completed = isFinished(finishReason);
        }
        return new AgentStreamEvent(text, completed);
    }

    private boolean isFinished(JsonElement finishReason) {
        if (finishReason == null || finishReason.isJsonNull()) {
            return false;
        }
        String value = finishReason.getAsString();
        return value != null && !value.isBlank() && !"null".equalsIgnoreCase(value);
    }

    private record AgentStreamEvent(String content, boolean completed) {

        static AgentStreamEvent empty() {
            return new AgentStreamEvent(null, false);
        }

        static AgentStreamEvent done() {
            return new AgentStreamEvent(null, true);
        }

        boolean hasContent() {
            return content != null && !content.isEmpty();
        }
    }
}
