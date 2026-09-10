package com.myharness.agent.workspace;

import java.io.IOException;

final class WorkspaceFileException extends IOException {
    final String code;
    WorkspaceFileException(String code,String message) {super(message);this.code=code;}
}
