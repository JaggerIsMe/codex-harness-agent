package com.myharness.agent.workspace;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Ancestors cannot be renamed while pinned; changes address the opened object, never a reopened name. */
public final class WindowsWorkspaceHandles implements AutoCloseable {
    private interface Kernel extends StdCallLibrary {
        Kernel INSTANCE=Native.load("kernel32",Kernel.class,W32APIOptions.UNICODE_OPTIONS);
        boolean SetFileInformationByHandle(WinNT.HANDLE file,int informationClass,Pointer information,int length);
        boolean SetFilePointerEx(WinNT.HANDLE file,long distance,Pointer position,int method);
        boolean SetEndOfFile(WinNT.HANDLE file);
    }
    /** Exclusive data handle: no path reopening and no shared hard-link objects. */
    static DataEntry data(Path path,boolean write,boolean create) throws IOException {
        WinNT.HANDLE handle=Kernel32.INSTANCE.CreateFile(path.toString(),WinNT.GENERIC_READ|(write?WinNT.GENERIC_WRITE|WinNT.DELETE:0),
                0,null,create?WinNT.CREATE_NEW:WinNT.OPEN_EXISTING,WinNT.FILE_FLAG_OPEN_REPARSE_POINT,null);
        if(WinBase.INVALID_HANDLE_VALUE.equals(handle))throw error(Native.getLastError());
        try {
            WinBase.FILE_ATTRIBUTE_TAG_INFO tag=new WinBase.FILE_ATTRIBUTE_TAG_INFO();
            if(!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle,9,tag.getPointer(),new WinDef.DWORD(tag.size())))throw error(Native.getLastError());
            tag.read();
            if((tag.FileAttributes&(WinNT.FILE_ATTRIBUTE_REPARSE_POINT|WinNT.FILE_ATTRIBUTE_DIRECTORY))!=0)
                throw new IOException("工具仅允许普通文件，不允许链接或目录");
            Memory standard=new Memory(24);
            if(!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle,1,standard,new WinDef.DWORD(24)))throw error(Native.getLastError());
            if(standard.getInt(16)!=1)throw new IOException("工具不允许共享硬链接文件");
            return new DataEntry(handle,standard.getLong(8));
        }catch(IOException|RuntimeException failure){Kernel32.INSTANCE.CloseHandle(handle);throw failure;}
    }
    static final class DataEntry implements AutoCloseable {
        private final WinNT.HANDLE handle;
        private final long length;
        DataEntry(WinNT.HANDLE handle,long length){this.handle=handle;this.length=length;}
        byte[] read(int limit) throws IOException {
            if(length<0||length>limit)throw new IOException("文件超过工具大小限制");
            var output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];var count=new com.sun.jna.ptr.IntByReference();
            while(true) {
                if(Thread.currentThread().isInterrupted())throw new IOException("工具调用已取消");
                if(!Kernel32.INSTANCE.ReadFile(handle,buffer,buffer.length,count,null))throw error(Native.getLastError());
                if(count.getValue()==0)break;
                if(output.size()+count.getValue()>limit)throw new IOException("文件超过工具大小限制");
                output.write(buffer,0,count.getValue());
            }
            return output.toByteArray();
        }
        void write(byte[] bytes) throws IOException {
            if(!Kernel.INSTANCE.SetFilePointerEx(handle,0,null,0))throw error(Native.getLastError());
            var written=new com.sun.jna.ptr.IntByReference();
            int offset=0;
            while(offset<bytes.length) {
                byte[] chunk=java.util.Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+8192));
                if(!Kernel32.INSTANCE.WriteFile(handle,chunk,chunk.length,written,null)||written.getValue()==0)throw error(Native.getLastError());
                offset+=written.getValue();
            }
            if(!Kernel.INSTANCE.SetEndOfFile(handle)||!Kernel32.INSTANCE.FlushFileBuffers(handle))throw error(Native.getLastError());
        }
        void relocate(Path destination) throws IOException {new OpenEntry(handle).relocate(destination);}
        void delete() throws IOException {new OpenEntry(handle).delete();}
        @Override public void close(){Kernel32.INSTANCE.CloseHandle(handle);}
    }
    private final List<WinNT.HANDLE> pins=new ArrayList<>();
    static boolean supported(){return Platform.isWindows();}
    public static WindowsWorkspaceHandles pin(Path root,Path... parents) throws IOException {
        if(!supported())throw new WorkspaceFileException("UNSUPPORTED_FILESYSTEM","当前平台不支持安全的文件变更句柄");
        WindowsWorkspaceHandles result=new WindowsWorkspaceHandles();Set<Path> done=new HashSet<>();
        try {
            for(Path parent:parents) {
                Path current=root.toAbsolutePath().normalize();Path end=parent.toAbsolutePath().normalize();
                if(!end.startsWith(current))throw new WorkspaceFileException("INVALID_PATH","路径超出工作区");
                if(done.add(current))result.pins.add(open(current,false));
                for(Path segment:current.relativize(end)) {
                    current=current.resolve(segment);if(done.add(current))result.pins.add(open(current,false));
                }
            }
            return result;
        }catch(IOException|RuntimeException e){result.close();throw e;}
    }
    static OpenEntry entry(Path path,boolean mutation) throws IOException {return new OpenEntry(open(path,mutation));}
    private static WinNT.HANDLE open(Path path,boolean mutation) throws IOException {
        return open(path,mutation,WinNT.FILE_SHARE_READ|WinNT.FILE_SHARE_WRITE,true);
    }
    private static WinNT.HANDLE open(Path path,boolean mutation,int share,boolean pin) throws IOException {
        // Metadata-only handles do not participate in Windows sharing checks. FILE_READ_DATA/LIST_DIRECTORY is essential.
        WinNT.HANDLE handle=Kernel32.INSTANCE.CreateFile(path.toString(),WinNT.FILE_READ_ATTRIBUTES|(pin?WinNT.FILE_READ_DATA:0)|(mutation?WinNT.DELETE:0),
                share,null,WinNT.OPEN_EXISTING,
                WinNT.FILE_FLAG_BACKUP_SEMANTICS|WinNT.FILE_FLAG_OPEN_REPARSE_POINT,null);
        if(WinBase.INVALID_HANDLE_VALUE.equals(handle))throw error(Native.getLastError());
        WinBase.FILE_ATTRIBUTE_TAG_INFO info=new WinBase.FILE_ATTRIBUTE_TAG_INFO();
        if(!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle,9,info.getPointer(),new WinDef.DWORD(info.size()))) {int code=Native.getLastError();Kernel32.INSTANCE.CloseHandle(handle);throw error(code);}
        info.read();
        if((info.FileAttributes&WinNT.FILE_ATTRIBUTE_REPARSE_POINT)!=0) {
            Kernel32.INSTANCE.CloseHandle(handle);throw new WorkspaceFileException("UNSUPPORTED_ENTRY","不支持链接或重解析路径");
        }
        return handle;
    }
    static String identity(Path path) throws IOException {
        WinNT.HANDLE handle=open(path,false,WinNT.FILE_SHARE_READ|WinNT.FILE_SHARE_WRITE|WinNT.FILE_SHARE_DELETE,false);
        try {
            Memory info=new Memory(24);info.clear();
            if(!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle,18,info,new WinDef.DWORD(24)))
                throw new WorkspaceFileException("UNSUPPORTED_FILESYSTEM","文件系统不能提供可靠的文件身份");
            // FILE_ID_INFO retains the full 128-bit identifier on ReFS as well as NTFS.
            byte[] id=info.getByteArray(8,16);boolean empty=true;for(byte value:id)if(value!=0){empty=false;break;}
            if(empty)throw new WorkspaceFileException("UNSUPPORTED_FILESYSTEM","文件系统返回了空文件身份");
            return HexFormat.of().formatHex(info.getByteArray(0,24));
        }finally{Kernel32.INSTANCE.CloseHandle(handle);}
    }
    static final class OpenEntry implements AutoCloseable {
        private final WinNT.HANDLE handle;
        private OpenEntry(WinNT.HANDLE handle){this.handle=handle;}
        void relocate(Path destination) throws IOException {
            byte[] name=destination.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_16LE);
            int pointerOffset=Native.POINTER_SIZE==8?8:4;int lengthOffset=pointerOffset+Native.POINTER_SIZE;
            Memory info=new Memory(lengthOffset+4L+name.length+2);info.clear();
            info.setByte(0,(byte)0); // ReplaceIfExists = FALSE. No copy/delete fallback.
            info.setPointer(pointerOffset,null);info.setInt(lengthOffset,name.length);info.write(lengthOffset+4L,name,0,name.length);
            if(!Kernel.INSTANCE.SetFileInformationByHandle(handle,3,info,(int)info.size()))throw error(Native.getLastError());
        }
        void delete() throws IOException {
            Memory info=new Memory(1);info.setByte(0,(byte)1);
            if(!Kernel.INSTANCE.SetFileInformationByHandle(handle,4,info,1))throw error(Native.getLastError());
        }
        @Override public void close(){Kernel32.INSTANCE.CloseHandle(handle);}
    }
    private static WorkspaceFileException error(int code) {
        return switch(code) {
            case 80,183 -> new WorkspaceFileException("TARGET_EXISTS","目标已存在，请使用其他名称");
            case 2,3 -> new WorkspaceFileException("SOURCE_CHANGED","文件或目录已不存在，请刷新后重试");
            case 17 -> new WorkspaceFileException("CROSS_FILESYSTEM","不支持跨文件系统移动");
            case 145 -> new WorkspaceFileException("SOURCE_CHANGED","目录出现新内容，请重新检查删除范围");
            case 32,33 -> new WorkspaceFileException("FILE_IN_USE","文件正在被其他程序占用");
            case 5 -> new WorkspaceFileException("ACCESS_DENIED","文件访问被拒绝");
            default -> new WorkspaceFileException("FILESYSTEM_ERROR","文件系统操作失败（"+code+"）");
        };
    }
    @Override public void close(){for(int i=pins.size()-1;i>=0;i--)Kernel32.INSTANCE.CloseHandle(pins.get(i));pins.clear();}
}
