package com.myharness.agent.attachment;

import com.myharness.agent.command.AgentOperationException;
import java.net.HttpURLConnection;

/** One Turn's cancellation and deadline; closes a blocked network read when canceled. */
public final class AttachmentPreparation {
    private volatile boolean canceled;
    private HttpURLConnection connection;
    private final long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
    public synchronized void cancel(){canceled=true; if(connection!=null) connection.disconnect();}
    public boolean canceled(){return canceled;}
    public void check(){
        if(canceled || Thread.currentThread().isInterrupted()) throw new AgentOperationException("ATTACHMENT_CANCELED","附件准备已取消");
        if(System.nanoTime()>deadline) throw new AgentOperationException("ATTACHMENT_TIMEOUT","附件准备超时");
    }
    public synchronized void connection(HttpURLConnection value){check(); connection=value;}
    public synchronized void clear(){connection=null;}
}
