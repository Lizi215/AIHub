package com.aihub.ai.agent.tool;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.result.Result;

/**
 * 把下游的 {@link Result} 拆成数据或异常。
 *
 * <p>本项目用「HTTP 200 + 非 0 code」表达业务失败，必须显式判断，
 * 否则会把失败当成功继续往下走。
 *
 * <p>错误码不透出：下游的 2xxxx / 3xxxx 统一转换为 AI 域的
 * {@link ErrorCode#AGENT_TOOL_FAILED}（docs/BACKEND.md §3）。
 */
final class RemoteResults {

    private RemoteResults() {
    }

    static <T> T unwrap(Result<T> result, String action) {
        if (result == null) {
            throw new BizException(ErrorCode.AGENT_TOOL_FAILED, action + " failed: empty response from downstream");
        }
        if (result.code() != ErrorCode.SUCCESS.getCode()) {
            throw new BizException(ErrorCode.AGENT_TOOL_FAILED,
                    action + " failed: " + result.message());
        }
        if (result.data() == null) {
            throw new BizException(ErrorCode.AGENT_TOOL_FAILED, action + " failed: downstream returned no data");
        }
        return result.data();
    }
}
