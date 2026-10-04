package cc.ccwu.apptuner;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

/** Bounded root session, with the exit status of every action checked. */
public final class RootShell implements AutoCloseable {
    private final Process process;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final ExecutorService reads = Executors.newSingleThreadExecutor();
    public RootShell() throws Exception {
        try { process = new ProcessBuilder("su").redirectErrorStream(true).start(); }
        catch (IOException e) { throw new IOException("Root 不可用，请在 KernelSU 授权后重新启动轻调", e); }
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        try { if (!exec("id -u").trim().equals("0")) throw new IOException("请在 KernelSU 授予轻调 Root 权限"); }
        catch (Exception e) { close(); throw e; }
    }
    public synchronized String exec(String command) throws Exception {
        String marker = "END_" + UUID.randomUUID().toString().replace("-", "") + ":";
        writer.write(command + "\nqt_result=$?\nprintf '\\n" + marker + "%s\\n' \"$qt_result\"\n"); writer.flush();
        Future<String[]> future = reads.submit(() -> {
            StringBuilder out = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(marker)) return new String[]{line.substring(marker.length()), out.toString().trim()};
                out.append(line).append('\n');
                if (out.length() > 65536) throw new IOException("Root 输出超出限制");
            }
            throw new IOException("Root 会话已结束");
        });
        String[] result;
        try { result = future.get(12, TimeUnit.SECONDS); }
        catch (Exception e) { future.cancel(true); close(); throw new IOException("Root 执行失败或超过 12 秒，试用配置仍会自动回滚", e); }
        if (!result[0].equals("0")) throw new IOException(result[1].isEmpty() ? "Root 操作失败" : result[1]);
        return result[1];
    }
    @Override public void close() { process.destroy(); reads.shutdownNow(); }
}
