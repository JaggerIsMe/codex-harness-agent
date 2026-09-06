package com.myharness.agent.codex;

import com.myharness.agent.attachment.AttachmentPreparation;
import com.myharness.agent.command.AgentOperationException;
import com.myharness.agent.entity.dto.InstallSkillCommandDTO;
import com.myharness.agent.entity.dto.StartTurnCommandDTO;
import com.myharness.agent.skill.SkillInstallationService;
import com.myharness.agent.workspace.WorkspaceRegistry;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class ExpertSkillPreparation {
    private final SkillInstallationService skills;
    private final WorkspaceRegistry workspaces;
    public ExpertSkillPreparation(SkillInstallationService skills, WorkspaceRegistry workspaces) {this.skills=skills; this.workspaces=workspaces;}
    public synchronized List<CodexSkillInput> prepare(StartTurnCommandDTO turn, AttachmentPreparation cancellation) {
        var runtime=turn.getExpertRuntime();
        if(runtime==null) return List.of();
        if((runtime.getSchemaVersion()!=2 && runtime.getSchemaVersion()!=3 && runtime.getSchemaVersion()!=4) || runtime.getProjectRevision()==null || runtime.getSkills()==null || runtime.getSkills().size()>30
                || (runtime.getSchemaVersion()==4 && (runtime.getMcpServers()==null || runtime.getMcpServers().size()>20))
                || runtime.getRuntimeKey()==null || !runtime.getRuntimeKey().matches("[0-9a-f]{64}"))
            throw new AgentOperationException("EXPERT_CONFIG_INVALID","专家运行配置无效，请同步升级服务端和 Agent 到会话隔离协议 V4");
        if(runtime.getProjectRevision()<0 || (runtime.getExpertVersionId()!=null && (runtime.getSystemPrompt()==null || runtime.getSystemPrompt().isBlank()))
                || (runtime.getExpertVersionId()==null && (runtime.getSystemPrompt()!=null || !runtime.getSkills().isEmpty() || !runtime.getMcpServers().isEmpty()))
                || (runtime.getSchemaVersion()>=3 && runtime.getExpertVersionId()!=null && runtime.getExpertId()==null))
            throw new AgentOperationException("EXPERT_CONFIG_INVALID","专家版本与指令不一致");
        if(turn.getTurnId()==null) throw new AgentOperationException("EXPERT_CONFIG_INVALID","缺少专家运行标识");
        if(turn.getConversationId()==null || !turn.getConversationId().matches("[A-Za-z0-9_-]{1,64}"))
            throw new AgentOperationException("EXPERT_CONFIG_INVALID","会话运行标识无效");
        List<CodexSkillInput> result=new ArrayList<>();
        for(var skill:runtime.getSkills()) {
            cancellation.check();
            if(skill.getSkillId()==null || skill.getVersionId()==null || skill.getName()==null)
                throw new AgentOperationException("EXPERT_CONFIG_INVALID","专家 Skill 配置无效");
            InstallSkillCommandDTO command=new InstallSkillCommandDTO();
            command.setSkillId(skill.getSkillId()+"-"+skill.getVersionId()); command.setVersion(skill.getVersion());
            command.setScopeType("EXPERT"); command.setWorkspaceName(turn.getWorkspaceName());
            command.setDownloadUrl(skill.getDownloadUrl()); command.setSha256(skill.getSha256());
            workspaces.resolve(turn.getWorkspaceName(),".harness/expert-skills/harness-"+command.getSkillId());
            skills.install(command,cancellation); cancellation.check();
            var path=workspaces.resolve(turn.getWorkspaceName(),".harness/expert-skills/harness-"+command.getSkillId()+"/SKILL.md");
            result.add(new CodexSkillInput(skill.getName(),path.toString()));
        }
        return ExpertSkillActivation.sync(workspaces,turn.getWorkspaceName(),
                ".harness/expert-runtimes/"+turn.getConversationId()+"/"+runtime.getRuntimeKey()+"/skills",result,cancellation);
    }
}
