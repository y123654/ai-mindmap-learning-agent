package com.example.aimindmap.agentruntime;

import org.springframework.stereotype.Component;

/**
 * 为单次问答创建证据台账（per-run，绝不跨请求复用）。
 *
 * <p>做成工厂而不是在 Streamer 里直接 {@code new}，是为了让 Streamer 单测能注入
 * 预置了证据的台账，从而覆盖「引用合法不追加提示」这类分支。
 */
@Component
public class EvidenceLedgerFactory {

    public EvidenceLedger create(Long runId) {
        return new EvidenceLedger(runId, EvidenceLedger.DEFAULT_MAX_ITEMS);
    }
}
