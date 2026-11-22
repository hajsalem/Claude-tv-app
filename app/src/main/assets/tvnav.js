/*
 * TV remote navigation for claude.ai.
 *
 * Injected into every page by MainActivity. Turns D-pad arrow keys into
 * spatial focus movement between clickable things, scrolls the chat when
 * there's nothing further to move to, draws a big focus ring, and gives the
 * Android side a few hooks (activate, insertText, scrollPage, focusInput).
 */
(function () {
  if (window.__tvnav) return;

  var RING_COLOR = '#d97757';
  var FOCUSABLE = [
    'a[href]', 'button', 'input:not([type=hidden])', 'select', 'textarea', 'summary',
    '[contenteditable="true"]', '[role=button]', '[role=link]', '[role=menuitem]',
    '[role=menuitemradio]', '[role=menuitemcheckbox]', '[role=option]', '[role=tab]',
    '[role=switch]', '[role=checkbox]', '[role=radio]', '[role=combobox]',
    '[tabindex]:not([tabindex="-1"])'
  ].join(',');
  var NATIVE_ENTER = /^(INPUT|SELECT|TEXTAREA)$/;

  // ---------- focus ring overlay ----------
  var ring = document.createElement('div');
  ring.id = '__tvnav_ring';
  ring.style.cssText = [
    'position:fixed', 'pointer-events:none', 'z-index:2147483647', 'display:none',
    'border:4px solid ' + RING_COLOR, 'border-radius:10px',
    'box-shadow:0 0 0 3px rgba(0,0,0,.55), 0 0 18px ' + RING_COLOR,
    'transition:all 90ms ease-out', 'box-sizing:border-box'
  ].join(';');

  var current = null;

  function attachRing() {
    if (!ring.isConnected && document.body) document.body.appendChild(ring);
  }

  function drawRing() {
    attachRing();
    if (!current || !current.isConnected || !isVisible(current)) {
      ring.style.display = 'none';
      return;
    }
    var r = current.getBoundingClientRect();
    ring.style.display = 'block';
    ring.style.left = (r.left - 6) + 'px';
    ring.style.top = (r.top - 6) + 'px';
    ring.style.width = (r.width + 12) + 'px';
    ring.style.height = (r.height + 12) + 'px';
  }

  var rafPending = false;
  function scheduleRing() {
    if (rafPending) return;
    rafPending = true;
    requestAnimationFrame(function () { rafPending = false; drawRing(); });
  }
  window.addEventListener('scroll', scheduleRing, true);
  window.addEventListener('resize', scheduleRing);
  document.addEventListener('focusin', function (e) {
    if (e.target && e.target !== document.body && e.target.nodeType === 1) {
      current = e.target;
      scheduleRing();
    }
  }, true);
  // SPA re-renders move things around; keep the ring glued to its element.
  setInterval(drawRing, 400);

  // ---------- helpers ----------
  function isEditable(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.isContentEditable) return true;
    if (el.tagName === 'TEXTAREA') return true;
    if (el.tagName === 'INPUT') {
      var t = (el.type || 'text').toLowerCase();
      return ['text', 'search', 'email', 'url', 'password', 'tel', 'number'].indexOf(t) >= 0;
    }
    return false;
  }

  function isVisible(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    var s = getComputedStyle(el);
    if (s.visibility === 'hidden' || s.display === 'none' || parseFloat(s.opacity) === 0) return false;
    if (el.closest('[aria-hidden="true"],[inert]')) return false;
    if (el.disabled) return false;
    return true;
  }

  function viewport() {
    return { left: 0, top: 0, right: window.innerWidth, bottom: window.innerHeight };
  }

  function onScreen(r) {
    var v = viewport();
    return r.bottom > v.top + 2 && r.top < v.bottom - 2 && r.right > v.left + 2 && r.left < v.right - 2;
  }

  // Is the element actually the top-most thing at its center (i.e. not
  // covered by a modal/overlay)?
  function reachable(el, r) {
    var x = Math.min(Math.max(r.left + r.width / 2, 1), window.innerWidth - 2);
    var y = Math.min(Math.max(r.top + r.height / 2, 1), window.innerHeight - 2);
    var hit = document.elementFromPoint(x, y);
    if (!hit) return false;
    return hit === el || el.contains(hit) || hit.contains(el);
  }

  // Container that is "active" right now: an open dialog/menu if there is one,
  // otherwise the whole document.
  function scope() {
    var modals = document.querySelectorAll(
      '[role=dialog][data-state=open],[role=alertdialog],[role=menu],[role=listbox],dialog[open]');
    for (var i = modals.length - 1; i >= 0; i--) {
      if (isVisible(modals[i])) return modals[i];
    }
    return document;
  }

  function candidates() {
    var root = scope();
    var list = root.querySelectorAll(FOCUSABLE);
    var out = [];
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el === ring) continue;
      // Skip elements nested inside another focusable (e.g. svg inside button)
      var parent = el.parentElement && el.parentElement.closest(FOCUSABLE);
      if (parent && parent !== el && !isEditable(el) && root.contains(parent) &&
          !parent.isContentEditable) continue;
      if (el.closest('[contenteditable="true"]') && !el.isContentEditable) continue;
      if (el.isContentEditable && el.parentElement && el.parentElement.isContentEditable) continue;
      if (!isVisible(el)) continue;
      var r = el.getBoundingClientRect();
      if (!onScreen(r)) continue;
      if (!reachable(el, r)) continue;
      out.push({ el: el, r: r });
    }
    return out;
  }

  function overlap(a1, a2, b1, b2) {
    return Math.max(0, Math.min(a2, b2) - Math.max(a1, b1));
  }

  // Score candidate c relative to rect cur moving in dir. Lower is better.
  function score(cur, c, dir) {
    var primary, ortho, ov;
    var cx = (c.left + c.right) / 2, cy = (c.top + c.bottom) / 2;
    var ux = (cur.left + cur.right) / 2, uy = (cur.top + cur.bottom) / 2;
    if (dir === 'down') {
      if (cy <= uy + 1 || c.bottom <= cur.bottom) return Infinity;
      primary = Math.max(0, c.top - cur.bottom);
      ov = overlap(cur.left, cur.right, c.left, c.right);
      ortho = ov > 0 ? 0 : Math.min(Math.abs(c.left - cur.right), Math.abs(cur.left - c.right));
    } else if (dir === 'up') {
      if (cy >= uy - 1 || c.top >= cur.top) return Infinity;
      primary = Math.max(0, cur.top - c.bottom);
      ov = overlap(cur.left, cur.right, c.left, c.right);
      ortho = ov > 0 ? 0 : Math.min(Math.abs(c.left - cur.right), Math.abs(cur.left - c.right));
    } else if (dir === 'right') {
      if (cx <= ux + 1 || c.right <= cur.right) return Infinity;
      primary = Math.max(0, c.left - cur.right);
      ov = overlap(cur.top, cur.bottom, c.top, c.bottom);
      ortho = ov > 0 ? 0 : Math.min(Math.abs(c.top - cur.bottom), Math.abs(cur.top - c.bottom));
    } else {
      if (cx >= ux - 1 || c.left >= cur.left) return Infinity;
      primary = Math.max(0, cur.left - c.right);
      ov = overlap(cur.top, cur.bottom, c.top, c.bottom);
      ortho = ov > 0 ? 0 : Math.min(Math.abs(c.top - cur.bottom), Math.abs(cur.top - c.bottom));
    }
    // Strongly prefer staying in the same row/column.
    return primary + ortho * 3 + (ov > 0 ? 0 : 40);
  }

  function findInput() {
    var els = document.querySelectorAll('div.ProseMirror[contenteditable="true"], [contenteditable="true"], textarea');
    for (var i = 0; i < els.length; i++) {
      if (isVisible(els[i])) return els[i];
    }
    return null;
  }

  function findSend() {
    var sels = ['button[aria-label="Send message" i]', 'button[aria-label*="send" i]',
                'button[type=submit]'];
    for (var i = 0; i < sels.length; i++) {
      var b = document.querySelector(sels[i]);
      if (b && isVisible(b) && !b.disabled) return b;
    }
    return null;
  }

  function focusEl(el) {
    if (!el) return;
    if (!el.matches('a[href],button,input,select,textarea,[tabindex],[contenteditable="true"]')) {
      el.setAttribute('tabindex', '-1');
    }
    try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
    current = el;
    var r = el.getBoundingClientRect();
    if (r.top < 0 || r.bottom > window.innerHeight) {
      el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    }
    drawRing();
  }

  // The scrollable box under the middle of the screen (the chat transcript).
  function scrollableAncestor(start) {
    for (var el = start; el && el !== document.documentElement; el = el.parentElement) {
      if (el === document.body) break;
      var s = getComputedStyle(el);
      if (/(auto|scroll|overlay)/.test(s.overflowY) && el.scrollHeight > el.clientHeight + 4) return el;
    }
    return null;
  }

  function scroller() {
    var a = document.activeElement;
    var own = a && a !== document.body && !isEditable(a) ? scrollableAncestor(a) : null;
    if (own) return own;
    var mid = scrollableAncestor(document.elementFromPoint(window.innerWidth * 0.6, window.innerHeight * 0.5));
    if (mid) return mid;
    // Fallback: biggest scrollable element on the page.
    var best = null, bestArea = 0;
    var all = document.querySelectorAll('div,main,section');
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      if (e.scrollHeight <= e.clientHeight + 4) continue;
      var st = getComputedStyle(e);
      if (!/(auto|scroll|overlay)/.test(st.overflowY)) continue;
      var area = e.clientWidth * e.clientHeight;
      if (area > bestArea) { bestArea = area; best = e; }
    }
    return best || document.scrollingElement || document.documentElement;
  }

  function canScroll(dir) {
    var s = scroller();
    if (dir === 'up') return s.scrollTop > 1;
    return s.scrollTop + s.clientHeight < s.scrollHeight - 1;
  }

  function scrollBy(dir, fraction) {
    var s = scroller();
    var h = (s === document.scrollingElement ? window.innerHeight : s.clientHeight);
    var dy = h * (fraction || 0.6) * (dir === 'up' ? -1 : 1);
    s.scrollBy({ top: dy, behavior: 'smooth' });
  }

  var virtualIn = null;

  function currentRect(dir) {
    virtualIn = null;
    var el = document.activeElement;
    if (el && el !== document.body && el.nodeType === 1 && isVisible(el)) {
      var r = el.getBoundingClientRect();
      if (onScreen(r)) { current = el; return r; }
    }
    // Nothing focused on-screen (e.g. it scrolled away while reading): pretend
    // we're on the edge of the chat pane we're moving away from.
    var v = { left: 0, top: 0, right: window.innerWidth, bottom: window.innerHeight };
    var sc = scroller();
    if (sc && sc !== document.scrollingElement && sc !== document.documentElement) {
      var b = sc.getBoundingClientRect();
      v = { left: Math.max(0, b.left), top: Math.max(0, b.top),
            right: Math.min(v.right, b.right), bottom: Math.min(v.bottom, b.bottom) };
      virtualIn = sc;
    }
    if (dir === 'down') return { left: v.left, right: v.right, top: v.top - 2, bottom: v.top - 1 };
    if (dir === 'up') return { left: v.left, right: v.right, top: v.bottom + 1, bottom: v.bottom + 2 };
    if (dir === 'right') return { left: v.left - 2, right: v.left - 1, top: v.top, bottom: v.bottom };
    return { left: v.right + 1, right: v.right + 2, top: v.top, bottom: v.bottom };
  }

  function move(dir) {
    var cur = currentRect(dir);
    var best = null, bestScore = Infinity;
    var cands = candidates();
    for (var i = 0; i < cands.length; i++) {
      if (cands[i].el === document.activeElement) continue;
      if (virtualIn && !virtualIn.contains(cands[i].el)) continue;
      var s = score(cur, cands[i].r, dir);
      if (s < bestScore) { bestScore = s; best = cands[i].el; }
    }
    // Reading a long answer: if the next button is far away, scroll text instead.
    var far = window.innerHeight * 0.45;
    if (best && (dir === 'up' || dir === 'down') && bestScore > far && scope() === document &&
        canScroll(dir)) {
      scrollBy(dir, 0.5);
      return true;
    }
    if (best) { focusEl(best); return true; }
    if ((dir === 'up' || dir === 'down') && canScroll(dir)) { scrollBy(dir, 0.6); return true; }
    return false;
  }

  function caretAtEdge(el, dir) {
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      var v = el.value || '';
      if (!v.length) return true;
      return dir === 'left' ? el.selectionStart === 0 : el.selectionEnd === v.length;
    }
    var text = el.innerText.replace(/\n$/, '');
    if (!text.trim().length) return true;
    var sel = window.getSelection();
    if (!sel || !sel.rangeCount) return true;
    var range = sel.getRangeAt(0);
    var pre = document.createRange();
    pre.selectNodeContents(el);
    pre.setEnd(range.startContainer, range.startOffset);
    var before = pre.toString().length;
    if (dir === 'left') return before === 0;
    return before >= el.textContent.length;
  }

  // ---------- key handling ----------
  var KEYS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };

  document.addEventListener('keydown', function (e) {
    var dir = KEYS[e.key];
    if (!dir || e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;
    var a = document.activeElement;
    // Let open menus/listboxes handle their own arrow keys (Radix does roving focus).
    if (a && a.closest && a.closest('[role=menu],[role=listbox],[role=radiogroup],[role=tablist]') &&
        !isEditable(a)) {
      // ...but still draw the ring where the menu put focus.
      setTimeout(scheduleRing, 0);
      return;
    }
    if (isEditable(a)) {
      // Left/right move the caret while there's text to move through.
      // Up/down always leave the box (to reach Send, the chat, the sidebar).
      if ((dir === 'left' || dir === 'right') && !caretAtEdge(a, dir)) return;
    }
    if (move(dir)) {
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
    }
  }, true);

  // ---------- API for the Android side ----------
  window.__tvnav = {
    move: move,

    // Called on OK/center. Returns "editable" when the native side should
    // pop up the on-screen keyboard.
    activate: function () {
      var el = document.activeElement;
      if (!el || el === document.body) {
        var input = findInput();
        if (input) { focusEl(input); return 'editable'; }
        move('down');
        return 'moved';
      }
      if (isEditable(el)) return 'editable';
      if (NATIVE_ENTER.test(el.tagName)) {
        el.click();
        return 'clicked';
      }
      // Give React/Radix keyboard handlers first go (menus, dropdowns).
      var opts = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
      var down = new KeyboardEvent('keydown', opts);
      el.dispatchEvent(down);
      if (!down.defaultPrevented) {
        var r = el.getBoundingClientRect();
        var pt = { bubbles: true, cancelable: true, composed: true, button: 0, buttons: 1,
                   clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, pointerType: 'mouse',
                   isPrimary: true, pointerId: 1 };
        el.dispatchEvent(new PointerEvent('pointerdown', pt));
        el.dispatchEvent(new MouseEvent('mousedown', pt));
        pt.buttons = 0;
        el.dispatchEvent(new PointerEvent('pointerup', pt));
        el.dispatchEvent(new MouseEvent('mouseup', pt));
        el.click();
      }
      el.dispatchEvent(new KeyboardEvent('keyup', opts));
      setTimeout(drawRing, 50);
      return 'clicked';
    },

    focusInput: function () {
      var input = findInput();
      if (input) { focusEl(input); return true; }
      return false;
    },

    // Put dictated text in the prompt box, then hop focus to Send.
    insertText: function (text, autoSend) {
      var input = findInput();
      if (!input) return 'no-input';
      focusEl(input);
      if (input.isContentEditable) {
        var sel = window.getSelection();
        var range = document.createRange();
        range.selectNodeContents(input);
        range.collapse(false);
        sel.removeAllRanges();
        sel.addRange(range);
        var existing = input.innerText.trim();
        document.execCommand('insertText', false, (existing ? ' ' : '') + text);
      } else {
        var proto = input.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
        var v = input.value ? input.value + ' ' : '';
        setter.call(input, v + text);
        input.dispatchEvent(new Event('input', { bubbles: true }));
      }
      setTimeout(function () {
        var send = findSend();
        if (!send) return;
        if (autoSend) send.click();
        else focusEl(send);
      }, 350);
      return 'ok';
    },

    scrollPage: function (dir) { scrollBy(dir, 0.85); },

    activeIsEditable: function () { return isEditable(document.activeElement); },

    // Cloudflare "verify you are human" page/widget. Returns where the
    // checkbox is (CSS px) so the native pointer can be put on it, or null.
    findChallenge: function () {
      var title = document.title || '';
      var page = /just a moment|attention required|verify you are human|security check|one more step/i.test(title) ||
          /__cf_chl/.test(location.href) ||
          !!document.querySelector('#challenge-form, #challenge-stage, #challenge-body-text, ' +
                                   'script[src*="challenge-platform"]');
      var widget = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
      if (!widget) {
        var resp = document.querySelector('input[name="cf-turnstile-response"]');
        var box = resp && resp.parentElement;
        if (box && box.getBoundingClientRect().width > 50) widget = box;
      }
      if (!widget) widget = document.querySelector('.cf-turnstile, [id^="cf-chl-widget"]');
      if (!widget && page) {
        // The widget is a ~300x65 box, usually inside a closed shadow root.
        var divs = document.querySelectorAll('div');
        for (var i = 0; i < divs.length; i++) {
          var r0 = divs[i].getBoundingClientRect();
          if (r0.width >= 280 && r0.width <= 330 && r0.height >= 55 && r0.height <= 85) {
            widget = divs[i];
            break;
          }
        }
      }
      if (widget) {
        var r = widget.getBoundingClientRect();
        if (r.width > 50 && r.height > 20) {
          return { x: r.left + Math.min(30, r.width / 2), y: r.top + r.height / 2,
                   vw: window.innerWidth, widget: true };
        }
      }
      if (page) return { x: window.innerWidth / 2, y: window.innerHeight / 2, vw: window.innerWidth, widget: false };
      return null;
    },

    blur: function () {
      var a = document.activeElement;
      if (isEditable(a)) { a.blur(); current = a; drawRing(); return true; }
      return false;
    }
  };

  // Land in the prompt box when a page opens, so you can start typing/talking.
  function initialFocus(tries) {
    if (document.activeElement && document.activeElement !== document.body) return;
    if (!window.__tvnav.focusInput() && tries > 0) {
      setTimeout(function () { initialFocus(tries - 1); }, 500);
    }
  }
  initialFocus(10);
})();
