package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Request-only projection. Persist hashes of provider-owned opaque state, never the state or credentials. */
final class ResponsesHistoryPolicy implements AutoCloseable {
    static final String POLICY="responses-portable-v1";
    private static final int MAX_ITEMS=100000;
    private final ObjectMapper json;
    private final Path manifest;
    private final Set<String> owned=new HashSet<>();
    private Set<String> otherTargets;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    ResponsesHistoryPolicy(Path data,Path workspace,String thread,String identity,ObjectMapper json) {
        this.json=json;
        FileChannel opened=null;FileLock held=null;
        try {
            UUID.fromString(thread);
            Path root=data.toAbsolutePath().normalize().resolve("responses-history");Files.createDirectories(root);
            if(!root.toRealPath().getParent().equals(data.toRealPath()) || root.toRealPath().startsWith(workspace.toRealPath()))
                throw failure("History metadata must be outside the Project Workspace and inside Agent data-dir");
            Path dir=root.toRealPath().resolve(thread);Files.createDirectories(dir);
            if(!dir.toRealPath().equals(dir)) throw failure("History metadata directory must not be linked");
            opened=FileChannel.open(dir.resolve("runtime.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            held=opened.tryLock();if(held==null) throw failure("Conversation history is locked by another Agent process");
            manifest=dir.resolve(digest(identity)+".json");
            if(Files.exists(manifest)) {
                if(Files.isSymbolicLink(manifest) || Files.size(manifest)>8*1024*1024) throw failure("Invalid history provenance manifest");
                JsonNode state=json.readTree(Files.readString(manifest));
                if(!POLICY.equals(state.path("policy").asText()) || !workspace.toRealPath().toString().equals(state.path("workspace").asText())
                        || !state.path("owned").isArray() || state.path("owned").size()>MAX_ITEMS) throw failure("Invalid history provenance manifest");
                for(JsonNode key:state.path("owned")) {if(!key.asText().matches("[0-9a-f]{64}")) throw failure("Invalid history provenance hash");owned.add(key.asText());}
            }
            this.workspace=workspace.toRealPath();channel=opened;lock=held;
        } catch(IOException | RuntimeException e) {
            try {if(held!=null) held.release();if(opened!=null) opened.close();} catch(IOException ignored) { }
            if(e instanceof CodexException value) throw value;
            throw failure("Cannot open history provenance metadata");
        }
    }
    private final Path workspace;
    record Projection(ObjectNode request,int excludedReasoning,int convertedSearches) { }

    synchronized Projection project(JsonNode raw) {
        if(closed) throw failure("History compatibility runtime is closed");
        if(!(raw instanceof ObjectNode request) || !raw.path("input").isArray()) throw failure("Expected a complete Responses input array");
        if(raw.hasNonNull("previous_response_id") || raw.hasNonNull("conversation")) throw failure("Remote incremental history is not portable; full input is required");
        ObjectNode copy=request.deepCopy();var input=copy.putArray("input");int excluded=0,searches=0;
        Map<String,String> pending=new HashMap<>();Set<String> seen=new HashSet<>();
        for(JsonNode item:raw.path("input")) {
            String type=item.path("type").asText(item.has("role")?"message":"");
            boolean nativeItem=owned.contains(fingerprint(item));
            if(type.equals("reasoning")) {if(nativeItem) input.add(item.deepCopy());else excluded++;continue;}
            if(type.equals("web_search_call")) {
                SearchHistoryItem.evidence(item);
                if(nativeItem) input.add(item.deepCopy());
                else {
                    input.add(SearchHistoryItem.portable(item,otherTargetOwns(fingerprint(item))?"other_provider":"unverified",json));
                    searches++;
                }
                continue;
            }
            if(type.equals("message")) {
                if(!Set.of("user","assistant","system","developer").contains(item.path("role").asText())) throw failure("Unsupported message role");
                if(!item.path("content").isTextual()) content(item.path("content"));
            } else if(Set.of("function_call","custom_tool_call").contains(type)) {
                String call=item.path("call_id").asText();
                if(call.isBlank() || !seen.add(call)) throw failure("Missing or duplicate tool call id");
                if(item.path("name").asText().isBlank() || !item.path(type.equals("function_call")?"arguments":"input").isTextual()) throw failure("Malformed tool call");
                pending.put(call,type+"_output");
            } else if(Set.of("function_call_output","custom_tool_call_output").contains(type)) {
                if(!type.equals(pending.remove(item.path("call_id").asText()))) throw failure("Tool output has no matching call");
                if(!item.path("output").isTextual()) content(item.path("output"));
            } else if(type.equals("additional_tools")) {
                if(!item.path("tools").isArray()) throw failure("Malformed additional_tools request declaration");
            } else if(!nativeItem) throw failure("Unverified provider-specific history item: "+safeType(type));
            input.add(item.deepCopy());
        }
        if(!pending.isEmpty()) throw failure("Unfinished tool calls cannot be safely replayed");
        return new Projection(copy,excluded,searches);
    }
    private void content(JsonNode parts) {
        if(!parts.isArray()) throw failure("Expected structured content");
        for(JsonNode part:parts) {
            switch(part.path("type").asText()) {
                case "input_text","output_text" -> {if(!part.path("text").isTextual()) throw failure("Invalid text content");}
                // Preserve modalities verbatim; the target provider performs its capability validation.
                case "input_image" -> {if(!part.path("image_url").isTextual()) throw failure("Invalid image content");}
                default -> throw failure("Unverified history content type");
            }
        }
    }
    synchronized void observe(JsonNode event) {
        if(closed) throw failure("History compatibility runtime is closed");
        boolean changed=false;
        if("response.output_item.done".equals(event.path("type").asText())) changed=remember(event.path("item"));
        if("response.completed".equals(event.path("type").asText())) for(JsonNode item:event.path("response").path("output")) changed=remember(item)||changed;
        // Non-streaming /compact and Responses replies also return output items.
        if(event.path("output").isArray()) for(JsonNode item:event.path("output")) changed=remember(item)||changed;
        if(changed) save();
    }
    private boolean remember(JsonNode item) {
        String type=item.path("type").asText();
        if(type.isEmpty() || Set.of("message","function_call","custom_tool_call","function_call_output","custom_tool_call_output").contains(type)) return false;
        if(type.equals("web_search_call") && !Set.of("completed","failed").contains(item.path("status").asText())) return false;
        String key=fingerprint(item);if(owned.contains(key)) return false;
        if(owned.size()>=MAX_ITEMS) throw failure("History provenance limit reached");
        return owned.add(key);
    }
    private String fingerprint(JsonNode item) {
        if("web_search_call".equals(item.path("type").asText())) {
            // Codex omits item ids in outgoing history, and adds optional null native metadata.
            // Recognize the complete observed content, never a caller-supplied id alone.
            ObjectNode evidence=SearchHistoryItem.evidence(item);
            evidence.remove("id");
            if(item.hasNonNull("internal_chat_message_metadata_passthrough"))
                evidence.set("internal_chat_message_metadata_passthrough",item.get("internal_chat_message_metadata_passthrough"));
            return digest("search-portable-v1\n"+canonical(evidence));
        }
        // Codex may omit IDs or empty optional fields when serializing output back to input.
        if(!item.path("encrypted_content").asText().isEmpty()) return digest(item.path("type").asText()+"\n"+item.path("encrypted_content").asText());
        if("reasoning".equals(item.path("type").asText())) return digest("reasoning\n"+reasoningParts(item.path("summary"))+"\n"+reasoningParts(item.path("content")));
        if(item.isObject()) {ObjectNode normalized=item.deepCopy();normalized.remove(List.of("id","status"));return digest(canonical(normalized).toString());}
        return "";
    }
    private boolean otherTargetOwns(String key) {
        if(otherTargets==null) {
            Set<String> found=new HashSet<>();
            try(var files=Files.list(manifest.getParent())) {
                var peers=files.filter(p->p.getFileName().toString().matches("[0-9a-f]{64}\\.json") && !p.equals(manifest)).limit(65).toList();
                if(peers.size()>64) throw failure("Too many history provenance sources");
                for(Path peer:peers) {
                    if(Files.isSymbolicLink(peer) || Files.size(peer)>8*1024*1024) throw failure("Invalid history provenance manifest");
                    JsonNode state=json.readTree(Files.readString(peer));
                    if(state==null || !POLICY.equals(state.path("policy").asText()) || !workspace.toString().equals(state.path("workspace").asText())
                            || !state.path("owned").isArray() || state.path("owned").size()>MAX_ITEMS) throw failure("Invalid history provenance manifest");
                    for(JsonNode hash:state.path("owned")) {
                        if(!hash.isTextual() || !hash.asText().matches("[0-9a-f]{64}")) throw failure("Invalid history provenance hash");
                        found.add(hash.asText());
                        if(found.size()>MAX_ITEMS*4) throw failure("History provenance source limit reached");
                    }
                }
                otherTargets=found;
            } catch(IOException e) {throw failure("Cannot read history provenance sources");}
        }
        return otherTargets.contains(key);
    }
    private JsonNode canonical(JsonNode value) {
        if(value.isMissingNode()) return json.nullNode();
        if(value.isArray()) {var array=json.createArrayNode();value.forEach(item->array.add(canonical(item)));return array;}
        if(value.isObject()) {
            var object=json.createObjectNode();var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key->object.set(key,canonical(value.get(key))));return object;
        }
        return value;
    }
    private JsonNode reasoningParts(JsonNode value) {return value.isMissingNode() || value.isNull()?json.createArrayNode():canonical(value);}
    private void save() {
        Path temporary=manifest.resolveSibling(UUID.randomUUID()+".tmp");
        try {
            ObjectNode state=json.createObjectNode().put("policy",POLICY).put("workspace",workspace.toString());
            var hashes=state.putArray("owned");owned.stream().sorted().forEach(hashes::add);
            Files.writeString(temporary,state.toString(),StandardOpenOption.CREATE_NEW);
            try(FileChannel file=FileChannel.open(temporary,StandardOpenOption.WRITE)) {file.force(true);}
            Files.move(temporary,manifest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch(IOException e) {closed=true;throw failure("Cannot persist history provenance");}
        finally {try {Files.deleteIfExists(temporary);} catch(IOException ignored) { }}
    }
    static String digest(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    private static String safeType(String type) {return type.matches("[a-z_]{1,64}")?type:"unknown";}
    static CodexException failure(String message) {return new CodexException("HISTORY_INCOMPATIBLE: "+message);}
    @Override public synchronized void close() {closed=true;try {lock.release();} catch(IOException ignored) { }try {channel.close();} catch(IOException ignored) { }}
}
