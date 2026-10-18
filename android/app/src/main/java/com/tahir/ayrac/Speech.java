package com.tahir.ayrac;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/** Android's text-to-speech behind the page's speechSynthesis polyfill (android-bridge.js). */
final class Speech {
    interface Events {
        /** type: "start", "boundary" (with the character index), "end", "error", "canceled" */
        void onSpeech(String id, String type, int charIndex);
    }

    private final TextToSpeech tts;
    private final Events events;
    private volatile boolean ready;

    Speech(Context context, Events events) {
        this.events = events;
        tts = new TextToSpeech(context, status -> ready = status == TextToSpeech.SUCCESS);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { events.onSpeech(id, "start", 0); }
            @Override public void onDone(String id) { events.onSpeech(id, "end", 0); }
            @Override @SuppressWarnings("deprecation") public void onError(String id) { events.onSpeech(id, "error", 0); }
            @Override public void onError(String id, int code) { events.onSpeech(id, "error", 0); }
            @Override public void onStop(String id, boolean interrupted) { events.onSpeech(id, "canceled", 0); }
            @Override public void onRangeStart(String id, int start, int end, int frame) { events.onSpeech(id, "boundary", start); }
        });
    }

    void speak(String id, String text, String lang, float rate) {
        if (!ready) { events.onSpeech(id, "error", 0); return; }
        Locale locale = Locale.forLanguageTag(lang == null || lang.isEmpty() ? "en-US" : lang);
        if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.setLanguage(locale);
        tts.setSpeechRate(rate > 0 ? rate : 1f);
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id);
    }

    void stop() {
        tts.stop();
    }

    void shutdown() {
        tts.shutdown();
    }
}
