package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.workspace.WorkspaceToolFiles;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Returns decoded raster pixels, never a host path or an arbitrary URL. */
final class WorkspaceImageTool {
    static final String NAME="harness_view_image";
    static final int MAX_BYTES=10*1024*1024;
    private WorkspaceImageTool(){ }
    static ObjectNode view(Path workspace,String path,ObjectMapper json) throws IOException {
        byte[] bytes;
        try(var files=new WorkspaceToolFiles(workspace,false)){bytes=files.read(path,MAX_BYTES);}
        byte[] normalized=normalize(bytes);
        var result=json.createObjectNode().put("success",true);
        var items=result.putArray("contentItems");
        items.addObject().put("type","inputText").put("text","Workspace image: "+path);
        items.addObject().put("type","inputImage").put("imageUrl","data:image/png;base64,"+Base64.getEncoder().encodeToString(normalized));
        return result;
    }
    static byte[] normalize(byte[] bytes) throws IOException {
        if(bytes.length==0||bytes.length>MAX_BYTES)throw new IOException("图片超过 10 MiB 限制或为空");
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext())throw new IOException("仅支持 PNG、JPEG、GIF 图片，不能通过此工具读取文本或 SVG");
            var reader=readers.next();
            try {
                if(!Set.of("png","jpeg","gif").contains(reader.getFormatName().toLowerCase(java.util.Locale.ROOT)))throw new IOException("不支持的图片格式");
                reader.setInput(input,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||(long)width*height>16_000_000)throw new IOException("图片像素超过 1600 万限制");
                var image=reader.read(0);var output=new ByteArrayOutputStream();
                if(image==null||!ImageIO.write(image,"png",output))throw new IOException("图片解码失败");
                if(output.size()>MAX_BYTES)throw new IOException("解码后的图片超过 10 MiB 限制");
                return output.toByteArray();
            }finally{reader.dispose();}
        }
    }
}
