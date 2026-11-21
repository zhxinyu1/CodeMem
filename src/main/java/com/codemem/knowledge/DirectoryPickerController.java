package com.codemem.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 在用户本机弹出系统「选择文件夹」对话框，返回选中的绝对路径。
 *
 * 浏览器出于安全限制拿不到本地目录的完整路径（webkitdirectory 只给相对路径），
 * 所以这一步交给跑在同一台机器上的后端进程去做：后端调 Windows 自带的
 * Shell 文件夹选择框，把结果回给前端填进输入框。
 *
 * 注意：Spring Boot 默认 headless=true，AWT/Swing 弹窗不可用，所以这里走
 * PowerShell + System.Windows.Forms，独立进程，不受 headless 影响。
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class DirectoryPickerController {

    private static final Logger log = LoggerFactory.getLogger(DirectoryPickerController.class);
    private static final Object LOCK = new Object();
    private static final long TIMEOUT_SECONDS = 300;

    @PostMapping("/pick-directory")
    public ResponseEntity<Map<String, Object>> pick(@RequestBody(required = false) Map<String, String> body) {
        if (!isWindows()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "cancelled", true,
                    "reason", "当前系统不支持弹窗选择目录，请手动填写路径"
            ));
        }
        String initialPath = body == null ? null : body.get("initialPath");
        synchronized (LOCK) {
            return pickLocked(initialPath);
        }
    }

    private ResponseEntity<Map<String, Object>> pickLocked(String initialPath) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "powershell",
                    "-NoProfile",
                    "-STA",
                    "-ExecutionPolicy", "Bypass",
                    "-Command", buildScript(initialPath)
            ).redirectErrorStream(false).start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(line);
                }
                output = builder.toString().trim();
            }

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return ResponseEntity.status(408).body(Map.of(
                        "cancelled", true,
                        "reason", "等待选择超时，请重试或手动填写路径"
                ));
            }

            if (output.isEmpty()) {
                return ResponseEntity.ok(Map.of("cancelled", true));
            }
            return ResponseEntity.ok(Map.of("cancelled", false, "path", output));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).body(Map.of("cancelled", true, "reason", "选择过程被中断"));
        } catch (Exception ex) {
            log.warn("Unable to open folder picker: {}", ex.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "cancelled", true,
                    "reason", "无法打开系统文件夹选择窗口：" + ex.getMessage()
            ));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String buildScript(String initialPath) {
        StringBuilder script = new StringBuilder();
        script.append("[Console]::OutputEncoding=[System.Text.Encoding]::UTF8\n");
        script.append("Add-Type -AssemblyName System.Windows.Forms | Out-Null\n");
        script.append("$dialog = New-Object System.Windows.Forms.FolderBrowserDialog\n");
        script.append("$dialog.Description = '\u9009\u62e9\u7b14\u8bb0\u5e93\u76ee\u5f55'\n");
        script.append("$dialog.ShowNewFolderButton = $true\n");
        if (initialPath != null && !initialPath.isBlank()) {
            // PowerShell 单引号串里反斜杠不是转义符，只有单引号要写成两个单引号
            script.append("$dialog.SelectedPath = '")
                    .append(initialPath.replace("'", "''"))
                    .append("'\n");
        }
        script.append("$result = $dialog.ShowDialog()\n");
        script.append("if ($result -eq [System.Windows.Forms.DialogResult]::OK) { [Console]::Out.Write($dialog.SelectedPath) }\n");
        return script.toString();
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("win");
    }
}