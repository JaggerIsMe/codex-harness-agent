package com.myharness.agent.workspace;

import com.myharness.agent.command.AgentOperationException;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceExecutionCoordinatorTest {
    @Test void turnsOnSameWorkspaceAreExclusiveAndDifferentWorkspacesCanRun() throws Exception {
        var coordinator=new WorkspaceExecutionCoordinator();Path root=Path.of("target","project-a");
        var first=coordinator.enterTurn(root);
        try(var other=coordinator.enterTurn(Path.of("target","project-b"))) {
            assertEquals("WORKSPACE_BUSY",assertThrows(AgentOperationException.class,()->coordinator.enterTurn(root)).getErrorCode());
            assertThrows(AgentOperationException.class,()->coordinator.enterMutation(root,"edit"));
        }
        // Completion callback may execute on a different Java thread.
        try(var executor=Executors.newSingleThreadExecutor()){executor.submit(first::close).get(5,TimeUnit.SECONDS);}
        first.close();
        try(var next=coordinator.enterTurn(root)){assertNotNull(next);}
    }
    @Test void simultaneousAdmissionsHaveExactlyOneWinner() throws Exception {
        var coordinator=new WorkspaceExecutionCoordinator();var barrier=new CyclicBarrier(2);var release=new CountDownLatch(1);
        var admitted=new java.util.concurrent.atomic.AtomicInteger();var checked=new CountDownLatch(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<2;i++)futures.add(executor.submit(()->{
                WorkspaceExecutionCoordinator.Lease lease=null;
                try {barrier.await();lease=coordinator.enterTurn(Path.of("target","same"));admitted.incrementAndGet();}
                catch(AgentOperationException expected){assertEquals("WORKSPACE_BUSY",expected.getErrorCode());}
                catch(Exception e){throw new RuntimeException(e);}
                finally {checked.countDown();}
                try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                finally {if(lease!=null)lease.close();}
            }));
            assertTrue(checked.await(5,TimeUnit.SECONDS));assertEquals(1,admitted.get());release.countDown();
            for(var future:futures)future.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();}
    }
}
