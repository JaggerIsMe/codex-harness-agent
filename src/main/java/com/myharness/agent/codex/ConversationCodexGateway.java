package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.enums.ApprovalDecision;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Owns one app-server process per loaded conversation thread, including approval routing. */
@Component
public class ConversationCodexGateway implements CodexGateway {
    private final Supplier<CodexGateway> factory;
    private final Map<String,Entry> threads=new ConcurrentHashMap<>();
    private final Map<String,ApprovalRoute> approvals=new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper=Executors.newSingleThreadScheduledExecutor(r->{
        Thread thread=new Thread(r,"codex-runtime-reaper");thread.setDaemon(true);return thread;
    });
    @Autowired
    public ConversationCodexGateway(AgentProperties properties,ObjectMapper mapper) {
        this(()->new AppServerCodexAdapter(properties,mapper));
    }
    ConversationCodexGateway(Supplier<CodexGateway> factory) {
        this.factory=factory;
        reaper.scheduleWithFixedDelay(()->reapIdle(System.nanoTime(),TimeUnit.MINUTES.toNanos(2)),30,30,TimeUnit.SECONDS);
    }
    @Override public synchronized String startThread(CodexThreadOptions options) {
        CodexGateway gateway=factory.get();
        try {
            String id=gateway.startThread(options);
            if(threads.putIfAbsent(id,new Entry(gateway,options))!=null) throw new CodexException("Duplicate Codex thread identifier");
            return id;
        } catch(RuntimeException failure) {gateway.close();throw failure;}
    }
    @Override public synchronized void resumeThread(String id,CodexThreadOptions options) {
        Entry existing=threads.get(id);
        if(existing!=null && !existing.gateway.isAvailable()) {closeThread(id);existing=null;}
        if(existing!=null) {
            synchronized(existing) {
                if(!existing.options.getWorkspace().equals(options.getWorkspace())
                        || !Objects.equals(existing.options.getProjectId(),options.getProjectId()))
                    throw new CodexException("Loaded conversation runtime does not match its project or workspace");
                boolean sameConfiguration=existing.options.getExpertSkills().equals(options.getExpertSkills())
                        && existing.options.isIsolatedExpertRuntime()==options.isIsolatedExpertRuntime();
                if(!sameConfiguration) {
                    if(existing.active) throw new CodexException("Cannot update an active conversation runtime");
                    existing.gateway.resumeThread(id,options);
                    existing.options=options;
                }
                existing.touched=System.nanoTime();return;
            }
        }
        CodexGateway gateway=factory.get();
        try {gateway.resumeThread(id,options);threads.put(id,new Entry(gateway,options));}
        catch(RuntimeException failure) {gateway.close();throw failure;}
    }
    @Override public String startTurn(String id,CodexTurnInput input,CodexEventListener listener) {
        Entry entry=required(id);
        synchronized(entry) {
            if(threads.get(id)!=entry) throw new CodexException("Conversation runtime was reclaimed; retry the Turn");
            if(entry.active) throw new CodexException("Conversation already has an active Codex Turn");
            entry.active=true;
        }
        var terminal=new java.util.concurrent.atomic.AtomicBoolean();
        CodexEventListener forwarding=new CodexEventListener() {
            @Override public void onEvent(CodexEvent event) {if(!terminal.get()) listener.onEvent(event);}
            @Override public void onApproval(CodexApproval approval) {
                if(terminal.get()) return;
                String token=UUID.randomUUID().toString();
                approvals.put(token,new ApprovalRoute(entry,approval.getRequestId()));
                listener.onApproval(new CodexApproval(token,approval.getType(),approval.getDetails()));
            }
            @Override public void onCompleted(String turnId,String status,String reason) {
                if(terminal.compareAndSet(false,true)) {idle(entry);listener.onCompleted(turnId,status,reason);}
            }
        };
        try {return entry.gateway.startTurn(id,input,forwarding);}
        catch(RuntimeException failure) {if(terminal.compareAndSet(false,true)) idle(entry);throw failure;}
    }
    private void idle(Entry entry) {
        approvals.entrySet().removeIf(value->value.getValue().entry==entry);
        synchronized(entry) {entry.touched=System.nanoTime();entry.active=false;}
    }
    @Override public void interruptTurn(String threadId,String turnId) {required(threadId).gateway.interruptTurn(threadId,turnId);}
    @Override public void resolveApproval(String requestId,ApprovalDecision decision) {
        ApprovalRoute route=approvals.remove(requestId);
        if(route==null) throw new CodexException("Unknown or expired conversation approval");
        route.entry.gateway.resolveApproval(route.nativeId,decision);
    }
    @Override public synchronized void closeThread(String id) {
        Entry entry=threads.remove(id);
        if(entry!=null) {approvals.entrySet().removeIf(value->value.getValue().entry==entry);entry.gateway.close();}
    }
    synchronized void reapIdle(long now,long timeout) {
        for(var item:List.copyOf(threads.entrySet())) {
            Entry entry=item.getValue();boolean removed=false;
            synchronized(entry) {
                if(!entry.active && now-entry.touched>=timeout) removed=threads.remove(item.getKey(),entry);
            }
            if(removed) {approvals.entrySet().removeIf(value->value.getValue().entry==entry);entry.gateway.close();}
        }
    }
    private Entry required(String id) {
        Entry entry=threads.get(id);if(entry==null) throw new CodexException("Conversation runtime is not loaded");return entry;
    }
    @Override @PreDestroy public synchronized void close() {
        reaper.shutdownNow();for(String id:List.copyOf(threads.keySet())) closeThread(id);
    }
    private static final class Entry {
        final CodexGateway gateway;CodexThreadOptions options;boolean active;long touched=System.nanoTime();
        Entry(CodexGateway gateway,CodexThreadOptions options) {this.gateway=gateway;this.options=options;}
    }
    private record ApprovalRoute(Entry entry,String nativeId) { }
}
