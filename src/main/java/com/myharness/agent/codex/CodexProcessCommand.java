package com.myharness.agent.codex;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class CodexProcessCommand {
    private CodexProcessCommand() {
    }

    static List<String> appServer(String configuredCommand) {
        return appServer(configuredCommand,false,"elevated",System.getProperty("os.name"),System.getenv("PATH"),System.getenv("ComSpec"),null);
    }

    static List<String> appServer(String configuredCommand, String osName, String pathValue, String commandShell) {
        return appServer(configuredCommand,false,"elevated",osName,pathValue,commandShell,null);
    }

    static List<String> appServer(String configuredCommand,boolean strictIsolation,String windowsSandbox) {
        return appServer(configuredCommand,strictIsolation,windowsSandbox,System.getProperty("os.name"),System.getenv("PATH"),System.getenv("ComSpec"),null);
    }

    static List<String> appServer(String configuredCommand,boolean strictIsolation,String windowsSandbox,Path modelCatalog) {
        return appServer(configuredCommand,strictIsolation,windowsSandbox,System.getProperty("os.name"),System.getenv("PATH"),System.getenv("ComSpec"),modelCatalog);
    }

    static List<String> appServer(String configuredCommand,boolean strictIsolation,String windowsSandbox,
                                  String osName,String pathValue,String commandShell) {
        return appServer(configuredCommand,strictIsolation,windowsSandbox,osName,pathValue,commandShell,null);
    }

    static List<String> appServer(String configuredCommand,boolean strictIsolation,String windowsSandbox,
                                  String osName,String pathValue,String commandShell,Path modelCatalog) {
        String configured = configuredCommand == null ? "" : configuredCommand.trim();
        if (configured.isEmpty()) {
            throw new CodexException("Codex command must be configured");
        }
        if (configured.indexOf('"') >= 0) {
            throw new CodexException("Codex command must be an executable name or path without quotes");
        }

        boolean windows=osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
        if (strictIsolation && !windows) {
            throw new CodexException("Strict project isolation requires Windows 10 1809+ or Windows 11");
        }
        if (strictIsolation && !"elevated".equalsIgnoreCase(windowsSandbox)) {
            throw new CodexException("Strict project isolation requires the Codex elevated Windows sandbox");
        }
        List<String> arguments=new ArrayList<>();
        arguments.add("app-server");
        if (strictIsolation) {
            arguments.add("--strict-config");
            arguments.add("-c");
            arguments.add("windows.sandbox=\"elevated\"");
        }
        if(modelCatalog!=null) {
            arguments.add("-c");
            arguments.add("model_catalog_json=\""+modelCatalog.toAbsolutePath().normalize().toString().replace('\\','/')+"\"");
        }
        arguments.add("--stdio");

        if (windows) {
            Path resolved = resolveWindowsCommand(configured, pathValue);
            if (isBatchFile(resolved)) {
                String shell = commandShell == null || commandShell.trim().isEmpty() ? "cmd.exe" : commandShell;
                String invocation = quote(resolved.toString()) + " " + String.join(" ",arguments);
                return Arrays.asList(shell, "/d", "/s", "/c", invocation);
            }
            configured = resolved.toString();
        }
        List<String> command=new ArrayList<>(); command.add(configured); command.addAll(arguments); return command;
    }

    private static Path resolveWindowsCommand(String configured, String pathValue) {
        Path direct = Paths.get(configured);
        if (direct.isAbsolute() || direct.getParent() != null) {
            return direct.toAbsolutePath().normalize();
        }

        List<String> suffixes = new ArrayList<>();
        if (hasExtension(configured)) {
            suffixes.add("");
        } else {
            // npm 在 Windows 上提供可执行的 .cmd 包装器；优先它以避开不可直接启动的应用执行别名。
            suffixes.add(".cmd");
            suffixes.add(".bat");
            suffixes.add(".exe");
            suffixes.add("");
        }
        if (pathValue != null) {
            String[] directories = pathValue.split(Pattern.quote(File.pathSeparator));
            for (String suffix : suffixes) {
                for (String directory : directories) {
                    if (directory == null || directory.trim().isEmpty()) {
                        continue;
                    }
                    Path candidate = Paths.get(directory.trim(), configured + suffix).toAbsolutePath().normalize();
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return direct;
    }

    private static boolean hasExtension(String command) {
        String name = Paths.get(command).getFileName().toString();
        return name.lastIndexOf('.') > 0;
    }

    private static boolean isBatchFile(Path command) {
        String value = command.toString().toLowerCase(Locale.ROOT);
        return value.endsWith(".cmd") || value.endsWith(".bat");
    }

    private static String quote(String value) {
        return '"' + value + '"';
    }
}
