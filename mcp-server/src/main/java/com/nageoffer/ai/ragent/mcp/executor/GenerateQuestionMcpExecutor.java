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

package com.nageoffer.ai.ragent.mcp.executor;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP工具：生成练习题/试题
 *
 * 由 Ragent 的意图识别模块自动从用户自然语言中提取参数，
 * 调用本工具返回结构化出题指令，再由 Chat LLM 完成题目内容生成。
 */
@Slf4j
@Component
public class GenerateQuestionMcpExecutor {

    private static final String TOOL_ID = "generate_question";

    private static final List<String> VALID_QUESTION_TYPES = List.of(
            "单选题", "多选题", "填空题", "判断题",
            "解答题", "压轴题", "阅读理解", "作文", "综合"
    );

    @Bean
    public McpServerFeatures.SyncToolSpecification generateQuestionToolSpecification() {
        return new McpServerFeatures.SyncToolSpecification(buildTool(),
                (exchange, request) -> handleCall(request));
    }

    private Tool buildTool() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("grade", Map.of(
                "type", "string",
                "description", "年级，如小学一年级、七年级、高一等"
        ));
        properties.put("subject", Map.of(
                "type", "string",
                "description", "学科，如数学、语文、英语、物理等"
        ));
        properties.put("textbook_version", Map.of(
                "type", "string",
                "description", "教材版本，如新教材、人教版、北师大版等，可选"
        ));
        properties.put("chapter", Map.of(
                "type", "string",
                "description", "章节或单元名称，如我上学了、一元一次方程，可选"
        ));
        properties.put("knowledge_points", Map.of(
                "type", "array",
                "description", "知识点列表，可选",
                "items", Map.of("type", "string")
        ));
        properties.put("question_count", Map.of(
                "type", "integer",
                "description", "题目数量，默认3，最多20",
                "default", 3
        ));
        properties.put("question_type", Map.of(
                "type", "string",
                "description", "题型：单选题、多选题、填空题、判断题、解答题、压轴题、阅读理解、作文、综合",
                "enum", VALID_QUESTION_TYPES,
                "default", "综合"
        ));
        properties.put("difficulty", Map.of(
                "type", "number",
                "description", "难度系数（0到1），0最简单，1最难，默认0.6",
                "default", 0.6
        ));

        JsonSchema inputSchema = new JsonSchema(
                "object", properties, List.of("grade", "subject"), null, null, null);

        return Tool.builder()
                .name(TOOL_ID)
                .description("根据年级、学科、章节和知识点生成练习题、试题或压轴题。支持指定题型（单选题/填空题/解答题等）、题目数量和难度系数。适用于教师出题、试卷组卷、课后练习等场景。")
                .inputSchema(inputSchema)
                .build();
    }

    private CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

            String grade = stringArg(args, "grade");
            String subject = stringArg(args, "subject");
            String textbookVersion = stringArg(args, "textbook_version");
            String chapter = stringArg(args, "chapter");
            Integer questionCount = intArg(args, "question_count");
            String questionType = stringArg(args, "question_type");
            Double difficulty = doubleArg(args, "difficulty");

            // --- 参数校验 ---
            if (grade == null || grade.isBlank()) {
                return errorResult("请提供年级信息，如：七年级、小学三年级等。");
            }
            if (subject == null || subject.isBlank()) {
                return errorResult("请提供学科信息，如：数学、语文、英语等。");
            }
            if (questionCount == null || questionCount <= 0) {
                questionCount = 3;
            }
            if (questionCount > 20) {
                questionCount = 20;
            }
            if (questionType == null || questionType.isBlank()) {
                questionType = "综合";
            }
            if (!VALID_QUESTION_TYPES.contains(questionType)) {
                return errorResult("无效的题型：" + questionType + "，支持的题型：" + String.join("、", VALID_QUESTION_TYPES));
            }
            if (difficulty == null) {
                difficulty = 0.6;
            }
            if (difficulty < 0 || difficulty > 1) {
                return errorResult("难度系数必须在 0 到 1 之间。");
            }

            // --- 构建结构化的出题指令，由 Chat LLM 完成内容生成 ---
            String result = buildGenerationInstruction(grade, subject, textbookVersion,
                    chapter, questionCount, questionType, difficulty, args);

            log.info("MCP 出题工具调用完成, grade={}, subject={}, chapter={}, type={}, count={}, elapsed={}ms",
                    grade, subject, chapter, questionType, questionCount,
                    System.currentTimeMillis() - startMs);
            return successResult(result);

        } catch (Exception e) {
            log.error("MCP 出题工具调用失败, elapsed={}ms",
                    System.currentTimeMillis() - startMs, e);
            return errorResult("出题失败: " + e.getMessage());
        }
    }

    /**
     * 构建结构化的出题指令文本。
     * 返回的文本将作为 {tool_results} 注入到 Chat LLM 的 Prompt 中，
     * 由 LLM 最终完成题目的生成。
     */
    private String buildGenerationInstruction(String grade, String subject,
                                              String textbookVersion, String chapter,
                                              int count, String type, double difficulty,
                                              Map<String, Object> args) {

        StringBuilder sb = new StringBuilder();
        sb.append("## 出题任务\n\n");

        sb.append("| 参数 | 值 |\n");
        sb.append("|------|----|\n");
        sb.append(String.format("| 年级 | %s |\n", grade));
        sb.append(String.format("| 学科 | %s |\n", subject));
        if (textbookVersion != null && !textbookVersion.isBlank()) {
            sb.append(String.format("| 教材版本 | %s |\n", textbookVersion));
        }
        if (chapter != null && !chapter.isBlank()) {
            sb.append(String.format("| 章节/单元 | %s |\n", chapter));
        }
        sb.append(String.format("| 题目数量 | %d |\n", count));
        sb.append(String.format("| 题型 | %s |\n", type));
        sb.append(String.format("| 难度 | %.1f |\n", difficulty));

        // 知识点列表
        Object kpObj = args.get("knowledge_points");
        if (kpObj instanceof List<?> kpList && !kpList.isEmpty()) {
            sb.append("| 知识点 | ").append(String.join("、", kpList.stream()
                    .map(Object::toString).toList())).append(" |\n");
        }

        sb.append("\n---\n\n");

        // 生成要求
        sb.append("请根据以上参数生成题目，要求如下：\n\n");

        sb.append(String.format("1. **数量**：生成 %d 道题目\n", count));
        sb.append(String.format("2. **题型**：%s\n", type));
        sb.append(String.format("3. **难度**：%.1f（1=最难，0=最简单），请控制在 %s 难度范围\n",
                difficulty, describeDifficulty(difficulty)));

        // 知识点约束
        if (kpObj instanceof List<?> kpList && !kpList.isEmpty()) {
            sb.append("4. **知识点范围**：题目必须覆盖以下知识点：")
                    .append(String.join("、", kpList.stream().map(Object::toString).toList()))
                    .append("\n");
        } else {
            sb.append(String.format("4. **知识范围**：题目内容限定在 %s %s 的课程大纲范围内\n",
                    grade, subject));
        }

        // 章节约束
        if (chapter != null && !chapter.isBlank()) {
            sb.append(String.format("5. **章节范围**：围绕「%s」章节出题\n", chapter));
            sb.append("6. **格式要求**：\n");
        } else {
            sb.append("5. **格式要求**：\n");
        }

        sb.append("   - 每道题以 ### 题号 开头，例如 ### 第1题\n");
        sb.append("   - 选择题需列出 A/B/C/D 四个选项\n");
        sb.append("   - 填空题需预留下划线或括号\n");
        sb.append("   - 每道题包含「答案」和「解析」两个部分\n");
        sb.append("   - 解析需包含解题思路和关键知识点\n");
        sb.append("   - 数学公式使用 LaTeX 格式（如 $x^2 + 2x + 1 = 0$）\n");
        sb.append("7. **禁止超纲**：不得使用超出该年级知识范围的任何概念\n");

        return sb.toString().trim();
    }

    private String describeDifficulty(double d) {
        if (d <= 0.2) return "非常基础";
        if (d <= 0.4) return "基础";
        if (d <= 0.6) return "中等";
        if (d <= 0.8) return "较难";
        return "高难度";
    }

    // --- 参数提取工具方法 ---

    private static String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
    }

    private static Integer intArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Double doubleArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static CallToolResult successResult(String text) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(text)))
                .isError(false)
                .build();
    }

    private static CallToolResult errorResult(String message) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(message)))
                .isError(true)
                .build();
    }
}
