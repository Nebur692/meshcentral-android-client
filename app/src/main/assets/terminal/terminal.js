// Bridge between xterm.js and the app (androidx.webkit web messages, JSON strings). The page only draws and passes keys:
// Ctrl, Alt, the key bar, copy and paste are the app's. / Puente entre xterm.js y la app (mensajes web de androidx.webkit,
// cadenas JSON). La página solo pinta y pasa las teclas: Ctrl, Alt, la barra de teclas, copiar y pegar son de la app.
(function () {
  'use strict';
  const host = window.app;
  const send = (m) => { if (host) host.postMessage(JSON.stringify(m)); };
  // A mistake in this page would leave the app waiting with nothing said: it goes to the app's log.
  // Un fallo de esta página dejaría a la app esperando sin decir nada: va al registro de la app.
  window.addEventListener('error', (e) => send({ t: 'error', text: String(e.message) + ' @' + e.lineno }));
  const themes = {
    dark: { background: '#1e1e1e', foreground: '#d4d4d4', cursor: '#d4d4d4', selectionBackground: '#264f78' },
    light: { background: '#ffffff', foreground: '#1e1e1e', cursor: '#1e1e1e', selectionBackground: '#add6ff' },
  };
  const params = new URLSearchParams(location.search);
  const dark = params.get('dark') === '1';
  document.documentElement.classList.toggle('dark', dark);
  const term = new Terminal({
    cursorBlink: true, scrollback: 5000, allowProposedApi: false,
    // Pasted text goes as it is, never wrapped in bracketed-paste marks: the agent's own Linux terminal runs with
    // TERM=dumb and its bash then printed the marks ('[200~text~', seen on Debian 12); several lines are asked first.
    // Lo pegado va tal cual, sin las marcas de pegado entre corchetes: la terminal del agente en Linux corre con
    // TERM=dumb y su bash escribía las marcas ('[200~texto~', visto en Debian 12); varias líneas se preguntan antes.
    ignoreBracketedPasteMode: true,
    screenReaderMode: params.get('reader') === '1',
    fontFamily: 'monospace', fontSize: Number(params.get('font')) || 13,
    theme: dark ? themes.dark : themes.light,
  });
  const fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(document.getElementById('term'));
  fit.fit();

  // A phone keyboard would correct and capitalise what goes to a shell. / Un teclado de móvil corregiría y pondría mayúsculas a lo que va a una shell.
  const input = document.querySelector('.xterm-helper-textarea');
  if (input) {
    for (const [k, v] of [['autocapitalize', 'off'], ['autocorrect', 'off'], ['autocomplete', 'off'], ['spellcheck', 'false']]) input.setAttribute(k, v);
  }

  const b64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
  term.onData((d) => send({ t: 'input', d }));
  term.onTitleChange((title) => send({ t: 'title', title }));
  let timer = null;
  term.onResize(({ cols, rows }) => { clearTimeout(timer); timer = setTimeout(() => send({ t: 'size', cols, rows }), 150); });
  window.addEventListener('resize', () => fit.fit());

  // A tap focuses the terminal, which brings up the keyboard: in a WebView xterm.js does not do it from a touch (seen on
  // Android 17). A drag (scrolling back) does not. / Un toque enfoca el terminal y saca el teclado: en un WebView xterm.js
  // no lo hace con un toque (visto en Android 17). Un arrastre (para ver lo de atrás) no.
  let start = null;
  const area = document.getElementById('term');

  // Selecting with the finger, as Android's text fields: a long press picks the word under it (or the whole line's
  // text), dragging without lifting stretches it, and on lifting the app shows Android's own Copy / Paste / Select all
  // bar there. A long press on nothing offers Paste. xterm.js has no touch selection of its own.
  // Seleccionar con el dedo, como los campos de texto de Android: una pulsación larga elige la palabra de debajo,
  // arrastrar sin levantar la estira, y al levantar la app enseña ahí la barra de Copiar / Pegar / Seleccionar todo del
  // propio Android. Una pulsación larga sobre nada ofrece Pegar. xterm.js no tiene selección táctil propia.
  const screenEl = document.querySelector('.xterm-screen');
  const cellAt = (x, y) => {
    const r = screenEl.getBoundingClientRect();
    const col = Math.max(0, Math.min(term.cols - 1, Math.floor((x - r.left) / (r.width / term.cols))));
    const row = Math.max(0, Math.min(term.rows - 1, Math.floor((y - r.top) / (r.height / term.rows))));
    return { col, row: row + term.buffer.active.viewportY };
  };
  const isWord = (ch) => !!ch && !/\s/.test(ch);
  let press = null;      // the pending long press / la pulsación larga pendiente
  let anchor = null;     // where a selection being dragged started / dónde empezó la selección que se arrastra
  const linear = (c) => c.row * term.cols + c.col;
  const selectSpan = (a, b) => {
    const [s, e] = linear(a) <= linear(b) ? [a, b] : [b, a];
    term.select(s.col, s.row, linear(e) - linear(s) + 1);
  };
  const report = (x, y) => send({ t: 'selection', text: term.getSelection(), x, y });
  area.addEventListener('touchstart', (e) => {
    start = e.touches.length === 1 ? { x: e.touches[0].clientX, y: e.touches[0].clientY } : null;
    clearTimeout(press && press.timer);
    press = null;
    if (!start) return;
    const at = { x: start.x, y: start.y };
    press = { timer: setTimeout(() => {
      const c = cellAt(at.x, at.y);
      const line = term.buffer.active.getLine(c.row);
      const text = line ? line.translateToString(false) : '';
      if (isWord(text[c.col])) {
        let s = c.col, e2 = c.col;
        while (s > 0 && isWord(text[s - 1])) s--;
        while (e2 < text.length - 1 && isWord(text[e2 + 1])) e2++;
        term.select(s, c.row, e2 - s + 1);
        anchor = { col: s, row: c.row };
        press.end = { col: e2, row: c.row };
      } else {
        term.clearSelection();
        anchor = null;
      }
      press.fired = true;
      if (navigator.vibrate) navigator.vibrate(20);
    }, 450) };
  }, { passive: true });
  // While a selection is dragged the page must not scroll: this one listener is not passive. / Mientras se arrastra no se desplaza.
  area.addEventListener('touchmove', (e) => {
    const t = e.touches[0];
    if (!press || !t) return;
    if (!press.fired) {
      if (start && Math.hypot(t.clientX - start.x, t.clientY - start.y) >= 12) { clearTimeout(press.timer); press = null; }
      return;
    }
    if (anchor) { e.preventDefault(); selectSpan(anchor, cellAt(t.clientX, t.clientY)); }
  }, { passive: false });
  area.addEventListener('touchend', (e) => {
    const t = e.changedTouches[0];
    if (press && press.fired) {
      clearTimeout(press.timer);
      press = null;
      if (t) report(t.clientX, t.clientY);
      start = null;
      return;
    }
    if (press) { clearTimeout(press.timer); press = null; }
    if (start && t && Math.hypot(t.clientX - start.x, t.clientY - start.y) < 12) {
      // A tap drops a selection; with none, it brings the keyboard. / Un toque quita la selección; sin ella, saca el teclado.
      if (term.hasSelection()) { term.clearSelection(); send({ t: 'selection-gone' }); } else term.focus();
    }
    start = null;
  }, { passive: true });

  // A hardware keyboard copies and pastes as desktop terminals do: Ctrl+Shift+C / Ctrl+Shift+V, Ctrl+Insert /
  // Shift+Insert, and Ctrl+C copies only while something is selected (else it is the interrupt). On Windows devices
  // Ctrl+V pastes too, as in Windows Terminal; elsewhere it stays the shell's ^V. The phone's clipboard is the app's.
  // Un teclado físico copia y pega como los terminales de escritorio; en equipos Windows Ctrl+V también pega, como en
  // Windows Terminal; en los demás sigue siendo el ^V de la shell. El portapapeles del móvil es cosa de la app.
  const windowsKeys = params.get('win') === '1';
  term.attachCustomKeyEventHandler((e) => {
    if (e.type !== 'keydown') return true;
    const k = e.key.toLowerCase();
    // The WebView would also do its own copy or paste of the same keys (a paste came twice): it is stopped here.
    // El WebView también haría su propio copiar o pegar con las mismas teclas (se pegaba dos veces): se para aquí.
    const mine = () => { e.preventDefault(); e.stopPropagation(); return false; };
    const copy = () => { const s = term.getSelection(); if (s) send({ t: 'copy', text: s }); term.clearSelection(); return mine(); };
    if (e.ctrlKey && e.shiftKey && k === 'c') return copy();
    if (e.ctrlKey && !e.shiftKey && k === 'insert') return copy();
    if (e.ctrlKey && !e.shiftKey && !e.altKey && k === 'c' && term.hasSelection()) return copy();
    if ((e.ctrlKey && e.shiftKey && k === 'v') || (e.shiftKey && !e.ctrlKey && k === 'insert') ||
        (windowsKeys && e.ctrlKey && !e.shiftKey && !e.altKey && k === 'v')) { send({ t: 'paste-request' }); return mine(); }
    return true;
  });

  // Two fingers change the size of the letters. / Dos dedos cambian el tamaño de la letra.
  let pinch = null;
  const gap = (e) => Math.hypot(e.touches[0].clientX - e.touches[1].clientX, e.touches[0].clientY - e.touches[1].clientY);
  document.addEventListener('touchstart', (e) => { if (e.touches.length === 2) pinch = { d: gap(e), size: term.options.fontSize }; }, { passive: true });
  document.addEventListener('touchmove', (e) => {
    if (!pinch || e.touches.length !== 2) return;
    const size = Math.max(8, Math.min(28, Math.round(pinch.size * gap(e) / pinch.d)));
    if (size !== term.options.fontSize) { term.options.fontSize = size; fit.fit(); }
  }, { passive: true });
  document.addEventListener('touchend', () => { if (pinch) { pinch = null; send({ t: 'font', size: term.options.fontSize }); } }, { passive: true });

  // Everything on screen and in the history, for the app's own selection (xterm.js cannot select by touch).
  // Todo lo de la pantalla y el historial, para la selección propia de la app (xterm.js no selecciona con el dedo).
  const allText = () => {
    const b = term.buffer.active; const lines = [];
    for (let i = 0; i < b.length; i++) lines.push(b.getLine(i).translateToString(true));
    while (lines.length && lines[lines.length - 1] === '') lines.pop();
    return lines.join('\n');
  };

  // The key bar's keys, as the terminal mode wants them: full-screen programs (vim, less, top) switch the arrows to
  // "application" mode. / Las teclas de la barra, como las quiere el modo del terminal: los programas a pantalla
  // completa (vim, less, top) pasan las flechas al modo «aplicación».
  const keys = (name) => {
    const app = term.modes.applicationCursorKeysMode;
    const cursor = (c) => (app ? '\x1bO' : '\x1b[') + c;
    return {
      Escape: '\x1b', Tab: '\t', ArrowUp: cursor('A'), ArrowDown: cursor('B'), ArrowRight: cursor('C'), ArrowLeft: cursor('D'),
      Home: cursor('H'), End: cursor('F'), PageUp: '\x1b[5~', PageDown: '\x1b[6~', Delete: '\x1b[3~',
    }[name];
  };

  if (host) host.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.t === 'key') { const s = keys(m.name); if (s) send({ t: 'input', d: s, key: true }); }
    else if (m.t === 'data') term.write(b64(m.b64));
    else if (m.t === 'paste') term.paste(m.text);
    else if (m.t === 'theme') { document.documentElement.classList.toggle('dark', m.dark); term.options.theme = m.dark ? themes.dark : themes.light; }
    else if (m.t === 'closed') { term.write('\r\n\x1b[31m' + m.text + '\x1b[0m\r\n'); term.options.disableStdin = true; term.options.cursorBlink = false; }
    else if (m.t === 'focus') term.focus();
    else if (m.t === 'text-request') send({ t: 'text', text: allText() });
    else if (m.t === 'select-all') { term.selectAll(); send({ t: 'selection', text: term.getSelection(), x: m.x, y: m.y }); }
    else if (m.t === 'clear-selection') term.clearSelection();
  };
  // Keys from a hardware keyboard go to the terminal from the start. / Las teclas de un teclado físico van a la terminal desde el principio.
  term.focus();
  send({ t: 'ready', cols: term.cols, rows: term.rows });
})();
