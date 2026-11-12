package com.example.aimindmap.agenttracing;

import com.example.aimindmap.auth.AuthUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P0-E1：Agent run 观测查询接口（方案 §4 P0-5 / §6 接口表）。
 *
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>用途</th></tr>
 *   <tr><td>GET</td><td>{@code /api/agent/runs/{runId}}</td>
 *       <td>run + steps + evidence + citations + tool_calls</td></tr>
 *   <tr><td>GET</td><td>{@code /api/agent/runs?sessionId=&limit=}</td>
 *       <td>当前用户在该会话下的 run 列表（时间倒序，默认 20，上限 50）</td></tr>
 * </table>
 *
 * <p>两个接口都要求登录（Spring Security 的 {@code anyRequest().authenticated()}），
 * 且都做归属校验：别人的 run → 404（不泄露存在性）；列表按 userId 过滤，只返回本人。
 * 入参 {@code runId}/{@code sessionId} 均为对外混淆字符串（修订第 44 条）。</p>
 */
@RestController
@RequestMapping("/api/agent/runs")
public class AgentRunController {

    private final AgentRunQueryService queryService;

    public AgentRunController(AgentRunQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{runId}")
    public AgentRunDtos.RunDetailResponse getRun(@AuthenticationPrincipal AuthUser user,
                                                 @PathVariable String runId) {
        return queryService.getRun(user.userId(), runId);
    }

    /**
     * @param limit 缺省 20；超过 50 按 50 返回；非正数按默认值处理（见
     *              {@link AgentRunQueryService#normalizeLimit(Integer)}）
     */
    @GetMapping
    public List<AgentRunDtos.RunSummaryResponse> listRuns(
            @AuthenticationPrincipal AuthUser user,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) Integer limit) {
        return queryService.listRuns(user.userId(), sessionId, limit);
    }
}
