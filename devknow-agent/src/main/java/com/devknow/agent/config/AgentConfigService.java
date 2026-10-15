package com.devknow.agent.config;

import com.devknow.common.api.ErrorCode;
import com.devknow.common.exception.BizException;
import com.devknow.domain.entity.AgentConfig;
import com.devknow.domain.mapper.AgentConfigMapper;
import org.springframework.stereotype.Service;

/**
 * 智能体配置加载：一行 agent_config 即一个 Agent，改配置不重启。
 */
@Service
public class AgentConfigService {

    private final AgentConfigMapper agentConfigMapper;

    public AgentConfigService(AgentConfigMapper agentConfigMapper) {
        this.agentConfigMapper = agentConfigMapper;
    }

    public AgentConfig getRequired(Long id) {
        if (id == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "agentConfigId 不能为空");
        }
        AgentConfig config = agentConfigMapper.selectById(id);
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND,
                    "智能体配置不存在或已禁用: id=" + id);
        }
        return config;
    }
}
