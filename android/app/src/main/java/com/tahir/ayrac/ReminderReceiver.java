package com.tahir.ayrac;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Daily reminder (Ayarlar → Günlük hatırlatma): an inexact daily alarm that posts "12 kelime tekrar bekliyor ·
 * bugün 0 / 15 dk okudun · 7 günlük serin bozulmasın", computed from the notebook and reading stats the same way
 * the page does. Nothing is shown when nothing is due and today's goal is reached. Re-armed after a reboot or an
 * app update.
 */
public final class ReminderReceiver extends BroadcastReceiver {
    static final String ACTION = "com.tahir.ayrac.REMIND";
    private static final String PREFS = "ayrac-reminder", CHANNEL = "reminder";
    private static final long MIN = 60_000L, DAY = 86_400_000L;

    /** Saves the setting and (re)arms or cancels the alarm. time is "HH:MM". */
    static void configure(Context ctx, boolean on, String time) {
        int[] hm = parseTime(time);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("on", on).putInt("hour", hm[0]).putInt("minute", hm[1]).apply();
        arm(ctx);
    }

    static boolean isOn(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false);
    }

    private static int[] parseTime(String t) {
        int h = 20, m = 0;
        try {
            String[] p = String.valueOf(t).split(":");
            h = Math.max(0, Math.min(23, Integer.parseInt(p[0].trim())));
            m = Math.max(0, Math.min(59, Integer.parseInt(p[1].trim())));
        } catch (RuntimeException ignored) {
        }
        return new int[]{h, m};
    }

    private static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ctx, ReminderReceiver.class).setAction(ACTION);
        return PendingIntent.getBroadcast(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Next alarm at the chosen time (today if still ahead, else tomorrow); inexact, so no exact-alarm permission. */
    private static void arm(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = alarmIntent(ctx);
        am.cancel(pi);
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean("on", false)) return;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, sp.getInt("hour", 20));
        c.set(Calendar.MINUTE, sp.getInt("minute", 0));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + MIN) c.add(Calendar.DAY_OF_YEAR, 1);
        am.setWindow(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), 10 * MIN, pi);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (ACTION.equals(a)) {
            try {
                String text = message(ctx);
                if (text != null) show(ctx, text);
            } catch (Exception ignored) {
            }
        }
        arm(ctx);  // after the reminder, a reboot (BOOT_COMPLETED) or an update (MY_PACKAGE_REPLACED)
    }

    private static String dayKey(long t) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(t);
    }

    private static boolean activeDay(JSONObject d) {
        return d != null && (d.optLong("read") >= 5 * MIN || d.optLong("rev") >= 10);
    }

    /** The reminder text, or null when there is nothing to remind about. Same counting as ayrac.html. */
    static String message(Context ctx) throws Exception {
        Store store = new Store(new File(ctx.getFilesDir(), "Ayrac"));
        JSONObject all = store.kvAll();
        JSONArray words = all.optJSONArray("words");
        JSONObject stats = all.optJSONObject("stats"), prefs = all.optJSONObject("prefs");
        if (stats == null) stats = new JSONObject();
        if (prefs == null) prefs = new JSONObject();
        long now = System.currentTimeMillis();
        String today = dayKey(now);
        JSONObject td = stats.optJSONObject(today);
        int due = 0, fresh = 0;
        for (int i = 0; words != null && i < words.length(); i++) {
            JSONObject w = words.optJSONObject(i);
            if (w == null || w.optLong("due", 0) > now) continue;
            int reps = w.has("reps") ? w.optInt("reps") : w.optInt("reviews");
            if (reps > 0) due++; else fresh++;
        }
        String perDay = prefs.optString("newPerDay", "20");
        int newLeft = "all".equals(perDay) ? fresh : Math.max(0, Math.min(fresh, parseInt(perDay, 20) - (td == null ? 0 : td.optInt("new"))));
        int cards = due + newLeft;
        int goal = parseInt(prefs.optString("goal", "15"), 15), mins = (int) ((td == null ? 0 : td.optLong("read")) / MIN);
        int streak = 0;
        long t = now;
        if (!activeDay(td)) t -= DAY;  // today still counts until midnight
        while (activeDay(stats.optJSONObject(dayKey(t)))) { streak++; t -= DAY; }
        if (cards == 0 && mins >= goal) return null;
        List<String> parts = new ArrayList<>();
        if (cards > 0) parts.add(cards + " kelime tekrar bekliyor");
        if (mins < goal) parts.add("bugün " + mins + " / " + goal + " dk okudun");
        if (streak > 0 && !activeDay(td)) parts.add(streak + " günlük serin bozulmasın");
        return String.join(" · ", parts);
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static void show(Context ctx, String text) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Günlük hatırlatma", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_ayrac)
                .setContentTitle("Ayraç")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(1, n);
    }
}
