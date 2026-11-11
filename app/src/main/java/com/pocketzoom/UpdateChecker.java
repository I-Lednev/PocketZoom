package com.pocketzoom;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Checks GitHub Releases for a newer Pocket Zoom build and downloads its APK.
 * Android always asks the user before installing an update; this class only
 * prepares the file and opens the system package installer.
 */
final class UpdateChecker {
    private static final String TAG = "PocketZoom";
    private static final String REPO = "I-Lednev/PocketZoom";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String RELEASES = "https://github.com/" + REPO + "/releases/latest";
    private static final String USER_AGENT = "PocketZoom-Android";
    private static final int TIMEOUT_MS = 20000;

    interface Listener {
        void onCheckResult(UpdateInfo info, String error);

        void onDownloadProgress(int percent);

        void onDownloadResult(File apk, String error);
    }

    static final class UpdateInfo {
        final String tag;
        final String version;
        final String notes;
        final String apkUrl;
        final String assetName;

        UpdateInfo(String tag, String version, String notes, String apkUrl, String assetName) {
            this.tag = tag;
            this.version = version;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.assetName = assetName;
        }
    }

    private UpdateChecker() {
    }

    static String currentVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.0";
        }
    }

    static String releasesUrl() {
        return RELEASES;
    }

    static void check(Context context, Listener listener) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            UpdateInfo info = null;
            String error = null;
            HttpURLConnection connection = null;
            try {
                connection = open(API);
                int code = connection.getResponseCode();
                String body = readAll(code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream());
                if (code == 404) {
                    error = "релизы ещё не опубликованы (HTTP 404)";
                } else if (code < 200 || code >= 300) {
                    error = "GitHub HTTP " + code + apiMessage(body);
                } else {
                    JSONObject json = new JSONObject(body);
                    String tag = json.optString("tag_name", "").trim();
                    String version = normalize(tag);
                    String notes = json.optString("body", "");
                    String url = null;
                    String name = null;
                    JSONArray assets = json.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.optJSONObject(i);
                            if (asset == null) {
                                continue;
                            }
                            String assetName = asset.optString("name", "");
                            String download = asset.optString("browser_download_url", "");
                            if (assetName.toLowerCase().endsWith(".apk") && !download.isEmpty()) {
                                name = assetName;
                                url = download;
                                break;
                            }
                        }
                    }
                    if (url == null) {
                        error = "в последнем релизе нет APK";
                    } else if (isNewer(version, currentVersion(app))) {
                        info = new UpdateInfo(tag, version, notes, url, name);
                    }
                }
            } catch (Throwable t) {
                error = friendly(t);
                Log.w(TAG, "update check failed", t);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            final UpdateInfo result = info;
            final String failure = error;
            main.post(() -> listener.onCheckResult(result, failure));
        }, "pocketzoom-update-check").start();
    }

    static void download(Context context, UpdateInfo info, Listener listener) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File result = null;
            String error = null;
            HttpURLConnection connection = null;
            FileOutputStream output = null;
            try {
                File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) {
                    dir = app.getCacheDir();
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("не удалось создать папку загрузок");
                }
                String safeName = info.assetName == null ? "" : info.assetName
                        .replaceAll("[^A-Za-z0-9._-]", "_");
                if (!safeName.toLowerCase().endsWith(".apk")) {
                    safeName = "PocketZoom-" + info.version + ".apk";
                }
                File target = new File(dir, safeName);
                File part = new File(dir, safeName + ".part");

                connection = open(info.apkUrl);
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code);
                }
                int total = connection.getContentLength();
                InputStream input = new BufferedInputStream(connection.getInputStream());
                output = new FileOutputStream(part);
                byte[] buffer = new byte[8192];
                long received = 0L;
                int lastPercent = -1;
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                    received += read;
                    if (total > 0) {
                        int percent = (int) (received * 100L / total);
                        if (percent != lastPercent && (percent % 2 == 0 || percent == 100)) {
                            lastPercent = percent;
                            int shown = percent;
                            main.post(() -> listener.onDownloadProgress(shown));
                        }
                    }
                }
                output.flush();
                output.close();
                output = null;
                input.close();

                if (target.exists() && !target.delete()) {
                    throw new IOException("не удалось заменить старый файл");
                }
                if (!part.renameTo(target)) {
                    throw new IOException("не удалось сохранить APK");
                }
                result = target;
            } catch (Throwable t) {
                error = friendly(t);
                Log.w(TAG, "update download failed", t);
            } finally {
                if (output != null) {
                    try {
                        output.close();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
                if (connection != null) {
                    connection.disconnect();
                }
            }
            final File file = result;
            final String failure = error;
            main.post(() -> listener.onDownloadResult(file, failure));
        }, "pocketzoom-update-download").start();
    }

    static boolean canInstall(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        try {
            return context.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return false;
        }
    }

    static void openInstallPermission(Context context) {
        Intent intent;
        try {
            intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
        } catch (Throwable t) {
            intent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Throwable t) {
            try {
                context.startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
                // nothing else we can do
            }
        }
    }

    /** Opens the system installer for a downloaded APK. */
    static boolean launchInstaller(Context context, File apk) {
        try {
            Uri uri = ApkProvider.uriFor(apk.getName());
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "installer failed", t);
            return false;
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        return connection;
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }

    private static String apiMessage(String body) {
        try {
            JSONObject json = new JSONObject(body);
            String message = json.optString("message", "");
            return message.isEmpty() ? "" : ": " + message;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String friendly(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            return "ошибка сети";
        }
        return message;
    }

    private static String normalize(String tag) {
        String value = tag == null ? "" : tag.trim();
        while (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        return value.isEmpty() ? "0" : value;
    }

    private static boolean isNewer(String remote, String current) {
        int[] left = numbers(remote);
        int[] right = numbers(current);
        int count = Math.max(left.length, right.length);
        for (int i = 0; i < count; i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    private static int[] numbers(String version) {
        String[] parts = version.split("[^0-9]+");
        int count = 0;
        for (String part : parts) {
            if (!part.isEmpty()) {
                count++;
            }
        }
        int[] result = new int[count];
        int index = 0;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                result[index++] = Integer.parseInt(part);
            } catch (Throwable t) {
                result[index++] = 0;
            }
        }
        return result;
    }
}
