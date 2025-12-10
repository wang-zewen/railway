package com.railway.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.railway.app.config.AppConfig;
import com.railway.app.util.SystemUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 服务器启动服务
 * 负责初始化和启动各种组件
 */
@Service
public class ServerService {

    @Autowired
    private AppConfig appConfig;

    @Autowired
    private FileDownloadService downloadService;

    @Autowired
    private ProcessManager processManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 随机生成的文件名
    private String npmName;
    private String webName;
    private String botName;
    private String phpName;

    // 文件路径
    private Path npmPath;
    private Path phpPath;
    private Path webPath;
    private Path botPath;
    private Path subPath;
    private Path bootLogPath;
    private Path configPath;
    private Path pidFilePath;

    @PostConstruct
    public void startServer() {
        System.out.println("Starting server initialization...");

        try {
            // 初始化路径（需要先初始化才能使用 pidFilePath）
            initializePaths();

            // 清理旧进程：根据 PID 文件杀死所有之前启动的进程
            System.out.println("Cleaning up old processes from PID file...");
            cleanupOldProcessesFromPidFile();
            Thread.sleep(2000); // 等待进程完全终止

            // 清理历史文件
            cleanupOldFiles();

            // 生成配置文件
            generateConfig();

            // 处理 Argo 隧道配置
            setupArgoTunnel();

            // 下载并运行依赖文件
            downloadFilesAndRun();

            // 延迟后提取域名
            Thread.sleep(5000);
            extractDomains();

            System.out.println("Server initialization completed");
            System.out.println("Application is running on port: " + appConfig.getPort());
            System.out.println("Access configuration page at: http://localhost:" + appConfig.getPort() + "/config");

            // 90秒后清理文件
            scheduleFileCleanup();

        } catch (Exception e) {
            System.err.println("Error during server initialization: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 重新启动服务（配置修改后调用）
     */
    public void restartServices() {
        System.out.println("Restarting services...");

        try {
            // 1. 先停止所有已跟踪的进程（使用当前的随机名称）
            processManager.stopAllProcesses();

            // 2. 强制清理所有tunnel相关进程（不依赖随机名称，直接通过进程特征杀死）
            // 这确保即使进程名称改变，所有旧的cloudflared/xray/nezha进程都会被杀死
            processManager.cleanupAllTunnelProcesses();

            // 3. 等待端口释放和资源清理（增加等待时间以确保TCP连接完全关闭）
            Thread.sleep(3000);

            // 4. 重新加载配置
            appConfig.init();

            // 5. 重新启动服务
            startServer();

        } catch (Exception e) {
            System.err.println("Error restarting services: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 初始化路径
     */
    private void initializePaths() {
        npmName = SystemUtils.generateRandomName();
        webName = SystemUtils.generateRandomName();
        botName = SystemUtils.generateRandomName();
        phpName = SystemUtils.generateRandomName();

        Path baseDir = Paths.get(appConfig.getFilePath());
        npmPath = baseDir.resolve(npmName);
        phpPath = baseDir.resolve(phpName);
        webPath = baseDir.resolve(webName);
        botPath = baseDir.resolve(botName);
        subPath = baseDir.resolve("sub.txt");
        bootLogPath = baseDir.resolve("boot.log");
        configPath = baseDir.resolve("config.json");
        pidFilePath = baseDir.resolve("pids.txt");
    }

    /**
     * 清理历史文件
     */
    private void cleanupOldFiles() {
        try {
            Path filePath = Paths.get(appConfig.getFilePath());
            if (Files.exists(filePath) && Files.isDirectory(filePath)) {
                Files.walk(filePath)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            String fileName = file.getFileName().toString();
                            if ((fileName.endsWith(".log") || fileName.endsWith(".json") ||
                                fileName.endsWith(".txt")) && !fileName.equals(".env")) {
                                Files.deleteIfExists(file);
                            }
                        } catch (IOException e) {
                            // 忽略删除错误
                        }
                    });
            }
        } catch (IOException e) {
            // 忽略所有错误
        }
    }

    /**
     * 生成 xray 配置文件
     */
    private void generateConfig() {
        try {
            Map<String, Object> config = new HashMap<>();

            // Log configuration
            Map<String, String> log = new HashMap<>();
            log.put("access", "/dev/null");
            log.put("error", "/dev/null");
            log.put("loglevel", "none");
            config.put("log", log);

            // Inbounds configuration - 需要 5 个 inbound！
            List<Map<String, Object>> inbounds = new ArrayList<>();

            // 1. Main inbound (ARGO_PORT) with fallbacks
            Map<String, Object> mainInbound = new HashMap<>();
            mainInbound.put("port", appConfig.getArgoPort());
            mainInbound.put("protocol", "vless");

            Map<String, Object> mainSettings = new HashMap<>();
            List<Map<String, Object>> mainClients = new ArrayList<>();
            Map<String, Object> mainClient = new HashMap<>();
            mainClient.put("id", appConfig.getUuid());
            mainClient.put("flow", "xtls-rprx-vision");
            mainClients.add(mainClient);
            mainSettings.put("clients", mainClients);
            mainSettings.put("decryption", "none");

            // Fallbacks configuration
            List<Map<String, Object>> fallbacks = new ArrayList<>();
            fallbacks.add(Map.of("dest", 3001));
            fallbacks.add(Map.of("path", appConfig.getVlessPath(), "dest", 3002));
            fallbacks.add(Map.of("path", appConfig.getVmessPath(), "dest", 3003));
            fallbacks.add(Map.of("path", appConfig.getTrojanPath(), "dest", 3004));
            mainSettings.put("fallbacks", fallbacks);

            mainInbound.put("settings", mainSettings);
            mainInbound.put("streamSettings", Map.of("network", "tcp"));
            inbounds.add(mainInbound);

            // 2. VLESS TCP inbound (3001)
            Map<String, Object> vlessTcp = new HashMap<>();
            vlessTcp.put("port", 3001);
            vlessTcp.put("listen", "127.0.0.1");
            vlessTcp.put("protocol", "vless");
            vlessTcp.put("settings", Map.of(
                "clients", List.of(Map.of("id", appConfig.getUuid())),
                "decryption", "none"
            ));
            vlessTcp.put("streamSettings", Map.of(
                "network", "tcp",
                "security", "none"
            ));
            inbounds.add(vlessTcp);

            // 3. VLESS WebSocket inbound (3002)
            Map<String, Object> vlessWs = new HashMap<>();
            vlessWs.put("port", 3002);
            vlessWs.put("listen", "127.0.0.1");
            vlessWs.put("protocol", "vless");
            vlessWs.put("settings", Map.of(
                "clients", List.of(Map.of("id", appConfig.getUuid(), "level", 0)),
                "decryption", "none"
            ));
            vlessWs.put("streamSettings", Map.of(
                "network", "ws",
                "security", "none",
                "wsSettings", Map.of("path", appConfig.getVlessPath())
            ));
            vlessWs.put("sniffing", Map.of(
                "enabled", true,
                "destOverride", List.of("http", "tls", "quic"),
                "metadataOnly", false
            ));
            inbounds.add(vlessWs);

            // 4. VMess WebSocket inbound (3003)
            Map<String, Object> vmessWs = new HashMap<>();
            vmessWs.put("port", 3003);
            vmessWs.put("listen", "127.0.0.1");
            vmessWs.put("protocol", "vmess");
            vmessWs.put("settings", Map.of(
                "clients", List.of(Map.of("id", appConfig.getUuid(), "alterId", 0))
            ));
            vmessWs.put("streamSettings", Map.of(
                "network", "ws",
                "wsSettings", Map.of("path", appConfig.getVmessPath())
            ));
            vmessWs.put("sniffing", Map.of(
                "enabled", true,
                "destOverride", List.of("http", "tls", "quic"),
                "metadataOnly", false
            ));
            inbounds.add(vmessWs);

            // 5. Trojan WebSocket inbound (3004)
            Map<String, Object> trojanWs = new HashMap<>();
            trojanWs.put("port", 3004);
            trojanWs.put("listen", "127.0.0.1");
            trojanWs.put("protocol", "trojan");
            trojanWs.put("settings", Map.of(
                "clients", List.of(Map.of("password", appConfig.getUuid()))
            ));
            trojanWs.put("streamSettings", Map.of(
                "network", "ws",
                "security", "none",
                "wsSettings", Map.of("path", appConfig.getTrojanPath())
            ));
            trojanWs.put("sniffing", Map.of(
                "enabled", true,
                "destOverride", List.of("http", "tls", "quic"),
                "metadataOnly", false
            ));
            inbounds.add(trojanWs);

            config.put("inbounds", inbounds);

            // DNS configuration
            Map<String, Object> dns = new HashMap<>();
            dns.put("servers", Arrays.asList("https+local://8.8.8.8/dns-query"));
            config.put("dns", dns);

            // Outbounds configuration
            List<Map<String, String>> outbounds = new ArrayList<>();
            Map<String, String> freedom = new HashMap<>();
            freedom.put("protocol", "freedom");
            freedom.put("tag", "direct");
            outbounds.add(freedom);

            Map<String, String> blackhole = new HashMap<>();
            blackhole.put("protocol", "blackhole");
            blackhole.put("tag", "block");
            outbounds.add(blackhole);

            config.put("outbounds", outbounds);

            // Write to file
            String configJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configPath, configJson);

            System.out.println("Configuration file generated: " + configPath);
        } catch (IOException e) {
            System.err.println("Error generating config file: " + e.getMessage());
        }
    }

    /**
     * 设置 Argo 隧道
     */
    private void setupArgoTunnel() {
        if (appConfig.getArgoDomain() == null || appConfig.getArgoDomain().isEmpty() ||
            appConfig.getArgoAuth() == null || appConfig.getArgoAuth().isEmpty()) {
            System.out.println("ARGO_DOMAIN or ARGO_AUTH variable is empty, use quick tunnels");
            return;
        }

        String argoAuth = appConfig.getArgoAuth();
        if (argoAuth.contains("TunnelSecret")) {
            try {
                // Save tunnel.json
                Path tunnelJsonPath = Paths.get(appConfig.getFilePath(), "tunnel.json");
                Files.writeString(tunnelJsonPath, argoAuth);

                // Extract tunnel ID
                String tunnelId = extractTunnelId(argoAuth);

                // Generate tunnel.yml
                String tunnelYml = String.format("""
                    tunnel: %s
                    credentials-file: %s
                    protocol: http2

                    ingress:
                      - hostname: %s
                        service: http://localhost:%d
                        originRequest:
                          noTLSVerify: true
                      - service: http_status:404
                    """, tunnelId, tunnelJsonPath, appConfig.getArgoDomain(), appConfig.getArgoPort());

                Path tunnelYmlPath = Paths.get(appConfig.getFilePath(), "tunnel.yml");
                Files.writeString(tunnelYmlPath, tunnelYml);

            } catch (IOException e) {
                System.err.println("Error setting up Argo tunnel: " + e.getMessage());
            }
        } else {
            System.out.println("ARGO_AUTH mismatch TunnelSecret, use token connect to tunnel");
        }
    }

    /**
     * 从 JSON 中提取 tunnel ID
     */
    private String extractTunnelId(String json) {
        try {
            // Simple extraction, assuming the format is consistent
            String[] parts = json.split("\"");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].contains("TunnelID") && i + 2 < parts.length) {
                    return parts[i + 2];
                }
            }
        } catch (Exception e) {
            System.err.println("Error extracting tunnel ID: " + e.getMessage());
        }
        return "";
    }

    /**
     * 下载并运行依赖文件
     */
    private void downloadFilesAndRun() throws Exception {
        String architecture = SystemUtils.getSystemArchitecture();
        List<FileInfo> filesToDownload = getFilesForArchitecture(architecture);

        if (filesToDownload.isEmpty()) {
            System.out.println("Can't find files for the current architecture");
            return;
        }

        // Download all files
        for (FileInfo fileInfo : filesToDownload) {
            downloadService.downloadFile(fileInfo.url, fileInfo.path);
            downloadService.setExecutable(fileInfo.path);
        }

        // Wait for downloads to complete
        Thread.sleep(2000);

        // Run nezha
        runNezha();

        // Run xray
        runXray();

        // Run cloudflared
        runCloudflared();

        Thread.sleep(2000);
    }

    /**
     * 获取要下载的文件列表
     */
    private List<FileInfo> getFilesForArchitecture(String architecture) {
        List<FileInfo> files = new ArrayList<>();

        String webUrl, botUrl;
        if ("arm".equals(architecture)) {
            webUrl = "https://arm64.ssss.nyc.mn/web";
            botUrl = "https://arm64.ssss.nyc.mn/bot";
        } else {
            webUrl = "https://amd64.ssss.nyc.mn/web";
            botUrl = "https://amd64.ssss.nyc.mn/bot";
        }

        files.add(new FileInfo(webPath, webUrl));
        files.add(new FileInfo(botPath, botUrl));

        // Add nezha if configured
        if (appConfig.getNezhaServer() != null && !appConfig.getNezhaServer().isEmpty() &&
            appConfig.getNezhaKey() != null && !appConfig.getNezhaKey().isEmpty()) {

            if (appConfig.getNezhaPort() != null && !appConfig.getNezhaPort().isEmpty()) {
                // Nezha v0
                String npmUrl = "arm".equals(architecture)
                    ? "https://arm64.ssss.nyc.mn/agent"
                    : "https://amd64.ssss.nyc.mn/agent";
                files.add(0, new FileInfo(npmPath, npmUrl));
            } else {
                // Nezha v1
                String phpUrl = "arm".equals(architecture)
                    ? "https://arm64.ssss.nyc.mn/v1"
                    : "https://amd64.ssss.nyc.mn/v1";
                files.add(0, new FileInfo(phpPath, phpUrl));
            }
        }

        return files;
    }

    /**
     * 运行 Nezha
     */
    private void runNezha() throws Exception {
        if (appConfig.getNezhaServer() == null || appConfig.getNezhaServer().isEmpty() ||
            appConfig.getNezhaKey() == null || appConfig.getNezhaKey().isEmpty()) {
            System.out.println("NEZHA variable is empty, skip running");
            return;
        }

        if (appConfig.getNezhaPort() == null || appConfig.getNezhaPort().isEmpty()) {
            // Nezha v1
            String port = "";
            if (appConfig.getNezhaServer().contains(":")) {
                port = appConfig.getNezhaServer().substring(appConfig.getNezhaServer().lastIndexOf(":") + 1);
            }

            Set<String> tlsPorts = new HashSet<>(Arrays.asList("443", "8443", "2096", "2087", "2083", "2053"));
            String nezhatls = tlsPorts.contains(port) ? "true" : "false";

            // Generate config.yaml
            String configYaml = String.format("""
                client_secret: %s
                debug: false
                disable_auto_update: true
                disable_command_execute: false
                disable_force_update: true
                disable_nat: false
                disable_send_query: false
                gpu: false
                insecure_tls: true
                ip_report_period: 1800
                report_delay: 4
                server: %s
                skip_connection_count: true
                skip_procs_count: true
                temperature: false
                tls: %s
                use_gitee_to_upgrade: false
                use_ipv6_country_code: false
                uuid: %s
                """, appConfig.getNezhaKey(), appConfig.getNezhaServer(), nezhatls, appConfig.getUuid());

            Files.writeString(Paths.get(appConfig.getFilePath(), "config.yaml"), configYaml);

            String command = String.format("nohup %s -c \"%s/config.yaml\" >/dev/null 2>&1 &",
                phpPath, appConfig.getFilePath());
            long pid = processManager.startProcess(phpName, command);
            if (pid > 0) savePid(pid);

            Thread.sleep(1000);
        } else {
            // Nezha v0
            Set<String> tlsPorts = new HashSet<>(Arrays.asList("443", "8443", "2096", "2087", "2083", "2053"));
            String nezhatls = tlsPorts.contains(appConfig.getNezhaPort()) ? "--tls" : "";

            String command = String.format("nohup %s -s %s:%s -p %s %s --disable-auto-update --report-delay 4 --skip-conn --skip-procs >/dev/null 2>&1 &",
                npmPath, appConfig.getNezhaServer(), appConfig.getNezhaPort(), appConfig.getNezhaKey(), nezhatls);
            long pid = processManager.startProcess(npmName, command);
            if (pid > 0) savePid(pid);

            Thread.sleep(1000);
        }
    }

    /**
     * 运行 Xray
     */
    private void runXray() throws Exception {
        // 检查端口是否被占用
        if (isPortInUse(appConfig.getArgoPort())) {
            System.err.println("WARNING: Port " + appConfig.getArgoPort() + " is already in use!");
            System.out.println("Killing processes on port " + appConfig.getArgoPort() + "...");
            killProcessOnPort(appConfig.getArgoPort());
            Thread.sleep(2000);
        }

        String command = String.format("nohup %s -c %s/config.json >/dev/null 2>&1 &",
            webPath, appConfig.getFilePath());
        long pid = processManager.startProcess(webName, command);
        if (pid > 0) savePid(pid);
        Thread.sleep(1000);
    }

    /**
     * 运行 Cloudflared
     */
    private void runCloudflared() throws Exception {
        if (!Files.exists(botPath)) {
            return;
        }

        String args;
        String argoAuth = appConfig.getArgoAuth();

        if (argoAuth != null && argoAuth.matches("^[A-Z0-9a-z=]{120,250}$")) {
            // Token
            args = String.format("tunnel --edge-ip-version auto --no-autoupdate --protocol http2 run --token %s", argoAuth);
        } else if (argoAuth != null && argoAuth.contains("TunnelSecret")) {
            // JSON
            args = String.format("tunnel --edge-ip-version auto --config %s/tunnel.yml run", appConfig.getFilePath());
        } else {
            // Quick tunnel
            args = String.format("tunnel --edge-ip-version auto --no-autoupdate --protocol http2 --logfile %s/boot.log --loglevel info --url http://localhost:%d",
                appConfig.getFilePath(), appConfig.getArgoPort());
        }

        String command = String.format("nohup %s %s >/dev/null 2>&1 &", botPath, args);
        long pid = processManager.startProcess(botName, command);
        if (pid > 0) savePid(pid);

        Thread.sleep(2000);
    }

    /**
     * 提取域名
     */
    private void extractDomains() throws Exception {
        String argoDomain;

        if (appConfig.getArgoDomain() != null && !appConfig.getArgoDomain().isEmpty() &&
            appConfig.getArgoAuth() != null && !appConfig.getArgoAuth().isEmpty()) {
            argoDomain = appConfig.getArgoDomain();
            System.out.println("ARGO_DOMAIN: " + argoDomain);
            generateLinks(argoDomain);
        } else {
            // Read from boot.log
            if (!Files.exists(bootLogPath)) {
                System.out.println("boot.log not found, waiting...");
                Thread.sleep(3000);
            }

            if (Files.exists(bootLogPath)) {
                String content = Files.readString(bootLogPath);
                String[] lines = content.split("\n");

                for (String line : lines) {
                    if (line.contains("trycloudflare.com")) {
                        int start = line.indexOf("https://");
                        if (start == -1) start = line.indexOf("http://");

                        if (start != -1) {
                            int end = line.indexOf("/", start + 8);
                            if (end == -1) end = line.length();

                            String url = line.substring(start, end);
                            argoDomain = url.replace("https://", "").replace("http://", "");

                            // 清理域名：去除空格和特殊字符
                            argoDomain = argoDomain.replaceAll("[\\s\"\\}\\|]+$", "").trim();

                            System.out.println("ArgoDomain: " + argoDomain);
                            generateLinks(argoDomain);
                            return;
                        }
                    }
                }

                System.out.println("ArgoDomain not found, re-running bot");
                // 删除 boot.log 文件，重新运行 cloudflared
                Files.deleteIfExists(bootLogPath);
                processManager.killProcessByName(botName);
                Thread.sleep(3000);
                runCloudflared();
                Thread.sleep(3000);
                extractDomains();
            }
        }
    }

    /**
     * 生成节点链接
     */
    private void generateLinks(String argoDomain) throws Exception {
        // Get ISP info
        String isp = getISPInfo();
        String nodeName = (appConfig.getName() != null && !appConfig.getName().isEmpty())
            ? appConfig.getName() + "-" + isp
            : isp;

        // Generate VMESS
        Map<String, Object> vmess = new HashMap<>();
        vmess.put("v", "2");
        vmess.put("ps", nodeName);
        vmess.put("add", appConfig.getCfip());
        vmess.put("port", appConfig.getCfport());
        vmess.put("id", appConfig.getUuid());
        vmess.put("aid", "0");
        vmess.put("scy", "none");
        vmess.put("net", "ws");
        vmess.put("type", "none");
        vmess.put("host", argoDomain);
        vmess.put("path", appConfig.getVmessPath() + "?ed=2560");
        vmess.put("tls", "tls");
        vmess.put("sni", argoDomain);
        vmess.put("alpn", "");
        vmess.put("fp", "firefox");

        String vmessJson = objectMapper.writeValueAsString(vmess);
        String vmessLink = "vmess://" + Base64.getEncoder().encodeToString(vmessJson.getBytes());

        // URL encode paths for subscription links
        // 路径部分单独编码，然后拼接已编码的查询参数
        String vlessPathEncoded = URLEncoder.encode(appConfig.getVlessPath(), StandardCharsets.UTF_8) + "%3Fed%3D2560";
        String trojanPathEncoded = URLEncoder.encode(appConfig.getTrojanPath(), StandardCharsets.UTF_8) + "%3Fed%3D2560";

        // Generate subscription content (与 Node.js 格式完全一致)
        // 注意：格式必须与 Node.js 一致，包括换行和空格
        String subTxt = String.format("\nvless://%s@%s:%s?encryption=none&security=tls&sni=%s&fp=firefox&type=ws&host=%s&path=%s#%s\n  \n%s\n  \ntrojan://%s@%s:%s?security=tls&sni=%s&fp=firefox&type=ws&host=%s&path=%s#%s\n    ",
            appConfig.getUuid(), appConfig.getCfip(), appConfig.getCfport(), argoDomain, argoDomain, vlessPathEncoded, nodeName,
            vmessLink,
            appConfig.getUuid(), appConfig.getCfip(), appConfig.getCfport(), argoDomain, argoDomain, trojanPathEncoded, nodeName
        );

        // Save to file
        String encodedSub = Base64.getEncoder().encodeToString(subTxt.getBytes());
        Files.writeString(subPath, encodedSub);
        System.out.println(encodedSub);
        System.out.println(appConfig.getFilePath() + "/sub.txt saved successfully");

        // Upload nodes
        uploadNodes();
    }

    /**
     * 获取 ISP 信息
     */
    private String getISPInfo() {
        try {
            // 尝试使用 ipapi.co API
            HttpURLConnection conn = (HttpURLConnection) new URL("https://ipapi.co/json/").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // 简单解析 JSON
                String json = response.toString();
                String countryCode = extractJsonValue(json, "country_code");
                String org = extractJsonValue(json, "org");

                if (countryCode != null && org != null) {
                    return countryCode + "_" + org;
                }
            }
        } catch (Exception e) {
            // 忽略错误，尝试备用 API
        }

        try {
            // 备用: ip-api.com
            HttpURLConnection conn = (HttpURLConnection) new URL("http://ip-api.com/json/").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String json = response.toString();
                String countryCode = extractJsonValue(json, "countryCode");
                String org = extractJsonValue(json, "org");

                if (countryCode != null && org != null) {
                    return countryCode + "_" + org;
                }
            }
        } catch (Exception e) {
            // 忽略错误
        }

        return "Unknown";
    }

    /**
     * 从 JSON 字符串中提取值
     */
    private String extractJsonValue(String json, String key) {
        try {
            String searchKey = "\"" + key + "\":";
            int startIndex = json.indexOf(searchKey);
            if (startIndex == -1) return null;

            startIndex += searchKey.length();
            // 跳过空格和引号
            while (startIndex < json.length() && (json.charAt(startIndex) == ' ' || json.charAt(startIndex) == '"')) {
                startIndex++;
            }

            int endIndex = startIndex;
            // 找到值的结束位置
            while (endIndex < json.length() && json.charAt(endIndex) != ',' && json.charAt(endIndex) != '}' && json.charAt(endIndex) != '"') {
                endIndex++;
            }

            if (startIndex < endIndex) {
                return json.substring(startIndex, endIndex).trim();
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return null;
    }

    /**
     * 上传节点
     */
    private void uploadNodes() {
        // This is a placeholder - implement according to your needs
        System.out.println("Upload nodes functionality not yet implemented");
    }

    /**
     * 清理旧进程：根据 PID 文件杀死所有之前启动的进程
     */
    private void cleanupOldProcessesFromPidFile() {
        if (!Files.exists(pidFilePath)) {
            System.out.println("No PID file found, skipping cleanup");
            return;
        }

        try {
            List<String> pids = Files.readAllLines(pidFilePath);
            System.out.println("Found " + pids.size() + " PIDs to clean up");

            for (String pidStr : pids) {
                try {
                    long pid = Long.parseLong(pidStr.trim());
                    killProcessByPid(pid);
                } catch (NumberFormatException e) {
                    // 忽略无效的 PID
                }
            }

            // 清理完成后删除 PID 文件
            Files.deleteIfExists(pidFilePath);
            System.out.println("Old processes cleaned up");

        } catch (Exception e) {
            System.err.println("Error cleaning up old processes: " + e.getMessage());
        }
    }

    /**
     * 保存进程 PID 到文件
     */
    private void savePid(long pid) {
        try {
            // 追加模式写入 PID
            Files.writeString(pidFilePath, pid + System.lineSeparator(),
                Files.exists(pidFilePath) ?
                    java.nio.file.StandardOpenOption.APPEND :
                    java.nio.file.StandardOpenOption.CREATE);
            System.out.println("Saved PID: " + pid);
        } catch (Exception e) {
            System.err.println("Error saving PID: " + e.getMessage());
        }
    }

    /**
     * 根据 PID 杀死进程
     */
    private void killProcessByPid(long pid) {
        try {
            String command;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                command = "taskkill /F /PID " + pid;
            } else {
                command = "kill -9 " + pid;
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
            process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("Killed process PID: " + pid);

        } catch (Exception e) {
            // 进程可能已经不存在，忽略错误
        }
    }

    /**
     * 检查端口是否被占用
     */
    private boolean isPortInUse(int port) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb.command("cmd.exe", "/c", "netstat -ano | findstr :" + port);
            } else {
                pb.command("sh", "-c", "ss -tlnp 2>/dev/null | grep ':" + port + " ' || netstat -tlnp 2>/dev/null | grep ':" + port + " '");
            }

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);

            return line != null && !line.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 杀死占用指定端口的所有进程
     */
    private void killProcessOnPort(int port) {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                // Windows
                ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c",
                    "for /f \"tokens=5\" %a in ('netstat -ano ^| findstr :" + port + "') do taskkill /F /PID %a");
                pb.start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                // Linux/Unix - 使用多种方法确保杀死所有占用端口的进程
                String[] commands = {
                    "fuser -k " + port + "/tcp 2>/dev/null",
                    "lsof -ti:" + port + " 2>/dev/null | xargs -r kill -9",
                    "ss -tlnp 2>/dev/null | grep ':" + port + " ' | awk '{print $7}' | grep -oP 'pid=\\K[0-9]+' | xargs -r kill -9"
                };

                for (String cmd : commands) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
                        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                        Process process = pb.start();
                        process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                        Thread.sleep(500);
                    } catch (Exception e) {
                        // 尝试下一个命令
                    }
                }
            }
            System.out.println("Killed processes on port " + port);
        } catch (Exception e) {
            System.err.println("Error killing processes on port " + port + ": " + e.getMessage());
        }
    }

    /**
     * 计划文件清理
     */
    private void scheduleFileCleanup() {
        Timer timer = new Timer(true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    List<Path> filesToDelete = Arrays.asList(bootLogPath, configPath, webPath, botPath);

                    if (appConfig.getNezhaPort() != null && !appConfig.getNezhaPort().isEmpty()) {
                        filesToDelete = new ArrayList<>(filesToDelete);
                        filesToDelete.add(npmPath);
                    } else if (appConfig.getNezhaServer() != null && !appConfig.getNezhaServer().isEmpty()) {
                        filesToDelete = new ArrayList<>(filesToDelete);
                        filesToDelete.add(phpPath);
                    }

                    for (Path file : filesToDelete) {
                        Files.deleteIfExists(file);
                    }

                    System.out.println("App is running");
                    System.out.println("Thank you for using this script, enjoy!");
                } catch (Exception e) {
                    System.err.println("Error cleaning up files: " + e.getMessage());
                }
            }
        }, 90000); // 90 seconds
    }

    @PreDestroy
    public void cleanup() {
        System.out.println("Shutting down services...");
        try {
            // 先停止所有已跟踪的进程
            processManager.stopAllProcesses();
            // 强制清理所有tunnel相关进程，确保没有遗漏
            processManager.cleanupAllTunnelProcesses();
            // 给予足够的时间让进程完全终止
            Thread.sleep(2000);
        } catch (Exception e) {
            System.err.println("Error during cleanup: " + e.getMessage());
        }
    }

    /**
     * 文件信息类
     */
    private static class FileInfo {
        Path path;
        String url;

        FileInfo(Path path, String url) {
            this.path = path;
            this.url = url;
        }
    }
}
