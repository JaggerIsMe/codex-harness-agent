package com.myharness.agent.skill;

import java.nio.file.Path;

public interface SkillDownloadClient {
    void download(String downloadUrl, Path target, long maximumBytes);
    default void download(String downloadUrl, Path target, long maximumBytes, com.myharness.agent.attachment.AttachmentPreparation preparation) {
        if(preparation!=null) preparation.check();
        download(downloadUrl,target,maximumBytes);
        if(preparation!=null) preparation.check();
    }
}
