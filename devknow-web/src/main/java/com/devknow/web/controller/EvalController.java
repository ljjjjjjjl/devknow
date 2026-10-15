package com.devknow.web.controller;

import com.devknow.common.api.ApiResponse;
import com.devknow.retrieval.eval.EvalReport;
import com.devknow.retrieval.eval.EvaluationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索评估入口。
 *
 * <p><b>这不是临时调试接口</b>，是长期资产：第 4 周用它出 baseline 和 rerank 的对照数字，
 * 第 7 周回归和压测还要复用。与第 3 周那个验收完就删的 {@code SearchProbeController}
 * 性质不同，不要删。
 *
 * <p>用 POST 而不是 GET：这个调用会真实消耗嵌入接口配额，不希望被任何中间层缓存。
 *
 * <p>没有参数：检索窗口固定由 {@code EvaluationService} 决定。窗口可变会让
 * MRR 之类的指标跨跑批不可比，所以宁可不让调用方调。
 */
@RestController
@RequestMapping("/api/admin/eval")
public class EvalController {

    private final EvaluationService evaluationService;

    public EvalController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping("/run")
    public ApiResponse<EvalReport> run() {
        return ApiResponse.ok(evaluationService.run());
    }
}
