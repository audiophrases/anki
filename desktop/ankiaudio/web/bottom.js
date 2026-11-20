/* AnkiAudio Read Aloud: shortcut keys on the reviewer's bottom-bar buttons,
   plus Play and Undo buttons. Anki rewrites the middle of the bar for every
   question/answer, so a MutationObserver re-applies the labels. */
(function () {
  "use strict";

  var keys = window.ankiAudioKeys || { 1: "1", 2: "2", 3: "3", 4: "4" };
  var OBSERVE = { childList: true, subtree: true };
  var observer = null;

  function el(tag, className, text) {
    var e = document.createElement(tag);
    e.className = className;
    if (text) e.textContent = text;
    return e;
  }

  function label(button, text, before) {
    if (!button || button.querySelector(".aa-key")) return;
    button.insertBefore(el("kbd", "aa-key", text), before || null);
  }

  function extraButton(id, icon, text, key, title, command) {
    var b = el("button", "aa-extra");
    b.id = id;
    b.title = title;
    b.appendChild(el("span", "aa-icon", icon));
    b.appendChild(el("span", "aa-label", text));
    b.appendChild(el("kbd", "aa-key", key));
    b.addEventListener("click", function () {
      b.blur();
      pycmd(command);
    });
    return b;
  }

  function editButton() {
    return document.querySelector('button[onclick*="edit"]');
  }

  function decorate() {
    var edit = editButton();
    if (edit) {
      label(edit, "E");
      if (!document.getElementById("aa-play")) {
        edit.parentNode.appendChild(
          extraButton("aa-play", "▶", "Play", "P", "Read the card again (P)", "ankiaudio:play")
        );
        edit.parentNode.appendChild(
          extraButton("aa-undo", "↶", "Undo", "U / ⌫", "Undo the last answer (U or Backspace)", "ankiaudio:undo")
        );
      }
    }

    var answer = document.getElementById("ansbut");
    label(answer, "Space / ⏎", answer && answer.querySelector(".stattxt"));

    var buttons = document.querySelectorAll("#middle button[data-ease]");
    for (var i = 0; i < buttons.length; i++) {
      var b = buttons[i];
      var key = keys[b.getAttribute("data-ease")] || b.getAttribute("data-ease");
      if (b.id === "defease") key += " / ⏎";
      label(b, key, b.querySelector(".nobold"));
    }
    fit();
  }

  /* Anki gives the bar a fixed height, so a wrapped button would vanish below
     it. Narrow windows first drop the words of Play/Undo (icon + key stay); if
     even that doesn't fit, Play/Undo move to a row of their own and the bar is
     grown to show it. That row stays until the window gets wider, so the
     buttons don't jump between question and answer. */
  var rowsWidth = 0;
  var grown = false;

  function fit() {
    var edit = editButton();
    var play = document.getElementById("aa-play");
    var undo = document.getElementById("aa-undo");
    var body = document.body;
    if (!edit || !play || !undo) return;

    function inBar() {
      if (play.parentNode !== edit.parentNode) {
        edit.parentNode.appendChild(play);
        edit.parentNode.appendChild(undo);
      }
      var row = document.getElementById("aa-row");
      if (row) row.parentNode.removeChild(row);
    }
    function inRow() {
      var row = document.getElementById("aa-row");
      if (!row) {
        row = el("div", "");
        row.id = "aa-row";
        document.getElementById("outer").appendChild(row);
      }
      if (play.parentNode !== row) {
        row.appendChild(play);
        row.appendChild(undo);
      }
    }
    function wraps() {
      return play.offsetTop > edit.offsetTop + 2 || undo.offsetTop > edit.offsetTop + 2;
    }

    if (rowsWidth && window.innerWidth > rowsWidth) rowsWidth = 0;
    var rows = rowsWidth > 0;
    if (!rows) {
      inBar();
      body.classList.remove("aa-compact");
      if (wraps()) {
        body.classList.add("aa-compact");
        if (wraps()) {
          body.classList.remove("aa-compact"); // own row: room for the words
          rows = true;
          rowsWidth = window.innerWidth;
        }
      }
    }
    if (rows) inRow();

    var height = document.documentElement.offsetHeight;
    if (rows && height > window.innerHeight + 2) {
      grown = true;
      pycmd("ankiaudio:relayout");
    } else if (!rows && grown) {
      grown = false;
      pycmd("ankiaudio:relayout");
    }
  }

  /* Our own DOM changes must not wake the observer, or it would loop. */
  function quietly(fn) {
    if (observer) observer.disconnect();
    try {
      fn();
    } finally {
      if (observer) {
        observer.takeRecords();
        observer.observe(document.body, OBSERVE);
      }
    }
  }

  window.ankiAudioSpeaking = function (on) {
    var play = document.getElementById("aa-play");
    if (play) play.classList.toggle("aa-speaking", !!on);
  };

  function start() {
    observer = new MutationObserver(function () {
      quietly(decorate);
    });
    quietly(decorate);
    window.addEventListener("resize", function () {
      quietly(fit);
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
