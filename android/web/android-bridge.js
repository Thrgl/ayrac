// Ayraç Android bridge: gives ayrac.html the same window.pywebview.api the desktop app has.
// Every call goes to MainActivity.Native.call(id, method, argsJson); Java answers with window.__ayracResolve(id, ok, value).
(() => {
  'use strict';
  const pending = new Map();
  let seq = 0;
  window.__ayracResolve = (id, ok, value) => {
    const p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    if (ok) p.resolve(value); else p.reject(new Error(value || 'native error'));
  };
  const api = new Proxy({}, {
    get: (_, name) => (...args) => new Promise((resolve, reject) => {
      const id = String(++seq);
      pending.set(id, { resolve, reject });
      AyracNative.call(id, String(name), JSON.stringify(args.map((a) => (a === undefined ? null : a))));
    }),
  });
  window.pywebview = { api };
  setTimeout(() => window.dispatchEvent(new Event('pywebviewready')), 0);

  // Android's WebView has no working speechSynthesis (some versions expose one that never speaks),
  // so always replace it with one that speaks through Android's text-to-speech.
  {
    const live = new Map();
    let n = 0;
    class Utterance {
      constructor(text) { this.text = text || ''; this.lang = 'en-US'; this.rate = 1; this.voice = null; this.onend = this.onerror = this.onboundary = this.onstart = null; }
    }
    Object.defineProperty(window, 'SpeechSynthesisUtterance', { value: Utterance, configurable: true, writable: true });
    Object.defineProperty(window, 'speechSynthesis', { configurable: true, value: {
      speak(u) { const id = 'u' + (++n); live.set(id, u); AyracNative.speak(id, u.text, u.lang || 'en-US', Number(u.rate) || 1); },
      cancel() {
        AyracNative.stopSpeaking();
        const all = [...live.values()]; live.clear();
        all.forEach((u) => u.onerror && u.onerror({ error: 'canceled' }));
      },
      getVoices() { return []; },
      addEventListener() {},
      get speaking() { return live.size > 0; },
    } });
    window.__ayracTts = (id, type, at) => {
      const u = live.get(id);
      if (!u) return;
      if (type === 'start') { u.onstart && u.onstart({}); return; }
      if (type === 'boundary') { u.onboundary && u.onboundary({ name: 'word', charIndex: at }); return; }
      live.delete(id);
      if (type === 'end') u.onend && u.onend({});
      else u.onerror && u.onerror({ error: type === 'canceled' ? 'canceled' : 'synthesis-failed' });
    };
  }
})();
