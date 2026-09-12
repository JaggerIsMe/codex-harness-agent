package com.myharness.agent.codex;

import com.myharness.agent.workspace.WorkspaceToolFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict context patches: preflight every file before any mutation; lock opened objects until completion. */
final class WorkspacePatchTool {
    static final String NAME="harness_apply_patch";
    private static final int MAX_FILE=1024*1024;
    private record Change(String kind,String path,String destination,List<String> body){ }
    private record Prepared(Change change,byte[] bytes){ }
    private WorkspacePatchTool(){ }
    static String apply(Path workspace,String patch) throws IOException {
        List<Change> changes=parse(patch);var prepared=new ArrayList<Prepared>();var completed=new ArrayList<String>();
        try(var files=new WorkspaceToolFiles(workspace,true)) {
            for(var change:changes) {
                byte[] bytes=null;
                if(change.kind.equals("Add")) {
                    files.requireAbsent(change.path);
                    bytes=(String.join("\n",change.body.stream().map(line->line.substring(1)).toList())+"\n").getBytes(StandardCharsets.UTF_8);
                } else {
                    String before=decode(files.read(change.path,MAX_FILE));
                    if(change.kind.equals("Update"))bytes=update(before,change.body).getBytes(StandardCharsets.UTF_8);
                }
                if(change.destination!=null)files.requireAbsent(change.destination);
                if(bytes!=null&&bytes.length>MAX_FILE)throw new IOException("补丁结果超过单文件 1 MiB 限制");
                prepared.add(new Prepared(change,bytes));
            }
            if(Thread.currentThread().isInterrupted())throw new IOException("补丁已取消，尚未修改文件");
            // Once the preflight finishes, complete the small bounded commit without interruption mid-file.
            try {
                for(var value:prepared) {
                    Change change=value.change;
                    switch(change.kind) {
                        case "Add" -> files.create(change.path,value.bytes);
                        case "Delete" -> files.remove(change.path);
                        case "Update" -> {
                            files.replace(change.path,value.bytes);
                            if(change.destination!=null)files.move(change.path,change.destination);
                        }
                        default -> throw new IOException("未知补丁操作");
                    }
                    completed.add(change.kind+" "+change.path+(change.destination==null?"":" -> "+change.destination));
                }
            }catch(IOException failure){throw new IOException("补丁写入失败；可能已部分修改，请重新读取核实。已完成："+completed+"；"+failure.getMessage(),failure);}
        }
        return String.join("\n",completed);
    }
    private static String decode(byte[] bytes) throws IOException {
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(text.indexOf('\0')>=0)throw new IOException("补丁仅支持 UTF-8 文本文件");return text;
        }catch(CharacterCodingException failure){throw new IOException("补丁仅支持 UTF-8 文本文件",failure);}
    }
    private static List<Change> parse(String patch) throws IOException {
        if(patch==null||patch.isBlank()||patch.length()>128*1024)throw new IOException("补丁必须为 1–131072 个字符");
        var lines=new ArrayList<>(Arrays.asList(patch.replace("\r\n","\n").split("\n",-1)));
        if(lines.getLast().isEmpty())lines.removeLast();
        if(lines.size()<3||!lines.getFirst().equals("*** Begin Patch")||!lines.getLast().equals("*** End Patch"))throw new IOException("补丁缺少 Begin/End Patch 标记");
        var changes=new ArrayList<Change>();Set<String> paths=new HashSet<>();int cursor=1;
        while(cursor<lines.size()-1) {
            String header=lines.get(cursor++),kind,path;
            if(header.startsWith("*** Add File: "))kind="Add";
            else if(header.startsWith("*** Update File: "))kind="Update";
            else if(header.startsWith("*** Delete File: "))kind="Delete";
            else throw new IOException("不支持的补丁标记："+header);
            path=header.substring(header.indexOf(": ")+2);claim(paths,path);
            String destination=null;
            if(kind.equals("Update")&&cursor<lines.size()-1&&lines.get(cursor).startsWith("*** Move to: ")) {
                destination=lines.get(cursor++).substring("*** Move to: ".length());claim(paths,destination);
            }
            var body=new ArrayList<String>();
            while(cursor<lines.size()-1&&(!lines.get(cursor).startsWith("*** ")||lines.get(cursor).equals("*** End of File")))body.add(lines.get(cursor++));
            if(kind.equals("Add")&&(body.isEmpty()||body.stream().anyMatch(line->!line.startsWith("+"))))throw new IOException("新增文件的每行必须以 + 开头");
            if(kind.equals("Delete")&&!body.isEmpty())throw new IOException("删除文件不能包含补丁内容");
            if(kind.equals("Update")&&body.isEmpty()&&destination==null)throw new IOException("更新文件缺少补丁内容");
            changes.add(new Change(kind,path,destination,body));
            if(changes.size()>32)throw new IOException("单次补丁最多操作 32 个文件");
        }
        if(changes.isEmpty())throw new IOException("补丁没有文件操作");return changes;
    }
    private static void claim(Set<String> paths,String path) throws IOException {
        com.myharness.agent.workspace.WorkspaceFileService.validateRelative(path);
        if(path.isEmpty()||!paths.add(path.toLowerCase(Locale.ROOT)))throw new IOException("补丁路径为空或重复");
    }
    static String update(String before,List<String> patch) throws IOException {
        boolean crlf=before.contains("\r\n"),newline=before.endsWith("\n");
        var original=new ArrayList<>(Arrays.asList(before.replace("\r\n","\n").split("\n",-1)));
        if(newline||before.isEmpty())original.removeLast();
        var result=new ArrayList<String>();int sourceCursor=0,cursor=0;
        while(cursor<patch.size()) {
            String header=patch.get(cursor++);
            if(!header.equals("@@")&&!header.startsWith("@@ "))throw new IOException("补丁段必须以 @@ 开头");
            int searchStart=sourceCursor;
            if(header.startsWith("@@ ")) {
                String anchor=header.substring(3);int anchorIndex=unique(original,List.of(anchor),sourceCursor,false);
                searchStart=anchorIndex+1;
            }
            var oldLines=new ArrayList<String>();var newLines=new ArrayList<String>();boolean end=false;
            while(cursor<patch.size()&&!patch.get(cursor).startsWith("@@")) {
                String line=patch.get(cursor++);
                if(line.equals("*** End of File")) {end=true;if(cursor!=patch.size())throw new IOException("End of File 必须在最后");break;}
                if(line.isEmpty()||" +-".indexOf(line.charAt(0))<0)throw new IOException("补丁行必须以空格、+ 或 - 开头");
                if(line.charAt(0)!='+')oldLines.add(line.substring(1));
                if(line.charAt(0)!='-')newLines.add(line.substring(1));
            }
            int found;
            if(oldLines.isEmpty()) {
                if(!original.isEmpty()&&!end)throw new IOException("插入内容需要上下文，或 End of File 标记");
                found=original.size();
            }else found=unique(original,oldLines,searchStart,end);
            result.addAll(original.subList(sourceCursor,found));result.addAll(newLines);sourceCursor=found+oldLines.size();
        }
        result.addAll(original.subList(sourceCursor,original.size()));
        String separator=crlf?"\r\n":"\n";
        return String.join(separator,result)+(newline&&!result.isEmpty()?separator:"");
    }
    private static int unique(List<String> source,List<String> old,int start,boolean end) throws IOException {
        int found=-1;
        for(int i=start;i<=source.size()-old.size();i++) {
            if(end&&i+old.size()!=source.size())continue;
            if(source.subList(i,i+old.size()).equals(old)) {
                if(found!=-1)throw new IOException("补丁上下文不唯一，请增加上下文");found=i;
            }
        }
        if(found==-1)throw new IOException("补丁上下文不匹配，尚未修改文件");return found;
    }
}
