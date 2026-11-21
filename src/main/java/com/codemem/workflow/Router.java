package com.codemem.workflow;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 路由：规则优先，Mock 模式也完全可用。
 *
 * <p>策略偏保守：只有出现明确的“规划 / 诊断 / 审查 / 跨会话指代”信号时才进入双角色，
 * 单一知识点解释优先走单角色，避免无谓的延迟与成本。</p>
 */
@Component
public class Router {

    private static final List<String> STRICT_SIGNALS = List.of(
            "严格审查", "认真审查", "仔细审查", "深度审查", "代码审查", "帮我审查"
    );

    private static final List<String> CROSS_SESSION_SIGNALS = List.of(
            "上次那个", "上次说", "上次提", "上次讲", "继续上次", "之前说", "之前提", "之前提到",
            "之前讲", "前面说", "接上次", "接着上次", "接着之前", "刚才说"
    );

    private static final List<String> DUAL_SIGNALS = List.of(
            "计划", "路线", "怎么学", "学习安排", "诊断", "为什么错", "哪里错", "错在哪",
            "代码有没有问题", "代码有问题吗", "复盘", "两周", "一个月", "这周", "下周",
            "安排一下", "优先学", "从哪开始",
            "卡在", "卡点", "卡住", "不会做", "没懂", "搞不懂", "不明白", "诊断一下"
    );

    private static final List<String> SINGLE_SIGNALS = List.of(
            "是什么", "举个例子", "解释一下", "怎么写", "什么意思", "有什么区别", "怎么实现", "定义"
    );

    public RoutingDecision route(String message, boolean hasHistory) {
        if (message == null || message.isBlank()) {
            return new RoutingDecision(WorkflowMode.SINGLE, "空问题按单角色处理");
        }
        String text = message.trim();

        String hit = firstHit(text, STRICT_SIGNALS);
        if (hit != null) {
            return new RoutingDecision(WorkflowMode.DUAL, "用户要求审查：" + hit);
        }

        hit = firstHit(text, CROSS_SESSION_SIGNALS);
        if (hit != null) {
            return new RoutingDecision(WorkflowMode.DUAL, "跨会话指代：" + hit);
        }

        hit = firstHit(text, DUAL_SIGNALS);
        if (hit != null) {
            return new RoutingDecision(WorkflowMode.DUAL, "规划或诊断信号：" + hit);
        }

        hit = firstHit(text, SINGLE_SIGNALS);
        if (hit != null) {
            return new RoutingDecision(WorkflowMode.SINGLE, "单一知识点解释：" + hit);
        }

        return new RoutingDecision(
                WorkflowMode.SINGLE,
                hasHistory ? "无明确双角色信号，按单角色处理（已有历史）" : "无明确双角色信号，按单角色处理"
        );
    }

    private String firstHit(String text, List<String> signals) {
        for (String signal : signals) {
            if (text.contains(signal)) {
                return signal;
            }
        }
        return null;
    }
}