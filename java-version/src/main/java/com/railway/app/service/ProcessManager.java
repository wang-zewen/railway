package com.railway.app.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程管理器
 */
@Service
public class ProcessManager {

    private final ConcurrentHashMap<String, Process> processes = new ConcurrentHashMap<>();

    /**
     * 启动进程
     */
    public void startProcess(String name, String command) throws IOException {
        System.out.println("Starting process: " + name);
        System.out.println("Command: " + command);

        ProcessBuilder pb = new ProcessBuilder();

        // 根据操作系统选择不同的 shell
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            pb.command("cmd.exe", "/c", command);
        } else {
            pb.command("sh", "-c", command);
        }

        // 重定向输出到 /dev/null 或 NUL
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process = pb.start();
        processes.put(name, process);

        System.out.println(name + " is running");
    }

    /**
     * 停止进程
     */
    public void stopProcess(String name) {
        Process process = processes.get(name);
        if (process != null) {
            // 先尝试正常终止
            if (process.isAlive()) {
                process.destroy();
                try {
                    process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                }
            }
            processes.remove(name);
        }

        // 强制杀死所有匹配的进程（处理 nohup 启动的进程）
        killProcessByName(name);
        System.out.println("Stopped process: " + name);
    }

    /**
     * 停止所有进程
     */
    public void stopAllProcesses() {
        List<String> processNames = new ArrayList<>(processes.keySet());
        for (String name : processNames) {
            stopProcess(name);
        }
    }

    /**
     * 通过进程名杀死进程
     */
    public void killProcessByName(String processName) {
        try {
            String command;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                command = "taskkill /f /im " + processName + ".exe";
            } else {
                command = "pkill -f \"[" + processName.charAt(0) + "]" + processName.substring(1) + "\"";
            }

            ProcessBuilder pb = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb.command("cmd.exe", "/c", command);
            } else {
                pb.command("sh", "-c", command);
            }

            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process process = pb.start();
            process.waitFor();
        } catch (Exception e) {
            // 忽略错误
        }
    }

    /**
     * 检查进程是否在运行
     */
    public boolean isProcessRunning(String name) {
        Process process = processes.get(name);
        return process != null && process.isAlive();
    }

    /**
     * 清理所有tunnel相关进程（cloudflared, xray, nezha）
     * 这个方法不依赖随机生成的进程名称，直接通过进程命令行特征杀死进程
     */
    public void cleanupAllTunnelProcesses() {
        System.out.println("Cleaning up all tunnel-related processes...");

        try {
            // 构建清理命令，杀死所有相关进程
            List<String> commands = new ArrayList<>();

            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                // Windows: 使用 taskkill
                commands.add("taskkill /f /im cloudflared.exe");
                commands.add("taskkill /f /im xray.exe");
                commands.add("taskkill /f /im nezha-agent.exe");
            } else {
                // Linux/Unix: 使用 pkill -f 匹配命令行
                // 杀死所有cloudflared进程（匹配包含"tunnel"关键字的cloudflared进程）
                commands.add("pkill -9 -f 'cloudflared.*tunnel'");
                // 杀死所有xray进程（匹配包含"-c"配置文件参数的xray进程）
                commands.add("pkill -9 -f 'xray.*-c'");
                // 杀死所有nezha-agent进程（v0版本）
                commands.add("pkill -9 -f 'nezha-agent.*-s'");
                // 杀死所有nezha-agent进程（v1版本）
                commands.add("pkill -9 -f 'nezha-agent.*-c.*config.yaml'");
            }

            // 执行所有清理命令
            for (String command : commands) {
                try {
                    ProcessBuilder pb = new ProcessBuilder();
                    if (System.getProperty("os.name").toLowerCase().contains("win")) {
                        pb.command("cmd.exe", "/c", command);
                    } else {
                        pb.command("sh", "-c", command);
                    }

                    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    pb.redirectError(ProcessBuilder.Redirect.DISCARD);

                    Process process = pb.start();
                    // 等待命令执行完成
                    process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    // 忽略单个命令的错误，继续执行其他命令
                    System.out.println("Warning: Failed to execute cleanup command: " + command);
                }
            }

            // 等待一段时间确保进程真正被杀死
            Thread.sleep(1000);
            System.out.println("Tunnel processes cleanup completed");

        } catch (Exception e) {
            System.err.println("Error during tunnel processes cleanup: " + e.getMessage());
        }
    }
}
