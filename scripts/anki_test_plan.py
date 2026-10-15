#!/usr/bin/env python3
"""Sets up and checks the manual Anki export test plan (docs/anki-export-manual-test-plan.md).

The plan runs in three parts: this script's "setup" command, the plan's manual steps, and this
script's "check" command.

  setup   Builds and installs the plugin and its test helper, creates the decks and note types
          in desktop Anki and in AnkiDroid, resets the plugin's Anki settings to the defaults,
          checks the plugin's dictionaries, writes the desktop Yomitan settings file to import, and
          serves the test page, which holds every word the plan looks up, to both platforms until
          Enter is pressed. Refuses to run while AuditTest holds notes, unless --clean is given,
          which deletes them on both platforms first.
  serve   Serves the test page again, as setup does, for a plan resumed later.
  check   Reads the plugin's stored Anki settings and every note in AuditTest from both platforms,
          and makes every check of them the plan lists, reporting each under the plan's own
          check ID (for example "C2.7" or "C7.10") with one of these statuses:

            PASS    the check holds.
            FAIL    the check does not hold; a finding, unless the plan lists it as a known exception.
            RECORD  a value the plan says to record rather than compare; kept in the report.
            REVIEW  the script cannot decide; a person has to look at the values in the report.
            MISSING a note the check needs was not found on one or both platforms.

Desktop is reached through AnkiConnect. Android is reached through AnkiTestPlanHelper
(app/src/androidTest/.../anki/AnkiTestPlanHelper.kt), instrumented tests that run inside the
plugin's process, because the adb shell has no permission to read AnkiDroid's notes or the
plugin's settings. Every such run restarts the plugin's process.

check finds notes by their first field and the order they were added: T1 is the first AuditTerm
note whose Expression is 食べる, T4 the second, and so on. Delete any note added by mistake
before running it, or the notes after it are matched to the wrong steps.

Each command writes its results to a new folder under --out, and exits with 1 if anything failed.
"""

import argparse
import base64
import datetime
import html
import html.parser
import http.server
import json
import os
import re
import shlex
import struct
import subprocess
import sys
import threading
import urllib.request
import webbrowser
import zlib

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACKAGE = 'io.github.dokuendev.dokuen.plugins.dictionary.yomitan'
HELPER_CLASS = PACKAGE + '.anki.AnkiTestPlanHelper'
HELPER_RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
PLUGIN_APK = os.path.join(REPO, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
HELPER_APK = os.path.join(REPO, 'app', 'build', 'outputs', 'apk', 'androidTest', 'debug', 'app-debug-androidTest.apk')
DECK_QUERY = 'deck:AuditTest'
PAGE_NAME = 'anki-test-page.html'

# Everything below is fixed by the plan. See its R2 to R5.
DECKS = ['AuditTest', 'AuditTest::Sub']
TERM_FIELDS = [
    'Expression', 'Reading', 'Glossary',
    'furigana', 'furigana-plain', 'dictionary', 'dictionary-alias', 'tags', 'part-of-speech', 'conjugation',
    'glossary-brief', 'glossary-no-dictionary', 'glossary-plain', 'glossary-plain-no-dictionary',
    'glossary-first', 'glossary-first-brief', 'glossary-first-no-dictionary',
    'frequencies', 'single-frequency-number', 'frequency-harmonic-rank', 'frequency-harmonic-occurrence',
    'frequency-average-rank', 'frequency-average-occurrence',
    'sentence', 'cloze-prefix', 'cloze-body', 'cloze-suffix', 'cloze-body-kana',
    'search-query', 'sentence-furigana', 'sentence-furigana-plain',
    'audio', 'screenshot', 'clipboard-image', 'clipboard-text', 'popup-selection-text', 'url', 'document-title',
    'phonetic-transcriptions', 'pitch-accents', 'pitch-accent-graphs', 'pitch-accent-graphs-jj',
    'pitch-accent-positions', 'pitch-accent-categories',
]
KANJI_FIELDS = [
    'Character', 'Kunyomi', 'Onyomi', 'Glossary',
    'onyomi-hiragana', 'stroke-count', 'search-query', 'sentence', 'cloze-prefix', 'cloze-body', 'cloze-suffix',
]
NOTE_TYPES = [
    {'name': 'AuditTerm', 'fields': TERM_FIELDS, 'front': '{{Expression}}',
     'back': '{{FrontSide}}<hr id=answer>{{Glossary}}'},
    {'name': 'AuditKanji', 'fields': KANJI_FIELDS, 'front': '{{Character}}',
     'back': '{{FrontSide}}<hr id=answer>{{Glossary}}'},
]
# The card formats of R2: (name, type, deck, note type, icon, changed field values, overwrite modes
# other than Fill if empty). Every other field has the value named after it.
CARD_FORMATS = [
    ('AuditVocab', 'term', 'AuditTest', 'AuditTerm', 'big-circle', {}, {'Glossary': 'append'}),
    ('AuditKanjiFmt', 'kanji', 'AuditTest', 'AuditKanji', 'big-diamond', {}, {}),
    ('AuditSub', 'term', 'AuditTest::Sub', 'AuditTerm', 'big-square', {}, {}),
    ('AuditQuoteKey', 'term', 'AuditTest', 'AuditTerm', 'small-circle', {'Expression': '{expression}"'}, {}),
    ('AuditBadMarker', 'term', 'AuditTest', 'AuditTerm', 'big-square', {'Reading': '{expresion}'}, {}),
]
PLUGIN_ICONS = {'big-circle': 'BIG_CIRCLE', 'small-circle': 'SMALL_CIRCLE', 'big-square': 'SQUARE',
                'big-diamond': 'DIAMOND'}
# The pinned dictionaries, in search order: (title, revision).
DICTIONARIES = [
    ('Jitendex.org [2026-05-05]', '2026.05.05.0'),
    ('JMnedict [2026-09-25]', 'JMnedict.2026-09-25'),
    ('KANJIDIC [2026-147]', 'kanjidic2.2026-147'),
    ('JPDBv2㋕', 'JPDB v2.2 Frequency Kana 2024-10-13'),
    ('BCCWJ', '1'),
]
# The test page (R5): every word the plan scans, each on a line of its own that ends with a
# sentence terminator, so that both platforms cut the sentence at the line; then the paragraph T8
# and K2 are scanned from; then the words only Phases 9, 11, 12, and 13 scan.
PAGE_WORDS = ['食べる', '動き出す', 'お見舞い', '食べさせられた', '牛', '京都', '学習辞典', '時雨', '鳥居',
              '千鳥', '順路', '記号', '學校']
TEST_PARAGRAPH = '雨が降っていた。電車が動き出す前に座った。窓の外は暗かった。'
LATER_WORDS = ['椅子', '鉛筆', '手紙', '猫', '犬', 'かける']
# Lets scripts/anki_test_plan_android.py find a word on the device's screen. Only the page in
# Chrome on Android runs it: it polls the server for a command naming a line of the page and a
# text in it, scrolls that line to the middle of the screen, and posts the position of each
# character of the text back. A command can also enlarge the line, as a tester zooms in when the
# OCR misreads a word (R5); the line's text, and so what a scan reads from it, stays the same.
PAGE_SCRIPT = '''<script>
(function () {
  if (!/Android/.test(navigator.userAgent)) return;
  var done = 0;
  var enlarged = null;
  function lineElement(line) {
    return Array.prototype.filter.call(document.querySelectorAll('p'), function (p) {
      return p.textContent === line;
    })[0];
  }
  function characterRects(element, text) {
    var node = element.firstChild;
    var start = node.data.indexOf(text);
    var rects = [];
    for (var i = 0; i < text.length; i++) {
      var range = document.createRange();
      range.setStart(node, start + i);
      range.setEnd(node, start + i + 1);
      var rect = range.getBoundingClientRect();
      rects.push([rect.left, rect.top, rect.right, rect.bottom]);
    }
    return rects;
  }
  function report(command) {
    var element = lineElement(command.line);
    var viewport = window.visualViewport || {scale: 1, offsetLeft: 0, offsetTop: 0};
    var result = {id: command.id, found: !!element, dpr: window.devicePixelRatio, scale: viewport.scale,
                  offsetLeft: viewport.offsetLeft, offsetTop: viewport.offsetTop};
    if (element) result.rects = characterRects(element, command.text);
    return fetch('/control/layout', {method: 'POST', body: JSON.stringify(result)});
  }
  function poll() {
    fetch('/control/command?after=' + done).then(function (response) {
      return response.status === 200 ? response.json() : null;
    }).then(function (command) {
      if (!command) return null;
      done = command.id;
      var element = lineElement(command.line);
      if (enlarged) enlarged.style.fontSize = '';
      enlarged = null;
      if (element && command.scale > 1) {
        element.style.fontSize = (32 * command.scale) + 'px';
        enlarged = element;
      }
      if (element) element.scrollIntoView({block: 'center'});
      return new Promise(function (resolve) {
        setTimeout(function () { resolve(report(command)); }, 400);
      });
    }).catch(function () {}).then(function () { setTimeout(poll, 250); });
  }
  poll();
})();
</script>
'''
PAGE_HTML = ('<!DOCTYPE html>\n<html lang="ja"><head><meta charset="utf-8">\n'
             '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
             '<title>Anki test page</title></head>\n'
             '<body style="font-size: 32px; line-height: 1.6; margin: 24px;">\n%s<hr>\n<p>%s</p>\n<hr>\n%s%s</body></html>\n' % (
                 ''.join('<p>%s。</p>\n' % word for word in PAGE_WORDS), TEST_PARAGRAPH,
                 ''.join('<p>%s。</p>\n' % word for word in LATER_WORDS), PAGE_SCRIPT))
OVERWRITE_WORD = '椅子'


def field_values(note_type, changes):
    """The field values of a card format: each field's own marker, then the format's changes."""
    fields = next(spec['fields'] for spec in NOTE_TYPES if spec['name'] == note_type)
    first = '{expression}' if note_type == 'AuditTerm' else '{character}'
    values = {name: first if index == 0 else '{%s}' % name.lower() for index, name in enumerate(fields)}
    values.update(changes)
    return values

JITENDEX = 'Jitendex.org [2026-05-05]'
JMNEDICT = 'JMnedict [2026-09-25]'
JITENDEX_SCOPE = '.yomitan-glossary [data-dictionary="%s"]' % JITENDEX

# The notes the checks pair up: label -> (first field, occurrence, Reading).
# The occurrence counts notes of that type with that first field, oldest first. Reading, when
# given, must match, which catches a note added from the wrong popup entry.
TERM_NOTES = {
    'T1': ('食べる', 1, 'たべる'),
    'T2': ('動き出す', 1, 'うごきだす'),
    'T3': ('お見舞い', 1, 'おみまい'),
    'T4': ('食べる', 2, 'たべる'),
    'T5': ('牛', 1, 'ぎゅう'),
    'T6': ('京都', 1, 'きょうと'),
    'T7': ('学習辞典', 1, 'がくしゅうじてん'),
    'T8': ('動き出す', 2, 'うごきだす'),
    'T6b': ('京都', 2, 'きょうと'),
}
SECTION5_NOTES = {
    '時雨': ('時雨', 1, 'しぐれ'),
    '鳥居': ('鳥居', 1, 'とりい'),
    '千鳥': ('千鳥', 1, 'ちどり'),
    '順路': ('順路', 1, 'じゅんろ'),
    '記号': ('記号', 1, 'きごう'),
    '學校': ('學校', 1, None),
}
KANJI_NOTES = {
    'K1': ('食', 1, None),
    'K2': ('動', 1, None),
}

FURIGANA = {
    'T1': '<ruby>食<rt>た</rt></ruby>べる',
    'T2': '<ruby>動<rt>うご</rt></ruby>き<ruby>出<rt>だ</rt></ruby>す',
    'T3': 'お<ruby>見舞<rt>みま</rt></ruby>い',
}
T8_SENTENCE = {
    'sentence': '電車が動き出す前に座った。',
    'cloze-prefix': '電車が',
    'cloze-body': '動き出す',
    'cloze-suffix': '前に座った。',
    'cloze-body-kana': 'うごきだす',
    'search-query': '動き出す',
}
# The plugin's conjugation chain for T4, in the compact names its popup shows (C2.9).
T4_CONJUGATION = '-せる・させる « -られる « -た'
# The notes scanned from a line of their own (C4.4): the text scanned, and the kana of that text.
SCANNED_LINES = {
    'T1': ('食べる', 'たべる'),
    'T2': ('動き出す', 'うごきだす'),
    'T3': ('お見舞い', 'おみまい'),
    'T4': ('食べさせられた', 'たべさせられた'),
    'T5': ('牛', 'ぎゅう'),
    'T6': ('京都', 'きょうと'),
    'T7': ('学習辞典', 'がくしゅうじてん'),
    'T6b': ('京都', 'きょうと'),
}
# A kanji reached from a term's headword carries the term's sentence and query (C6.7, C6.8, C6.9).
KANJI_SENTENCES = {
    'K1': {'sentence': '食べる。', 'cloze-prefix': '', 'cloze-body': '食', 'cloze-suffix': 'べる。'},
    'K2': {'sentence': '電車が動き出す前に座った。', 'cloze-prefix': '電車が', 'cloze-body': '動',
           'cloze-suffix': 'き出す前に座った。'},
}

SENTENCE_FIELDS = ['sentence', 'cloze-prefix', 'cloze-body', 'cloze-suffix', 'cloze-body-kana']
SENTENCE_FURIGANA_FIELDS = ['sentence-furigana', 'sentence-furigana-plain']
STUB_FIELDS = [
    'audio', 'popup-selection-text', 'url', 'document-title', 'phonetic-transcriptions', 'pitch-accents',
    'pitch-accent-graphs', 'pitch-accent-graphs-jj', 'pitch-accent-positions', 'pitch-accent-categories',
]
# Filled on each platform from its own screen and clipboard when the note is added, so recorded,
# not compared (C2.23).
HOST_MEDIA_FIELDS = ['screenshot', 'clipboard-image', 'clipboard-text']
STYLED_FIELDS = ['Glossary', 'glossary-brief', 'glossary-no-dictionary']
STYLED_FIRST_FIELDS = ['glossary-first', 'glossary-first-brief', 'glossary-first-no-dictionary']
FREQUENCY_RANK_FIELDS = ['frequency-harmonic-rank', 'frequency-average-rank']
FREQUENCY_OCCURRENCE_FIELDS = ['frequency-harmonic-occurrence', 'frequency-average-occurrence']

# The expected-values table row each field of an AuditTerm note falls under. A field compared for
# a note the row does not name is reported under C2.22, the plan's rule that every other field is
# identical on both platforms.
TERM_FIELD_ROWS = {
    'Expression': ('C2.1', {'T1'}),
    'Reading': ('C2.2', {'T1'}),
    'furigana': ('C2.3', {'T1', 'T2', 'T3'}),
    'furigana-plain': ('C2.4', {'T1', 'T2', 'T3'}),
    'dictionary': ('C2.5', {'T1'}),
    'dictionary-alias': ('C2.6', {'T1'}),
    'tags': ('C2.7', {'T1', 'T2', 'T3', 'T6'}),
    'conjugation': ('C2.9', {'T4'}),
    'glossary-plain': ('C2.11', None),
    'glossary-plain-no-dictionary': ('C2.12', {'T1'}),
    'frequencies': ('C2.14', {'T1', 'T6', 'T7'}),
    'single-frequency-number': ('C2.15', {'T1', 'T6', 'T7'}),
    'search-query': ('C2.18', {'T1'}),
}
for _field in FREQUENCY_RANK_FIELDS + FREQUENCY_OCCURRENCE_FIELDS:
    TERM_FIELD_ROWS[_field] = ('C2.16', {'T1', 'T6', 'T7'})
for _field in SENTENCE_FIELDS:
    TERM_FIELD_ROWS[_field] = ('C2.19', {'T1', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'})


class Note:
    def __init__(self, note_id, model, fields, tags):
        self.id = note_id
        self.model = model
        self.fields = fields  # field name -> value, in the note type's field order
        self.tags = tags

    @property
    def first(self):
        return next(iter(self.fields.values()), '')

    def get(self, field):
        return self.fields.get(field, '')

    def to_json(self):
        return {'id': self.id, 'model': self.model, 'fields': self.fields, 'tags': self.tags}


# ---------------------------------------------------------------------------------------------
# Reading the notes


def anki_connect(url, action, **params):
    body = json.dumps({'action': action, 'version': 6, 'params': params}).encode('utf-8')
    with urllib.request.urlopen(urllib.request.Request(url, body), timeout=30) as response:
        reply = json.loads(response.read().decode('utf-8'))
    if reply.get('error'):
        raise RuntimeError('AnkiConnect %s failed: %s' % (action, reply['error']))
    return reply['result']


def desktop_notes(url):
    ids = anki_connect(url, 'findNotes', query=DECK_QUERY)
    infos = anki_connect(url, 'notesInfo', notes=ids) if ids else []
    notes = []
    for info in infos:
        ordered = sorted(info['fields'].items(), key=lambda item: item[1]['order'])
        fields = {name: value['value'] for name, value in ordered}
        notes.append(Note(info['noteId'], info['modelName'], fields, sorted(info['tags'])))
    return sorted(notes, key=lambda note: note.id)

def run_adb(adb, serial, args, binary=False):
    command = [adb] + (['-s', serial] if serial else []) + args
    # No input: adb shell would otherwise read what the tester types at the terminal.
    result = subprocess.run(command, capture_output=True, stdin=subprocess.DEVNULL)
    if result.returncode != 0:
        raise RuntimeError('%s failed:\n%s' % (' '.join(command), result.stderr.decode('utf-8', 'replace')))
    return result.stdout if binary else result.stdout.decode('utf-8', 'replace')


def run_helper(adb, serial, action, **arguments):
    """Runs one AnkiTestPlanHelper action on the device and returns the JSON it wrote."""
    output_file = 'files/anki-test-plan-%s.json' % action
    run_adb(adb, serial, ['shell', 'run-as', PACKAGE, 'rm', '-f', output_file])
    command = ['am', 'instrument', '-w', '-e', 'class', HELPER_CLASS, '-e', 'action', action]
    for name, value in arguments.items():
        command += ['-e', name, value]
    command.append(HELPER_RUNNER)
    output = run_adb(adb, serial, ['shell', ' '.join(shlex.quote(part) for part in command)])
    if 'FAILURES!!!' in output or 'OK (' not in output:
        raise RuntimeError('The test helper\'s %s failed. Are the plugin and helper APKs from the same build '
                           'installed (setup does this)? Output:\n%s' % (action, output))
    raw = run_adb(adb, serial, ['exec-out', 'run-as', PACKAGE, 'cat', output_file], binary=True)
    try:
        return json.loads(raw.decode('utf-8'))
    except ValueError:
        raise RuntimeError('The test helper\'s %s wrote no readable result:\n%s' % (
            action, raw.decode('utf-8', 'replace')))


def android_notes(adb, serial):
    notes = []
    for item in run_helper(adb, serial, 'dumpNotes', query=DECK_QUERY)['notes']:
        names = item['fieldNames']
        values = item['fields'] + [''] * (len(names) - len(item['fields']))
        notes.append(Note(item['id'], item['modelName'], dict(zip(names, values)), sorted(item['tags'])))
    return sorted(notes, key=lambda note: note.id)

def find_note(notes, model, first, occurrence):
    matches = [note for note in notes if note.model == model and note.first == first]
    return matches[occurrence - 1] if len(matches) >= occurrence else None


# ---------------------------------------------------------------------------------------------
# HTML and CSS helpers

VOID_ELEMENTS = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'source', 'track', 'wbr'}


class Element:
    def __init__(self, tag, attrs, parent):
        self.tag = tag
        self.attrs = dict(attrs)
        self.parent = parent
        self.children = []

    def elements(self):
        return [child for child in self.children if isinstance(child, Element)]

    def text(self):
        return ''.join(child if isinstance(child, str) else child.text() for child in self.children)

    def descendants(self):
        for child in self.elements():
            yield child
            yield from child.descendants()


class _TreeBuilder(html.parser.HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = Element('#root', [], None)
        self.current = self.root

    def handle_starttag(self, tag, attrs):
        element = Element(tag, attrs, self.current)
        self.current.children.append(element)
        if tag not in VOID_ELEMENTS:
            self.current = element

    def handle_startendtag(self, tag, attrs):
        self.current.children.append(Element(tag, attrs, self.current))

    def handle_endtag(self, tag):
        node = self.current
        while node is not None and node.tag != tag:
            node = node.parent
        if node is not None and node.parent is not None:
            self.current = node.parent

    def handle_data(self, data):
        self.current.children.append(data)


def parse_html(text):
    builder = _TreeBuilder()
    builder.feed(text)
    builder.close()
    return builder.root


def glossary_items(text):
    """The element children of the glossary's <ol>, or None if there is no such list."""
    root = parse_html(text)
    for element in root.descendants():
        if element.tag == 'div' and 'yomitan-glossary' in element.attrs.get('class', '').split():
            lists = [child for child in element.elements() if child.tag == 'ol']
            return lists[0].elements() if lists else None
    return None


STYLE_PATTERN = re.compile(r'<style>(.*?)</style>', re.S)
# An image as desktop writes it: the media file, linked, around a stack of spans and the <img>;
# or as the plugin writes it: a bare <img> (the known gap of R9).
IMAGE_PATTERN = re.compile(r'<a [^>]*href="yomitan_dictionary_media_[^"]*"[^>]*>.*?</a>|<img\b[^>]*>', re.S)
LINK_HREF_PATTERN = re.compile(r'<a\b[^>]*\bhref="([^"]*)"')


def split_styles(text):
    """The text with each <style> element emptied, and the style elements' contents."""
    return STYLE_PATTERN.sub('<style></style>', text), STYLE_PATTERN.findall(text)


def without_term_spans(value):
    """The value without sentence-furigana's <span class="term"> wrappers, which follow each
    platform's own word segmentation."""
    return None if value is None else value.replace('<span class="term">', '').replace('</span>', '')


def mask_known_differences(text):
    """Blanks what the plan records rather than compares: images."""
    return IMAGE_PATTERN.sub('<img>', text)


def internal_link_targets(text):
    """The href of every link that does not leave the dictionary (not http or https)."""
    return [href for href in LINK_HREF_PATTERN.findall(text) if not re.match(r'https?:', href)]


def normalize_selector(selector):
    selector = re.sub(r'\s+', ' ', selector).strip()
    selector = re.sub(r'\s*([,>+~])\s*', r'\1', selector)
    return selector


def parse_css(text, i=0):
    """Parses CSS into ('rule', selector, items) and ('decl', property name) items."""
    items = []
    buffer = ''
    while i < len(text):
        char = text[i]
        if char in '"\'':
            end = i + 1
            while end < len(text) and text[end] != char:
                end += 2 if text[end] == '\\' else 1
            buffer += text[i:end + 1]
            i = end + 1
        elif char == '{':
            children, i = parse_css(text, i + 1)
            items.append(('rule', normalize_selector(buffer), children))
            buffer = ''
        elif char in ';}':
            if buffer.strip():
                items.append(('decl', buffer.split(':', 1)[0].strip().lower()))
            buffer = ''
            i += 1
            if char == '}':
                return items, i
        else:
            buffer += char
            i += 1
    if buffer.strip():
        items.append(('decl', buffer.split(':', 1)[0].strip().lower()))
    return items, i


def css_structure(text):
    return parse_css(re.sub(r'/\*.*?\*/', '', text, flags=re.S))[0]


def top_level_selector_parts(structure):
    parts = []
    for item in structure:
        if item[0] == 'rule':
            parts.extend(item[1].split(','))
    return parts


def first_difference(a, b, context=80):
    index = next((i for i, (x, y) in enumerate(zip(a, b)) if x != y), min(len(a), len(b)))
    return 'first difference at character %d:\n  desktop: ...%s...\n  plugin:  ...%s...' % (
        index, a[max(0, index - context):index + context], b[max(0, index - context):index + context])


# ---------------------------------------------------------------------------------------------
# Report


class Report:
    def __init__(self):
        self.results = []
        self.field_rows = []

    def add(self, step, status, subject, message, desktop=None, plugin=None):
        self.results.append({'step': step, 'status': status, 'subject': subject, 'message': message,
                             'desktop': desktop, 'plugin': plugin})

    def field(self, note, field, rule, status, desktop, plugin):
        self.field_rows.append({'note': note, 'field': field, 'rule': rule, 'status': status,
                                'desktop': desktop, 'plugin': plugin})

    def failed(self):
        return any(result['status'] in ('FAIL', 'MISSING') for result in self.results)

    def print_summary(self):
        for result in self.results:
            print('%-7s %-8s %-10s %s' % (result['step'], result['status'], result['subject'], result['message']))
        counts = {}
        for result in self.results:
            counts[result['status']] = counts.get(result['status'], 0) + 1
        print('\n' + ', '.join('%s: %d' % item for item in sorted(counts.items())))

    def write(self, folder):
        with open(os.path.join(folder, 'report.json'), 'w', encoding='utf-8') as out:
            json.dump({'results': self.results, 'fields': self.field_rows},
                      out, ensure_ascii=False, indent=1)
        with open(os.path.join(folder, 'report.md'), 'w', encoding='utf-8') as out:
            out.write('# Anki parity report\n\n')
            out.write('| Step | Status | Note | Result |\n|---|---|---|---|\n')
            for result in self.results:
                out.write('| %s | %s | %s | %s |\n' % (
                    result['step'], result['status'], md_cell(result['subject']), md_cell(result['message'])))
            details = [result for result in self.results if result['status'] != 'PASS'
                       and (result['desktop'] is not None or result['plugin'] is not None)]
            if details:
                out.write('\n## Values\n')
                for result in details:
                    out.write('\n### %s %s (%s)\n\n' % (result['step'], result['subject'], result['status']))
                    for platform in ('desktop', 'plugin'):
                        if result[platform] is not None:
                            out.write('%s:\n\n```\n%s\n```\n\n' % (platform, result[platform]))
            if self.field_rows:
                out.write('\n## Every field compared\n\n'
                          'Full values are in report.json; long values are cut here, and each newline in a value '
                          'is shown as \\n.\n\n'
                          '| Note | Field | Rule | Status | Desktop | Plugin |\n|---|---|---|---|---|---|\n')
                for row in self.field_rows:
                    out.write('| %s | %s | %s | %s | %s | %s |\n' % (
                        md_cell(row['note']), md_cell(row['field']), md_cell(row['rule']), row['status'],
                        md_cell(shorten(row['desktop'])), md_cell(shorten(row['plugin']))))


def md_cell(text):
    """A table cell's text, shown literally: HTML such as <style> is escaped so that a renderer shows
    it rather than parsing it, and each newline is shown as the two characters \\n."""
    return html.escape(str(text), quote=False).replace('|', '\\|').replace('\n', '\\n')


def shorten(text, limit=120):
    text = '' if text is None else str(text)
    return text if len(text) <= limit else text[:limit] + '...'


# ---------------------------------------------------------------------------------------------
# Checks shared by several groups of checks


def pair(report, desktop, plugin, model, specs):
    """Finds each note of specs on both platforms; returns label -> (desktop note, plugin note)."""
    pairs = {}
    for label, (first, occurrence, reading) in specs.items():
        found = []
        for platform, notes in (('desktop', desktop), ('plugin', plugin)):
            note = find_note(notes, model, first, occurrence)
            if note is None:
                report.add('-', 'MISSING', label, 'no %s note #%d with first field %s on %s' % (
                    model, occurrence, first, platform))
            elif reading is not None and note.get('Reading') != reading:
                report.add('-', 'FAIL', label, '%s note has Reading %r, expected %r: added from the wrong entry?' % (
                    platform, note.get('Reading'), reading))
                note = None
            found.append(note)
        if all(found):
            pairs[label] = tuple(found)
    return pairs


def compare_styled(report, step, label, field, d_value, p_value):
    """Compares a glossary field that may hold <style> elements and images, as the plan allows."""
    d_body, d_styles = split_styles(d_value)
    p_body, p_styles = split_styles(p_value)
    d_masked, p_masked = mask_known_differences(d_body), mask_known_differences(p_body)
    if d_masked == p_masked:
        status = 'PASS'
        report.add(step, 'PASS', label, '%s identical apart from <style> text and images' % field)
    else:
        status = 'FAIL'
        report.add(step, 'FAIL', label, '%s differs; %s' % (field, first_difference(d_masked, p_masked)),
                   d_value, p_value)
    if len(d_styles) != len(p_styles):
        status = 'FAIL'
        report.add(step, 'FAIL', label, '%s has %d <style> elements on desktop, %d on the plugin' % (
            field, len(d_styles), len(p_styles)))
    else:
        for index, (d_css, p_css) in enumerate(zip(d_styles, p_styles)):
            if css_structure(d_css) != css_structure(p_css):
                if status == 'PASS':
                    status = 'REVIEW'
                report.add(step, 'REVIEW', label, '%s <style> #%d: the rules, selectors, or property names differ '
                           '(value spelling and whitespace may differ; anything else is a finding)' % (
                               field, index + 1), d_css, p_css)
    d_images, p_images = IMAGE_PATTERN.findall(d_body), IMAGE_PATTERN.findall(p_body)
    if d_images != p_images:
        report.add(step, 'RECORD', label, '%s images differ (known gap: the plugin stores no dictionary media)' % field,
                   '\n\n'.join(d_images), '\n\n'.join(p_images))
    return status


def expect_equal(report, step, label, what, expected, d_value, p_value):
    if d_value == expected and p_value == expected:
        report.add(step, 'PASS', label, '%s is %r on both' % (what, expected))
    else:
        report.add(step, 'FAIL', label, '%s should be %r on both' % (what, expected), d_value, p_value)


def check_scoped_first_style(report, step, label, field, value, platform):
    styles = split_styles(value)[1]
    parts = [part for css in styles for part in top_level_selector_parts(css_structure(css))]
    if styles and all(part.startswith('.yomitan-glossary ') and '[data-dictionary' not in part for part in parts):
        report.add(step, 'PASS', label, '%s <style> on %s is prefixed with .yomitan-glossary only' % (field, platform))
    else:
        report.add(step, 'FAIL', label, '%s <style> on %s should be prefixed with .yomitan-glossary only' % (
            field, platform), value if platform == 'desktop' else None, value if platform == 'plugin' else None)


def jmdict_ids(element):
    return [match for a in element.descendants() if a.tag == 'a'
            for match in re.findall(r'svc=jmdict&q=(\d+)', a.attrs.get('href', ''))]


# ---------------------------------------------------------------------------------------------
# Checks of the notes


def check_term_notes(report, desktop, plugin):
    pairs = pair(report, desktop, plugin, 'AuditTerm', TERM_NOTES)

    for label, (d, p) in pairs.items():
        for field in d.fields:
            d_value, p_value = d.get(field), p.get(field)
            if field not in p.fields:
                report.add('C2.22', 'FAIL', label, 'the plugin note has no field %s' % field)
                continue
            if label == 'T6b':
                step = 'C5.3'
            else:
                row, labels = TERM_FIELD_ROWS.get(field, ('C2.22', None))
                step = row if labels is None or label in labels else 'C2.22'
            if field == 'part-of-speech':
                rule, status = 'record', 'RECORD'
                report.add('C2.8', 'RECORD', label, 'part-of-speech', d_value, p_value)
            elif field == 'conjugation' and label == 'T4':
                rule = 'plugin exact; desktop recorded'
                status = 'PASS' if p_value == T4_CONJUGATION else 'FAIL'
            elif field in HOST_MEDIA_FIELDS:
                rule, status = 'recorded on both', 'RECORD'
            elif field in STUB_FIELDS:
                rule = 'plugin empty; desktop recorded'
                status = 'PASS' if p_value == '' else 'FAIL'
                if status == 'FAIL':
                    report.add('C2.21', 'FAIL', label, '%s should be empty on the plugin' % field, None, p_value)
            elif field in SENTENCE_FURIGANA_FIELDS:
                rule = 'identical apart from <span class="term"> boundaries'
                status = 'PASS' if without_term_spans(d_value) == without_term_spans(p_value) else 'FAIL'
                if status == 'FAIL':
                    report.add('C2.20', 'FAIL', label, '%s differs apart from term boundaries' % field,
                               d_value, p_value)
            elif field in STYLED_FIELDS + STYLED_FIRST_FIELDS:
                rule = 'identical apart from <style> text and images'
                styled_step = step if label == 'T6b' else ('C2.13' if field in STYLED_FIRST_FIELDS else 'C2.10')
                status = compare_styled(report, styled_step, label, field, d_value, p_value)
            else:
                rule = 'identical'
                status = 'PASS' if d_value == p_value else 'FAIL'
                if status == 'FAIL':
                    report.add(step, 'FAIL', label, '%s differs' % field, d_value, p_value)
            report.field(label, field, rule, status, d_value, p_value)
        mismatched = [row for row in report.field_rows if row['note'] == label and row['status'] == 'FAIL']
        if not mismatched:
            report.add('C2.22' if label != 'T6b' else 'C5.3', 'PASS', label,
                       'every compared field matches, apart from the rows reported separately')

    def both(label, field):
        d, p = pairs[label]
        return d.get(field), p.get(field)

    if 'T1' in pairs:
        expect_equal(report, 'C2.1', 'T1', 'Expression', '食べる', *both('T1', 'Expression'))
        expect_equal(report, 'C2.2', 'T1', 'Reading', 'たべる', *both('T1', 'Reading'))
        expect_equal(report, 'C2.5', 'T1', 'dictionary', JITENDEX, *both('T1', 'dictionary'))
        expect_equal(report, 'C2.6', 'T1', 'dictionary-alias', JITENDEX, *both('T1', 'dictionary-alias'))
        expect_equal(report, 'C2.18', 'T1', 'search-query', '食べる', *both('T1', 'search-query'))
        plugin_pos = pairs['T1'][1].get('part-of-speech')
        report.add('C2.8', 'PASS' if plugin_pos == 'Ichidan verb' else 'FAIL', 'T1',
                   'plugin part-of-speech is %r, expected \'Ichidan verb\'' % plugin_pos)
        for platform, note in zip(('desktop', 'plugin'), pairs['T1']):
            plain = note.get('glossary-plain')
            line = '(%s)<br>' % JITENDEX
            expected = plain.replace(line, '', 1)
            ok = line in plain and note.get('glossary-plain-no-dictionary') == expected
            report.add('C2.12', 'PASS' if ok else 'FAIL', 'T1',
                       '%s glossary-plain-no-dictionary is glossary-plain without its %s line' % (platform, line),
                       note.get('glossary-plain-no-dictionary') if not ok and platform == 'desktop' else None,
                       note.get('glossary-plain-no-dictionary') if not ok and platform == 'plugin' else None)
    for label, step in (('T1', 'C3.1'), ('T2', 'C3.2'), ('T3', 'C3.3')):
        if label in pairs:
            expect_equal(report, step, label, 'furigana', FURIGANA[label], *both(label, 'furigana'))
    tag_expectations = {'T1': ['★'], 'T2': ['★', r'priority\sform'], 'T6': ['★', 'place']}
    for label, patterns in tag_expectations.items():
        if label in pairs:
            d_tags, p_tags = both(label, 'tags')
            ok = all(re.search(pattern, d_tags) and re.search(pattern, p_tags) for pattern in patterns)
            report.add('C2.7', 'PASS' if ok else 'FAIL', label, 'tags include %s on both' % ', '.join(patterns),
                       None if ok else d_tags, None if ok else p_tags)
    if 'T3' in pairs:
        expect_equal(report, 'C2.7', 'T3', 'tags', '', *both('T3', 'tags'))
    if 'T4' in pairs:
        d_value, p_value = both('T4', 'conjugation')
        report.add('C2.9', 'PASS' if p_value == T4_CONJUGATION else 'FAIL', 'T4',
                   'plugin conjugation is %r (desktop recorded; its names are English)' % T4_CONJUGATION,
                   d_value, p_value)
    for label in pairs:
        for platform, note in zip(('desktop', 'plugin'), pairs[label]):
            if '<style' in note.get('glossary-plain'):
                report.add('C2.11', 'FAIL', label, '%s glossary-plain contains a <style>' % platform)
    if 'T7' in pairs:
        for field in ('frequencies', 'single-frequency-number'):
            expect_equal(report, 'C2.14' if field == 'frequencies' else 'C2.15', 'T7', field, '', *both('T7', field))
        for field in FREQUENCY_RANK_FIELDS:
            expect_equal(report, 'C2.16', 'T7', field, '9999999', *both('T7', field))
        for field in FREQUENCY_OCCURRENCE_FIELDS:
            expect_equal(report, 'C2.16', 'T7', field, '0', *both('T7', field))

    # -first variants hold only the first definition.
    for label in ('T5', 'T6'):
        if label not in pairs:
            continue
        for platform, note in zip(('desktop', 'plugin'), pairs[label]):
            items = glossary_items(note.get('Glossary')) or []
            definitions = [item for item in items if item.tag == 'li']
            if len(definitions) != 2:
                report.add('C2.13', 'FAIL', label, '%s Glossary has %d definitions, expected 2' % (
                    platform, len(definitions)))
                continue
            for field in STYLED_FIRST_FIELDS:
                first_text = parse_html(note.get(field))
                if label == 'T5':
                    first_ids, second_ids = jmdict_ids(definitions[0]), jmdict_ids(definitions[1])
                    present = set(jmdict_ids(first_text))
                    ok = bool(first_ids) and set(first_ids) <= present and not (set(second_ids) & present)
                else:
                    # JMnedict's gloss; Jitendex's own definition says "Kyoto" without the macron.
                    ok = bool(jmdict_ids(first_text)) and 'Kyōto (city, prefecture)' not in first_text.text()
                report.add('C2.13', 'PASS' if ok else 'FAIL', label,
                           '%s %s holds only the first definition' % (platform, field))
                check_scoped_first_style(report, 'C2.13', label, field, note.get(field), platform)

    # The sentence scanned from the paragraph.
    if 'T8' in pairs:
        d, p = pairs['T8']
        for field, expected in T8_SENTENCE.items():
            expect_equal(report, 'C4.1', 'T8', field, expected, d.get(field), p.get(field))
        leaked = [(platform, field) for platform, note in (('desktop', d), ('plugin', p))
                  for field, value in note.fields.items() if '雨が降' in value or '窓の外' in value]
        report.add('C4.2', 'FAIL' if leaked else 'PASS', 'T8',
                   'the outer sentences appear in %s' % leaked if leaked else 'neither outer sentence appears')
        for field, mark in (('sentence-furigana', '<ruby>'), ('sentence-furigana-plain', '[')):
            value = p.get(field) or ''
            report.add('C4.3', 'PASS' if mark in value else 'FAIL', 'T8',
                       'plugin %s carries readings' % field, None, value)

    # The sentences scanned from a line of their own.
    for label, (word, kana) in SCANNED_LINES.items():
        if label not in pairs:
            continue
        expected = {'sentence': word + '。', 'cloze-prefix': '', 'cloze-body': word, 'cloze-suffix': '。',
                    'cloze-body-kana': kana, 'search-query': word}
        for field, value in expected.items():
            expect_equal(report, 'C4.4', label, field, value, *both(label, field))

    # The entry with definitions from two dictionaries.
    for label, order, step in (('T6', [JITENDEX, JMNEDICT], 'C5.1'), ('T6b', [JMNEDICT, JITENDEX], 'C5.3')):
        if label not in pairs:
            continue
        for platform, note in zip(('desktop', 'plugin'), pairs[label]):
            items = glossary_items(note.get('Glossary'))
            if items is None:
                report.add(step, 'FAIL', label, '%s Glossary has no yomitan-glossary <ol>' % platform)
                continue
            dictionaries = [item.attrs.get('data-dictionary') for item in items if item.tag == 'li']
            report.add(step, 'PASS' if dictionaries == order else 'FAIL', label,
                       '%s definitions in order %s' % (platform, dictionaries))
            if label == 'T6':
                sequence = [item.tag for item in items]
                styles_ok = sequence == ['li', 'style', 'li']
                report.add('C5.1', 'PASS' if styles_ok else 'FAIL', label,
                           '%s <ol> children %s, expected li, style, li (no <style> after JMnedict)' % (
                               platform, sequence))
                jmnedict = [item for item in items if item.attrs.get('data-dictionary') == JMNEDICT]
                glosses = [li.text() for element in (jmnedict[0].descendants() if jmnedict else [])
                           if element.tag == 'ul' for li in element.elements() if li.tag == 'li']
                expected = ['Kyōto (city, prefecture)', 'Kyoto']
                report.add('C5.1', 'PASS' if glosses == expected else 'FAIL', label,
                           '%s JMnedict glosses %s' % (platform, glosses))
    if 'T6' in pairs:
        for platform, note in zip(('desktop', 'plugin'), pairs['T6']):
            plain = note.get('glossary-plain')
            first, second = plain.find('(%s)<br>' % JITENDEX), plain.find('(%s)<br>' % JMNEDICT)
            report.add('C5.2', 'PASS' if 0 <= first < second else 'FAIL', 'T6',
                       '%s glossary-plain has the Jitendex line, then the JMnedict line' % platform)


def check_kanji_notes(report, desktop, plugin):
    pairs = pair(report, desktop, plugin, 'AuditKanji', KANJI_NOTES)
    expected = {
        'K1': {'Character': '食', 'Kunyomi': 'く.う, く.らう, た.べる, は.む', 'Onyomi': 'ショク, ジキ',
               'onyomi-hiragana': 'しょく, じき', 'stroke-count': 'Stroke count: 9', 'search-query': '食べる'},
        'K2': {'Character': '動', 'Kunyomi': 'うご.く, うご.かす', 'Onyomi': 'ドウ',
               'stroke-count': 'Stroke count: 11', 'search-query': '動き出す'},
    }
    rows = {'Character': 'C6.1', 'Kunyomi': 'C6.2', 'Onyomi': 'C6.3', 'Glossary': 'C6.4',
            'onyomi-hiragana': 'C6.5', 'stroke-count': 'C6.6', 'search-query': 'C6.7'}
    for label, (d, p) in pairs.items():
        for field in d.fields:
            d_value, p_value = d.get(field), p.get(field)
            step = rows.get(field, 'C6.8' if label == 'K2' else 'C6.9')
            if field == 'onyomi-hiragana' and label != 'K1':
                step = 'C6.10'
            want = expected[label].get(field)
            if field in ('Kunyomi', 'Onyomi'):
                status = 'PASS' if p_value == want else 'FAIL'
                report.add(step, status, label, 'plugin %s is %r (desktop recorded)' % (field, want), d_value, p_value)
                rule = 'plugin exact; desktop recorded'
            elif field == 'onyomi-hiragana' and label == 'K1':
                status = 'PASS' if p_value == want else 'FAIL'
                if status == 'PASS' and d_value != p_value:
                    status = 'REVIEW'
                report.add(step, status, label, 'plugin %s is %r and matches desktop\'s' % (field, want),
                           d_value, p_value)
                rule = 'plugin exact; matches desktop'
            elif field in KANJI_SENTENCES[label]:
                want = KANJI_SENTENCES[label][field]
                expect_equal(report, step, label, field, want, d_value, p_value)
                status = 'PASS' if d_value == p_value == want else 'FAIL'
                rule = 'exact'
            elif want is not None:
                expect_equal(report, step, label, field, want, d_value, p_value)
                status = 'PASS' if d_value == p_value == want else 'FAIL'
                rule = 'exact'
            else:
                rule = 'identical'
                status = 'PASS' if d_value == p_value else 'FAIL'
                report.add(step if field in rows else 'C6.10', status, label, '%s identical on both' % field,
                           None if status == 'PASS' else d_value, None if status == 'PASS' else p_value)
            report.field(label, field, rule, status, d_value, p_value)


def check_structured_content(report, desktop, plugin):
    specs = dict(SECTION5_NOTES)
    specs['T1'] = TERM_NOTES['T1']
    specs['T3'] = TERM_NOTES['T3']
    pairs = pair(report, desktop, plugin, 'AuditTerm', specs)
    word_steps = {'T1': 'C7.2', 'T3': 'C7.3', '時雨': 'C7.4', '鳥居': 'C7.5', '千鳥': 'C7.6', '順路': 'C7.7',
                  '記号': 'C7.8', '學校': 'C7.9'}

    for label, (d, p) in pairs.items():
        d_value, p_value = d.get('Glossary'), p.get('Glossary')
        compare_styled(report, word_steps[label], label, 'Glossary', d_value, p_value)
        report.field(label, 'Glossary', 'identical apart from <style> text and images', '', d_value, p_value)
        if label == '學校':
            continue
        for platform, value in (('desktop', d_value), ('plugin', p_value)):
            items = glossary_items(value)
            root = parse_html(value)
            wrapper = next((element for element in root.descendants() if element.tag == 'div'
                            and 'yomitan-glossary' in element.attrs.get('class', '').split()), None)
            definitions = [item for item in (items or []) if item.tag == 'li']
            prefixes = [li.elements()[0] if li.elements() else None for li in definitions]
            ok = (wrapper is not None and wrapper.attrs.get('style') == 'text-align: left;' and definitions
                  and all(li.attrs.get('data-dictionary') == JITENDEX for li in definitions)
                  and all(i is not None and i.tag == 'i' and i.text().startswith('(')
                          and i.text().endswith('%s)' % JITENDEX) for i in prefixes))
            report.add('C7.1', 'PASS' if ok else 'FAIL', label,
                       '%s wrapper: div.yomitan-glossary, <ol>, <li data-dictionary>, <i>(...)</i> prefix' % platform)

    def bodies(label):
        return [split_styles(note.get('Glossary'))[0] for note in pairs[label]]

    if 'T3' in pairs:
        d_links, p_links = [internal_link_targets(body) for body in bodies('T3')]
        ok = bool(d_links) and d_links == p_links and set(d_links) == {'#'}
        report.add('C7.3', 'PASS' if ok else 'FAIL', 'T3', 'every internal link\'s href is # on both',
                   None if ok else '\n'.join(d_links), None if ok else '\n'.join(p_links))
    if '時雨' in pairs:
        ok = all('colspan="2"' in body for body in bodies('時雨'))
        report.add('C7.4', 'PASS' if ok else 'FAIL', '時雨', 'colspan="2" present on both')
    for label, step in (('鳥居', 'C7.5'), ('千鳥', 'C7.6')):
        if label not in pairs:
            continue
        d_body, p_body = bodies(label)
        p_images = IMAGE_PATTERN.findall(p_body)
        ok = bool(p_images) and all(' width=' in img and ' height=' in img and ' src=' not in img for img in p_images)
        report.add(step, 'PASS' if ok else 'FAIL', label, 'plugin <img> has width and height and no src',
                   None, '\n'.join(p_images))
        report.add(step, 'RECORD', label, 'desktop image (known gap: desktop stores and links the real image)',
                   '\n\n'.join(IMAGE_PATTERN.findall(d_body)), None)
    if '順路' in pairs:
        ok = all('-&gt;' in body for body in bodies('順路'))
        report.add('C7.7', 'PASS' if ok else 'FAIL', '順路', '-> written as -&gt; on both')
    if '記号' in pairs:
        ok = all('\'&amp;\' stands for' in body for body in bodies('記号'))
        report.add('C7.8', 'PASS' if ok else 'FAIL', '記号', '& written as &amp; on both')
    if '學校' in pairs:
        report.add('C7.9', 'RECORD', '學校', 'compare the popup entries by hand; the Glossary result is above')

    if 'T1' in pairs:
        d, p = pairs['T1']
        p_styles = split_styles(p.get('Glossary'))[1]
        items = glossary_items(p.get('Glossary')) or []
        sequence = [item.tag for item in items]
        follows = bool(sequence) and all(
            tag != 'li' or (index + 1 < len(sequence) and sequence[index + 1] == 'style')
            for index, tag in enumerate(sequence))
        report.add('C7.10', 'PASS' if follows else 'FAIL', 'T1', 'plugin: a <style> follows each <li> (%s)' % sequence)
        structures = [css_structure(css) for css in p_styles]
        parts = [part for structure in structures for part in top_level_selector_parts(structure)]
        prefixed = bool(parts) and all(part.startswith(JITENDEX_SCOPE + ' ') for part in parts)
        report.add('C7.10', 'PASS' if prefixed else 'FAIL', 'T1',
                   'plugin: every top-level selector part is prefixed with %s' % JITENDEX_SCOPE,
                   None, None if prefixed else '\n'.join(part for part in parts if not part.startswith(JITENDEX_SCOPE)))
        nested = [child for structure in structures for item in structure if item[0] == 'rule'
                  for child in item[2] if child[0] == 'rule']
        nested_ok = bool(nested) and all(child[1].startswith('&') for child in nested)
        report.add('C7.10', 'PASS' if nested_ok else 'FAIL', 'T1',
                   'plugin: nested rules (%d) are still inside their parent rules' % len(nested))
        no_comments = all('/*' not in css for css in p_styles)
        report.add('C7.10', 'PASS' if no_comments else 'FAIL', 'T1', 'plugin: no CSS comments in the <style>')
        d_styles = split_styles(d.get('Glossary'))[1]
        same = [css_structure(a) == css_structure(b) for a, b in zip(d_styles, p_styles)]
        if len(d_styles) == len(p_styles) and all(same):
            report.add('C7.11', 'PASS', 'T1', 'same rules, selectors, and property names on both')
        else:
            report.add('C7.11', 'REVIEW', 'T1', 'rules, selectors, or property names differ: compare by hand',
                       '\n\n'.join(d_styles), '\n\n'.join(p_styles))
        for platform, note in zip(('desktop', 'plugin'), pairs['T1']):
            check_scoped_first_style(report, 'C7.12', 'T1', 'glossary-first', note.get('glossary-first'), platform)

def check_overwrite(report, plugin):
    notes = [note for note in plugin if note.model == 'AuditTerm' and note.first == OVERWRITE_WORD]
    if len(notes) != 2:
        report.add('C8.2', 'FAIL', OVERWRITE_WORD, 'expected exactly 2 %s notes on the plugin (9.2, overwritten '
                   'in 9.4, and 9.5), found %d' % (OVERWRITE_WORD, len(notes)))
    if not notes:
        return

    def doubled(value):
        half = len(value) // 2
        return bool(value) and len(value) % 2 == 0 and value[:half] == value[half:]

    first = notes[0].get('Glossary')
    report.add('C8.1', 'PASS' if doubled(first) else 'FAIL', OVERWRITE_WORD,
               'the first note\'s Glossary is the original render followed by a second copy (Append)', None, first)
    report.add('C8.1', 'RECORD', OVERWRITE_WORD, 'the other fields render the same on every add, so an unchanged '
               'value cannot be told apart from an overwritten one; not checked')
    if len(notes) >= 2:
        second = notes[1].get('Glossary')
        report.add('C8.2', 'PASS' if second and not doubled(second) else 'FAIL', OVERWRITE_WORD,
                   'the second note is a new note with a single render', None, second)

def check_tags_and_markers(report, desktop, plugin):
    wanted = ['core2k', 'jlpt-n3', 'listening', 'vocab']
    for platform, notes in (('desktop', desktop), ('plugin', plugin)):
        tagged = [note for note in notes if 'jlpt-n3' in note.tags]
        if len(tagged) != 1:
            report.add('C9.1', 'FAIL', '食べる', 'expected exactly 1 note tagged jlpt-n3 on %s, found %d' % (
                platform, len(tagged)))
        else:
            tags = tagged[0].tags
            report.add('C9.1', 'PASS' if tags == wanted else 'FAIL', '食べる',
                       '%s tags are exactly %s' % (platform, ', '.join(wanted)),
                       ' '.join(tags) if platform == 'desktop' else None, ' '.join(tags) if platform == 'plugin' else None)
        # The unknown-marker note is the fourth 食べる note each add pass makes (after T1, T4, and the
        # tagged note).
        bad_marker = find_note(notes, 'AuditTerm', '食べる', 4)
        if bad_marker is None:
            report.add('C10.1', 'MISSING', '食べる', 'no fourth 食べる note on %s' % platform)
            continue
        reading = bad_marker.get('Reading')
        report.add('C10.1', 'PASS' if reading == '{expresion-render-error}' else 'FAIL', '食べる',
                   '%s fourth 食べる note (AuditBadMarker) has Reading {expresion-render-error}' % platform,
                   reading if platform == 'desktop' else None, reading if platform == 'plugin' else None)


def check_plugin_settings(report, state):
    """The plugin's stored Anki settings against R2's formats and the settings the plan ends with."""
    settings = state.get('ankiSettings')
    if not settings:
        report.add('C1.2', 'MISSING', 'settings', 'the plugin has no stored Anki settings')
        return
    formats = settings.get('cardFormats', [])
    names = [card_format.get('name') for card_format in formats]
    expected_names = [spec[0] for spec in CARD_FORMATS]
    report.add('C1.1', 'PASS' if names == expected_names else 'FAIL', 'formats',
               'the plugin has formats %s, expected %s in this order' % (names, expected_names))
    for (name, entry_type, deck, note_type, icon, changes, overwrite), stored in zip(CARD_FORMATS, formats):
        if stored.get('name') != name:
            continue
        actual = (stored.get('type'), stored.get('deckName'), stored.get('modelName'), stored.get('icon'))
        wanted = (entry_type.upper(), deck, note_type, PLUGIN_ICONS[icon])
        report.add('C1.2', 'PASS' if actual == wanted else 'FAIL', name,
                   'type, deck, note type, and icon are %s, expected %s' % (actual, wanted))
        stored_fields = stored.get('fields', {})
        values = field_values(note_type, changes)
        wrong = {field: stored_fields.get(field, {}).get('value') for field, value in values.items()
                 if stored_fields.get(field, {}).get('value') != value}
        extra = sorted(set(stored_fields) - set(values))
        report.add('C1.3', 'FAIL' if wrong or extra else 'PASS', name,
                   'field values differ from R2: %s%s' % (wrong, '; fields not in the note type: %s' % extra
                                                          if extra else '') if wrong or extra
                   else 'every field value is as in R2')
        modes = {field: stored_fields.get(field, {}).get('overwriteMode') for field in values}
        wanted_modes = {field: overwrite.get(field, 'coalesce').upper() for field in values}
        wrong_modes = {field: mode for field, mode in modes.items() if mode != wanted_modes[field]}
        report.add('C1.4', 'FAIL' if wrong_modes else 'PASS', name,
                   'overwrite modes differ from R2: %s' % wrong_modes if wrong_modes
                   else 'every overwrite mode is as in R2')
    final = {'enabled': True, 'checkForDuplicates': True, 'duplicateScope': 'COLLECTION',
             'duplicateScopeCheckAllModels': False, 'duplicateBehavior': 'ALWAYS_ADD'}
    actual = {key: settings.get(key) for key in final}
    report.add('C1.5', 'PASS' if actual == final else 'FAIL', 'settings',
               'the plugin ends with %s, expected %s' % (actual, final))
    report.add('C1.6', 'PASS' if settings.get('tags') == [] else 'FAIL', 'settings',
               'the plugin\'s card tags are %s, expected none' % settings.get('tags'))


def check_plugin_dictionaries(state):
    """Problems with the plugin's dictionaries: missing, wrong revision, wrong order, sorting on."""
    problems = []
    installed = {item['title']: item['revision'] for item in state.get('dictionaries', [])}
    for title, revision in DICTIONARIES:
        if title not in installed:
            problems.append('%s is not installed' % title)
        elif installed[title] != revision:
            problems.append('%s has revision %s, expected %s' % (title, installed[title], revision))
    active = state.get('activeDictionaries', [])
    expected = [title for title, _ in DICTIONARIES]
    if active != expected:
        problems.append('the enabled dictionaries, in order, are %s; expected exactly %s' % (active, expected))
    if state.get('sortFrequencyDictionary'):
        problems.append('results are sorted by %s; no sort dictionary may be chosen' % state['sortFrequencyDictionary'])
    return problems


def yomitan_settings(export_path):
    """The export at export_path, changed to the plan's desktop configuration."""
    with open(export_path, encoding='utf-8') as source:
        settings = json.load(source)
    options = settings['options']
    profile = options['profiles'][options['profileCurrent']]['options']

    by_name = {entry['name']: entry for entry in profile['dictionaries']}
    missing = [title for title, _ in DICTIONARIES if title not in by_name]
    if missing:
        raise RuntimeError('Import these dictionaries into desktop Yomitan first: %s' % ', '.join(missing))
    pinned = [dict(by_name[title], enabled=True) for title, _ in DICTIONARIES]
    others = [dict(entry, enabled=False) for entry in profile['dictionaries']
              if entry['name'] not in dict(DICTIONARIES)]
    profile['dictionaries'] = pinned + others
    profile['general']['sortFrequencyDictionary'] = None

    anki = profile['anki']
    anki.update({'enable': True, 'checkForDuplicates': True, 'duplicateScope': 'collection',
                 'duplicateScopeCheckAllModels': False, 'duplicateBehavior': 'new'})
    anki['cardFormats'] = [
        {'name': name, 'type': entry_type, 'deck': deck, 'model': note_type, 'icon': icon,
         'fields': {field: {'value': value, 'overwriteMode': overwrite.get(field, 'coalesce')}
                    for field, value in field_values(note_type, changes).items()}}
        for name, entry_type, deck, note_type, icon, changes, overwrite in CARD_FORMATS
    ]
    return settings


# ---------------------------------------------------------------------------------------------
# Commands


def step(message):
    print('\n== %s' % message)
    sys.stdout.flush()


def command_setup(args, folder):
    step('Checking the device and AnkiConnect')
    run_adb(args.adb, args.serial, ['get-state'])
    print('AnkiConnect version %s' % anki_connect(args.anki_connect, 'version'))

    if not args.no_build:
        step('Building the plugin and the test helper')
        gradle = os.path.join(REPO, 'gradlew.bat' if os.name == 'nt' else 'gradlew')
        subprocess.run([gradle, 'assembleDebug', 'assembleDebugAndroidTest'], cwd=REPO, check=True)
    step('Installing both APKs (-r keeps the plugin\'s dictionaries and settings)')
    for apk in (PLUGIN_APK, HELPER_APK):
        print(run_adb(args.adb, args.serial, ['install', '-r', '-t', apk]).strip())

    step('Looking for notes left in AuditTest')
    desktop_ids = anki_connect(args.anki_connect, 'findNotes', query=DECK_QUERY)
    plugin_count = len(run_helper(args.adb, args.serial, 'dumpNotes', query=DECK_QUERY)['notes'])
    print('desktop: %d, Android: %d' % (len(desktop_ids), plugin_count))
    if desktop_ids or plugin_count:
        if not args.clean:
            print('AuditTest must start empty. Run setup again with --clean to delete these notes on both platforms.')
            return 1
        if desktop_ids:
            anki_connect(args.anki_connect, 'deleteNotes', notes=desktop_ids)
        deleted = run_helper(args.adb, args.serial, 'deleteNotes', query=DECK_QUERY)['deleted']
        print('Deleted %d desktop and %d Android notes' % (len(desktop_ids), deleted))

    step('Creating the decks and note types in desktop Anki')
    problems = []
    for deck in DECKS:
        anki_connect(args.anki_connect, 'createDeck', deck=deck)
    existing = anki_connect(args.anki_connect, 'modelNames')
    for spec in NOTE_TYPES:
        if spec['name'] in existing:
            fields = anki_connect(args.anki_connect, 'modelFieldNames', modelName=spec['name'])
            state = 'exists'
        else:
            anki_connect(args.anki_connect, 'createModel', modelName=spec['name'], inOrderFields=spec['fields'],
                         cardTemplates=[{'Name': 'Card 1', 'Front': spec['front'], 'Back': spec['back']}])
            fields, state = spec['fields'], 'created'
        print('%s: %s' % (spec['name'], state))
        if fields != spec['fields']:
            problems.append('desktop note type %s has fields %s, expected %s' % (spec['name'], fields, spec['fields']))

    step('Creating the decks and note types in AnkiDroid')
    spec = base64.b64encode(json.dumps({'decks': DECKS, 'models': NOTE_TYPES}).encode('utf-8')).decode('ascii')
    result = run_helper(args.adb, args.serial, 'setupCollection', spec=spec)
    for note_type in NOTE_TYPES:
        created = result['models'].get(note_type['name'], {})
        print('%s: %s' % (note_type['name'], 'created' if created.get('created') else 'exists'))
        if created.get('fields') != note_type['fields']:
            problems.append('Android note type %s has fields %s, expected %s' % (
                note_type['name'], created.get('fields'), note_type['fields']))

    step('Resetting the Anki settings of the plugin to the defaults')
    run_helper(args.adb, args.serial, 'resetAnkiSettings')

    step('Checking the plugin\'s dictionaries')
    problems += check_plugin_dictionaries(run_helper(args.adb, args.serial, 'dumpPluginState'))

    step('Writing the desktop Yomitan settings to import')
    settings_path = os.path.join(folder, 'yomitan-settings-anki-test-plan.json')
    with open(settings_path, 'w', encoding='utf-8') as out:
        json.dump(yomitan_settings(args.yomitan_settings), out, ensure_ascii=False, indent=1)
    print(os.path.abspath(settings_path))

    if problems:
        step('Setup found problems')
        for problem in problems:
            print('FAIL: %s' % problem)
        return 1
    step('Setup complete')
    print('Import %s into desktop Yomitan in step 4.1.' % os.path.abspath(settings_path))
    return command_serve(args, folder)


IMAGE_NAME = 'anki-test-image.png'


def test_image_png(width=240, height=160):
    """A small PNG of colored stripes, which step 13.11 copies in Chrome to put an image on the
    clipboard."""
    rows = b''.join(b'\x00' + b''.join(bytes((40 + (x // 30) * 25 % 200, 120, 200 - y)) for x in range(width))
                    for y in range(height))

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))


def start_page_server(port):
    """Serves the test page on port, in a background thread, and returns the server.

    Besides the page and an image for step 13.11 to copy (IMAGE_NAME), the server relays between
    scripts/anki_test_plan_android.py and the page in Chrome on the device (see PAGE_SCRIPT):
    POST /control/command takes {"line", "text", "scale"} and returns the command's {"id"}; the
    page polls GET /control/command?after=<id> for a newer command and posts where the text is to
    POST /control/layout, which GET /control/layout?id=<id> then returns.
    """
    page = PAGE_HTML.encode('utf-8')
    image = test_image_png()
    state = {'command': None, 'layouts': {}}
    lock = threading.Lock()

    class Handler(http.server.BaseHTTPRequestHandler):
        def reply(self, status, body=b'', content_type='application/json'):
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path, _, query = self.path.partition('?')
            number = int(query.split('=', 1)[1]) if '=' in query else 0
            with lock:
                if path == '/' + PAGE_NAME:
                    self.reply(200, page, 'text/html; charset=utf-8')
                elif path == '/' + IMAGE_NAME:
                    self.reply(200, image, 'image/png')
                elif path == '/control/command' and state['command'] and state['command']['id'] > number:
                    self.reply(200, json.dumps(state['command']).encode('utf-8'))
                elif path == '/control/layout' and number in state['layouts']:
                    self.reply(200, json.dumps(state['layouts'][number]).encode('utf-8'))
                elif path in ('/control/command', '/control/layout'):
                    self.reply(204)
                else:
                    self.send_error(404)

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))).decode('utf-8'))
            with lock:
                if self.path == '/control/command':
                    command_id = (state['command']['id'] if state['command'] else 0) + 1
                    state['command'] = {'id': command_id, 'line': body['line'], 'text': body['text'],
                                        'scale': body.get('scale', 1)}
                    self.reply(200, json.dumps({'id': command_id}).encode('utf-8'))
                elif self.path == '/control/layout':
                    state['layouts'][body['id']] = body
                    self.reply(204)
                else:
                    self.send_error(404)

        def log_message(self, *_):
            pass

    server = http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def open_page_on_device(adb, serial, port):
    """Makes the device reach the test page at localhost and opens it in Chrome."""
    url = 'http://localhost:%d/%s' % (port, PAGE_NAME)
    run_adb(adb, serial, ['reverse', 'tcp:%d' % port, 'tcp:%d' % port])
    run_adb(adb, serial, ['shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d', url,
                          '-p', 'com.android.chrome'])
    return url


def command_serve(args, folder):
    step('Serving the test page')
    server = start_page_server(args.port)
    url = open_page_on_device(args.adb, args.serial, args.port)
    webbrowser.open(url)
    print('Opened %s in the desktop browser and in Chrome on the device.' % url)
    print('Leave this running until Phase 13 is done (every lookup scans this page), then press Enter.')
    try:
        input()
    except (EOFError, KeyboardInterrupt):
        pass
    server.shutdown()
    run_adb(args.adb, args.serial, ['reverse', '--remove', 'tcp:%d' % args.port])
    return 0


def command_check(args, folder):
    desktop = desktop_notes(args.anki_connect)
    state = run_helper(args.adb, args.serial, 'dumpPluginState')
    plugin = android_notes(args.adb, args.serial)
    for name, content in (('desktop-notes.json', [note.to_json() for note in desktop]),
                          ('android-notes.json', [note.to_json() for note in plugin]),
                          ('android-plugin-state.json', state)):
        with open(os.path.join(folder, name), 'w', encoding='utf-8') as out:
            json.dump(content, out, ensure_ascii=False, indent=1)

    report = Report()
    check_plugin_settings(report, state)
    check_term_notes(report, desktop, plugin)
    check_kanji_notes(report, desktop, plugin)
    check_structured_content(report, desktop, plugin)
    check_overwrite(report, plugin)
    check_tags_and_markers(report, desktop, plugin)
    report.print_summary()
    report.write(folder)
    print('\nReport: %s' % os.path.join(folder, 'report.md'))
    return 1 if report.failed() else 0


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--anki-connect', default='http://127.0.0.1:8765', help='AnkiConnect URL')
    parser.add_argument('--adb', default='adb', help='path to adb')
    parser.add_argument('--serial', help='adb device serial, when several devices are connected')
    parser.add_argument('--out', default='anki-test-plan-reports', help='folder for results')
    parser.add_argument('--port', type=int, default=8766, help='port the test page is served on')
    commands = parser.add_subparsers(dest='command', required=True)
    setup = commands.add_parser('setup', help='prepare both platforms before the manual steps')
    setup.add_argument('--yomitan-settings', required=True,
                       help='a settings file exported from desktop Yomitan (Settings, Backup, Export)')
    setup.add_argument('--clean', action='store_true', help='delete every note in AuditTest on both platforms')
    setup.add_argument('--no-build', action='store_true', help='install the APKs already built')
    commands.add_parser('serve', help='serve the test page again')
    commands.add_parser('check', help='check the notes and settings after the manual steps')
    args = parser.parse_args()

    folder = os.path.join(args.out, '%s-%s' % (datetime.datetime.now().strftime('%Y%m%d-%H%M%S'), args.command))
    os.makedirs(folder, exist_ok=True)
    try:
        return {'setup': command_setup, 'serve': command_serve, 'check': command_check}[args.command](args, folder)
    except (RuntimeError, subprocess.CalledProcessError, OSError) as error:
        print('\nERROR: %s' % error)
        return 1


if __name__ == '__main__':
    sys.exit(main())
