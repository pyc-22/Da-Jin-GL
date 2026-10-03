package com.xinchengjinjiang.mobile;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.Locale;

/**
 * 只提供本地声音、通知、系统设置和网络变化桥接，不持有后台 WebSocket。
 * WebView 被冻结后 JS 停跑，本插件没有服务来继续收取消息，也不会唤醒 APP。
 * 电池白名单、悬浮窗、允许后台活动均不等于冻结豁免。
 */
@CapacitorPlugin(name = "DajinMessages", permissions = {
    @Permission(alias = "notifications", strings = {Manifest.permission.POST_NOTIFICATIONS})
})
public class DajinMessagesPlugin extends Plugin {
    private static final String SOUND_CHANNEL = "dajin_messages_sound_v1";
    private static final String SILENT_CHANNEL = "dajin_messages_silent_v1";
    private static final String TAG = "dajin-messages";
    private static final String OWNER_EXTRA = "dajin_notification_owner";
    private static final int NOTIFICATION_ID = 7101;
    private volatile String owner = "";
    private MediaPlayer player;
    private long lastSoundAt;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String lastNetworkState = "";

    @Override public void load() {
        createChannels();
        connectivity = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { emitNetwork(); }
            @Override public void onLost(Network network) { emitNetwork(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { emitNetwork(); }
        };
        try { connectivity.registerDefaultNetworkCallback(networkCallback); } catch (Exception ignored) { }
        consumeIntent(getActivity().getIntent());
    }

    private NotificationManager manager() {
        return (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
    }
    private Uri soundUri() { return Uri.parse("android.resource://" + getContext().getPackageName() + "/raw/new_message"); }
    private AudioAttributes audioAttributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    }
    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel audible = new NotificationChannel(SOUND_CHANNEL, "新消息（有声）", NotificationManager.IMPORTANCE_HIGH);
        audible.setDescription("消息中心新增通知，声音遵循系统通知音量和勿扰设置");
        audible.setSound(soundUri(), audioAttributes()); audible.enableVibration(false);
        NotificationChannel silent = new NotificationChannel(SILENT_CHANNEL, "新消息（静音）", NotificationManager.IMPORTANCE_HIGH);
        silent.setSound(null, null); silent.enableVibration(false);
        manager().createNotificationChannel(audible); manager().createNotificationChannel(silent);
        // Android 通道创建后由用户掌握声音设置；不通过删除重建通道覆盖用户选择。
    }

    @PluginMethod public void setOwner(PluginCall call) {
        String next = call.getString("owner", "");
        if (!owner.equals(next) || next.isEmpty()) { manager().cancel(TAG, NOTIFICATION_ID); releasePlayer(); }
        owner = next; call.resolve();
    }
    private boolean currentOwner(PluginCall call) {
        return !owner.isEmpty() && owner.equals(call.getString("owner", ""));
    }
    private JSObject settings() {
        JSObject data = new JSObject();
        PowerManager power = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        String permission = Build.VERSION.SDK_INT < 33 ? "granted" : getPermissionState("notifications").toString();
        data.put("permission", permission);
        data.put("notificationsEnabled", NotificationManagerCompat.from(getContext()).areNotificationsEnabled());
        data.put("batteryOptimizationIgnored", power.isIgnoringBatteryOptimizations(getContext().getPackageName()));
        data.put("overlayAllowed", Settings.canDrawOverlays(getContext()));
        data.put("manufacturer", Build.MANUFACTURER); data.put("sdkInt", Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager().getNotificationChannel(SOUND_CHANNEL);
            data.put("soundChannelEnabled", channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
            data.put("soundChannelAudible", channel != null && channel.getSound() != null);
        }
        return data;
    }
    @PluginMethod public void getSettings(PluginCall call) { call.resolve(settings()); }
    @PluginMethod public void requestNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED) call.resolve(settings());
        else requestPermissionForAlias("notifications", call, "notificationPermissionResult");
    }
    @PermissionCallback private void notificationPermissionResult(PluginCall call) { call.resolve(settings()); }

    @PluginMethod public void playSound(PluginCall call) {
        JSObject result = new JSObject(); result.put("played", false);
        if (!currentOwner(call)) { result.put("reason", "account-changed"); call.resolve(result); return; }
        AudioManager audio = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        // 前台也尊重静音、勿扰、系统通知开关和通知音量，不切换到媒体/闹钟通道强行发声。
        if (!NotificationManagerCompat.from(getContext()).areNotificationsEnabled()
            || audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL
            || audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0
            || manager().getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL) {
            result.put("reason", "system-muted"); call.resolve(result); return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager().getNotificationChannel(SOUND_CHANNEL);
            if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE || channel.getSound() == null) {
                result.put("reason", "channel-muted"); call.resolve(result); return;
            }
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastSoundAt < 900) { result.put("reason", "coalesced"); call.resolve(result); return; }
        try {
            releasePlayer();
            player = MediaPlayer.create(getContext(), R.raw.new_message, audioAttributes(), audio.generateAudioSessionId());
            if (player == null) throw new IllegalStateException("sound unavailable");
            player.setOnCompletionListener(completed -> { if (player == completed) releasePlayer(); });
            player.setOnErrorListener((failed, what, extra) -> { if (player == failed) releasePlayer(); return true; });
            player.start(); lastSoundAt = now; result.put("played", true); call.resolve(result);
        } catch (Exception error) { releasePlayer(); call.reject("提示音播放失败", error); }
    }
    private void releasePlayer() {
        if (player != null) { try { player.release(); } catch (Exception ignored) { } player = null; }
    }

    @PluginMethod public void showNotification(PluginCall call) {
        JSObject result = new JSObject(); result.put("delivered", false);
        if (!currentOwner(call)) { result.put("reason", "account-changed"); call.resolve(result); return; }
        if (!NotificationManagerCompat.from(getContext()).areNotificationsEnabled()) { call.resolve(result); return; }
        boolean sound = Boolean.TRUE.equals(call.getBoolean("sound", true));
        String channelId = sound ? SOUND_CHANNEL : SILENT_CHANNEL;
        if (Build.VERSION.SDK_INT >= 26 && manager().getNotificationChannel(channelId).getImportance() == NotificationManager.IMPORTANCE_NONE) {
            call.resolve(result); return;
        }
        int count = Math.max(1, call.getInt("count", 1));
        Intent intent = new Intent(getContext(), MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(OWNER_EXTRA, owner);
        PendingIntent tap = PendingIntent.getActivity(getContext(), NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(getContext(), channelId)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle("打金店 · 新消息")
            .setContentText("收到 " + count + " 条新消息，点击进入消息页")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true)
            .setContentIntent(tap);
        if (Build.VERSION.SDK_INT < 26) builder.setSound(sound ? soundUri() : null);
        if (!sound) builder.setSilent(true);
        try { manager().notify(TAG, NOTIFICATION_ID, builder.build()); result.put("delivered", true); call.resolve(result); }
        catch (SecurityException error) { call.resolve(result); }
    }
    private void consumeIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(OWNER_EXTRA)) return;
        JSObject data = new JSObject(); data.put("owner", intent.getStringExtra(OWNER_EXTRA));
        intent.removeExtra(OWNER_EXTRA);
        // 冷启动时保留事件，等 JS 完成登录态恢复和注册监听后消费。
        notifyListeners("notificationAction", data, true);
    }
    @Override protected void handleOnNewIntent(Intent intent) { consumeIntent(intent); }
    private void emitNetwork() {
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null : connectivity.getNetworkCapabilities(active);
        boolean connected = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        String state = String.valueOf(active) + ":" + connected;
        if (state.equals(lastNetworkState)) return;
        lastNetworkState = state;
        JSObject data = new JSObject();
        data.put("connected", connected);
        notifyListeners("networkStatusChange", data);
    }

    /** 没有通用的 Android“后台运行”运行时权限；小米/vivo 的开关由用户在设置中决定。 */
    @PluginMethod public void openSettings(PluginCall call) {
        String kind = call.getString("kind", "notifications"), pkg = getContext().getPackageName();
        Intent intent;
        switch (kind) {
            case "battery":
                intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)); break;
            case "batteryList":
                intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS); break;
            case "overlay":
                // 可选高级入口。悬浮窗不是接收消息的必要条件，也不提供保活保证。
                intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + pkg)); break;
            case "background":
                String brand = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
                if (brand.contains("xiaomi") || brand.contains("redmi")) {
                    intent = new Intent().setComponent(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                } else if (brand.contains("vivo") || brand.contains("iqoo")) {
                    intent = new Intent().setComponent(new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
                } else intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
                break;
            default:
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
        }
        try { getActivity().startActivity(intent); call.resolve(); }
        catch (Exception unavailable) {
            try { getActivity().startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))); call.resolve(); }
            catch (Exception error) { call.reject("请手动进入手机设置中的应用管理", error); }
        }
    }
    @Override protected void handleOnDestroy() {
        releasePlayer();
        if (connectivity != null && networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) { }
        }
    }
}
