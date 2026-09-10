package com.myharness.agent.workspace;

import com.myharness.agent.command.AgentOperationException;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Leases belong to executions, not threads: Turn callbacks may finish on another executor. */
@Component
public class WorkspaceExecutionCoordinator {
    private final Map<Path,State> states=new HashMap<>();
    private static final class State {int readers;String mutation;boolean unknown;}
    public synchronized Lease enterTurn(Path root) {return acquire(root,null);}
    public synchronized Lease enterRead(Path root) {return acquire(root,null);}
    public synchronized Lease enterMutation(Path root,String operationId) {return acquire(root,operationId);}
    private Lease acquire(Path root,String operationId) {
        Path key=root.toAbsolutePath().normalize();State state=states.computeIfAbsent(key,ignored -> new State());
        if(state.mutation!=null || operationId!=null && state.readers>0)
            throw new AgentOperationException("WORKSPACE_BUSY","工作区有尚未结束的执行或待核实的文件操作");
        if(operationId==null) state.readers++;else state.mutation=operationId;
        return new Lease(this,key,operationId);
    }
    public synchronized void holdUnknown(Path root,String operationId) {
        State state=states.computeIfAbsent(root.toAbsolutePath().normalize(),ignored -> new State());
        state.mutation=operationId;state.unknown=true;
    }
    public synchronized void resolved(Path root,String operationId) {
        State state=states.get(root.toAbsolutePath().normalize());
        if(state!=null && operationId.equals(state.mutation)) {state.mutation=null;state.unknown=false;}
    }
    private synchronized void release(Path root,String operationId) {
        State state=states.get(root);if(state==null)return;
        if(operationId==null) state.readers--;else if(!state.unknown && operationId.equals(state.mutation)) state.mutation=null;
        if(state.readers==0 && state.mutation==null) states.remove(root);
    }
    public static final class Lease implements AutoCloseable {
        private final WorkspaceExecutionCoordinator owner;private final Path root;private final String operation;
        private final AtomicBoolean closed=new AtomicBoolean();
        Lease(WorkspaceExecutionCoordinator owner,Path root,String operation){this.owner=owner;this.root=root;this.operation=operation;}
        @Override public void close(){if(closed.compareAndSet(false,true))owner.release(root,operation);}
    }
}
