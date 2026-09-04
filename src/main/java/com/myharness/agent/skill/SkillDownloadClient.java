package com.myharness.agent.skill;

import java.nio.file.Path;

public interface SkillDownloadClient {
    void download(String downloadUrl, Path target, long maximumBytes);
}
