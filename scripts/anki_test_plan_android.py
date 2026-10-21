#!/usr/bin/env python3
"""Runs the Anki export test plan (docs/anki-export/test-plan.md) on a connected device; see
docs/anki-export/test-plan-runner.md. The plan runs on Android only, and this script does every
step of it, from setup (2.1) on, and checks what each step asks to confirm.

  python scripts/anki_test_plan_android.py run                 every step, from 1.1
  python scripts/anki_test_plan_android.py run --from 6.1      from step 6.1 on
  python scripts/anki_test_plan_android.py run --steps 6.1,6.2 just these steps
  python scripts/anki_test_plan_android.py run --pro no        on a device without Dokuen Pro
  python scripts/anki_test_plan_android.py list                every step, and its configuration
  python scripts/anki_test_plan_android.py review <folder>     answer a run's questions later

A run is one configuration (the plan's R10): the host's SDK level, which the plugin records and
this script reads after a scan, and, on a host at SDK 0.7.0 or later, whether Dokuen Pro is
active, which --pro names or the first add shows (an add shows a message about the image fields
without Dokuen Pro). Phases 11, 12, and 13 are each for one configuration; the others are skipped.

The script drives Chrome, Dokuen, the plugin's dashboard, AnkiDroid, and Android's dialogs through
uiautomator2 (`pip install uiautomator2`), which installs its agent on the device the first
time it connects. Dokuen's overlay draws the scanned text without any accessibility nodes, so a
scan finds where the word is on the screen from the test page itself: the page in Chrome reports
the position of each character of a line the script names (see PAGE_SCRIPT in
anki_test_plan.py), and the script drags across exactly those characters on the overlay. It then
reads what the plugin looked up from the plugin's log, and when the OCR misread the word, scans it
again with its line enlarged, as the plan has the tester zoom in.

Notes are read, added, and deleted in AnkiDroid through the plugin's test helper, as
anki_test_plan.py does. Dokuen connects to the plugin only when its session starts, and every run
of the helper restarts the plugin's process, so the script starts a new session before its first
scan and before the first scan after each run of the helper (as step 8.3 does).

Besides the plan's Phase 1, the prerequisites are that the device's language is English and that adb
reaches the device over USB, since 10.6 turns the device's network off. If nothing serves the test
page, this script serves it itself.

What only a person can judge (an image in AnkiDroid, or a check result the plan's R9 may list) is
asked at the end of the run, all at once, with review.html showing each question's images; a run
with --unattended leaves those questions PENDING for the review command.

Every run writes report.md, report.json, and screenshots to a new folder under --out, and exits
with 1 if any step failed.
"""

import argparse
import base64
import contextlib
import datetime
import html
import http.client
import json
import os
import re
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.request
import webbrowser
import xml.etree.ElementTree as ElementTree

import anki_test_plan as plan

try:
    import uiautomator2
    import uiautomator2.exceptions
except ImportError:
    uiautomator2 = None

HOST = 'io.github.dokuendev.dokuenreader'
# The main class of uiautomator2's agent, as its process on the device shows it.
AGENT_MAIN_CLASS = 'com.wetest.uia2.Main'
CHROME = 'com.android.chrome'
ANKIDROID = 'com.ichi2.anki'
PLAY_STORE = 'com.android.vending'
PLAN_FILE = os.path.join(plan.REPO, 'docs', 'anki-export', 'test-plan.md')
PAGE_PATH = '/' + plan.PAGE_NAME
# The dashboard's fixed footer, which covers the bottom of each tab's list.
FOOTER_TEXT = 'Launch Dokuen Japanese Reader'
# A text each dashboard tab shows at its top, and no other tab does.
TAB_MARKERS = {'Dictionaries': 'Installed Dictionaries', 'Anki Export': 'Enable Anki export'}
# The texts of the Anki Export tab's Audio card, its last card.
AUDIO_TITLE = 'Audio'
AUDIO_EXPLANATION = ('When searching for audio, the sources are checked in order until the first valid source is '
                     'found. This allows for selecting a fallback source if the first choice is not available.')
# Desktop's text for an empty source list, which desktop's stylesheet always hides. The Audio card
# must not show it, since desktop shows nothing in place of an empty list.
AUDIO_EMPTY = 'No audio sources enabled'
AUDIO_SOURCE_LABEL = re.compile(r'^Source (\d+)$')
AUDIO_URL_LABEL = 'URL'
AUDIO_ADD = 'Add source'
AUDIO_REMOVE = 'Remove source'
AUDIO_DEFAULT_SOURCES = 'Enable Default Audio Sources'
# How far, in dp, the center of the Enable Default Audio Sources switch may be from its label's. The
# switch is centered on the label and the description below it together, which puts it further from
# the label's center than the other switches are from theirs.
AUDIO_SWITCH_REACH = 60
AUDIO_TIMEOUT = 'Idle download timeout (in milliseconds)'
AUDIO_TIMEOUT_DESCRIPTION = 'The maximum time before an idle download will be cancelled; 0 = no limit.'

# How far above the screen's bottom edge a drag may start: below it is Android's gesture bar,
# where a drag goes home.
SCREEN_BOTTOM_MARGIN = 160
# The height of a headword's line in the dictionary window, in dp.
HEADWORD_HEIGHT = (28, 38)
# The characters a headword is written in: kana, kanji (with 々 and 〆), and the long vowel mark.
HEADWORD_TEXT = re.compile('^[ぁ-ヿ㐀-䶿一-鿿々〆]+$')
LINK_PATTERN = re.compile(r'^(\S)\s+(Add|Added|Overwritten): (.+)$')
# The link colors of R7, as the plugin draws them (AnkiLinkStatus in
# YomitanDictionaryPluginService.kt), and how far a sampled pixel may be from one to count as it.
LINK_COLORS = {'green': (0x6F, 0xB5, 0x58), 'purple': (0xD0, 0x8A, 0xE7), 'orange': (0xFF, 0x87, 0x27),
               'gray': (0x9F, 0x9F, 0x9F)}
COLOR_TOLERANCE = 70


class StepFailed(Exception):
    """A check of the current step does not hold; the run records it and goes on to the next step."""


# ---------------------------------------------------------------------------------------------
# The device


class Node:
    """One node of a UI dump."""

    def __init__(self, attributes):
        self.text = attributes.get('text', '')
        self.desc = attributes.get('content-desc', '')
        self.rid = attributes.get('resource-id', '').split('/')[-1]
        self.cls = attributes.get('class', '').split('.')[-1]
        self.package = attributes.get('package', '')
        left, top, right, bottom = map(int, re.findall(r'-?\d+', attributes.get('bounds', '[0,0][0,0]')))
        self.bounds = (left, top, right, bottom)
        for flag in ('clickable', 'checkable', 'checked', 'enabled', 'focused', 'scrollable', 'selected'):
            setattr(self, flag, attributes.get(flag) == 'true')

    left = property(lambda self: self.bounds[0])
    top = property(lambda self: self.bounds[1])
    right = property(lambda self: self.bounds[2])
    bottom = property(lambda self: self.bounds[3])
    width = property(lambda self: self.bounds[2] - self.bounds[0])
    height = property(lambda self: self.bounds[3] - self.bounds[1])
    center = property(lambda self: ((self.left + self.right) // 2, (self.top + self.bottom) // 2))

    def contains(self, other):
        return (self.left <= other.left and self.top <= other.top and self.right >= other.right
                and self.bottom >= other.bottom)

    def __repr__(self):
        return '<%s %r %r %s>' % (self.cls, self.text or self.desc, self.rid, self.bounds)


class Device:
    def __init__(self, serial, folder):
        if uiautomator2 is None:
            raise RuntimeError('uiautomator2 is not installed: run "pip install uiautomator2"')
        self.serial = serial
        self.u2 = uiautomator2.connect(serial)
        self.folder = folder
        self.shots = 0
        # Reports something the tester should know. The run replaces it with its own warnings.
        self.warn = lambda message: print('WARNING: %s' % message)
        self.width, self.height = self.u2.window_size()
        # Pixels per density-independent pixel, for sizes that do not depend on the screen.
        self.density = self.width / self.u2.info['displaySizeDpX']

    def agent(self, call, *arguments, **keywords):
        """Calls uiautomator2, once more if the connection to its agent on the device drops (the
        agent then restarts)."""
        try:
            return call(*arguments, **keywords)
        except (OSError, http.client.HTTPException, uiautomator2.exceptions.BaseException) as error:
            print('    (uiautomator2: %s; retrying)' % type(error).__name__)
            time.sleep(2)
            return call(*arguments, **keywords)

    def nodes(self):
        root = ElementTree.fromstring(self.agent(self.u2.dump_hierarchy))
        return [Node(element.attrib) for element in root.iter('node')]

    def wait(self, find, timeout=10.0, interval=0.4):
        """Calls find with fresh nodes until it returns something truthy; returns that, or None."""
        deadline = time.time() + timeout
        while True:
            nodes = self.nodes()
            if self.dismiss_interruption(nodes):
                nodes = self.nodes()
            found = find(nodes)
            if found or time.time() > deadline:
                return found
            time.sleep(interval)

    def wait_settled(self, find, timeout=10.0, interval=0.4):
        """Waits as wait does for a node, and returns it once two looks in a row find it at the same
        place, so that a tap does not land while a screen still animates in (an app's launch scales
        its first screen up from its icon). When the node never holds still, returns the last one
        found, and None only when none was found."""
        deadline = time.time() + timeout
        last = None
        while True:
            node = self.wait(find, max(0.0, deadline - time.time()), interval)
            if node is None or (last is not None and node.bounds == last.bounds):
                return node or last
            last = node
            if time.time() > deadline:
                return node
            time.sleep(interval)

    def dismiss_interruption(self, nodes):
        """Dismisses a prompt that can appear over any app and that no step expects, such as Google
        Play asking for a review of Dokuen. Returns whether it dismissed one."""
        if any(node.package == PLAY_STORE and 'Reviews are only visible' in node.text for node in nodes):
            not_now = first([node for node in nodes if node.package == PLAY_STORE and node.text == 'Not now'])
            if not_now is not None:
                print('    (dismissed Google Play\'s review prompt)')
                self.tap(not_now)
                time.sleep(1)
                return True
        return False

    def tap(self, target):
        x, y = target.center if isinstance(target, Node) else target
        self.agent(self.u2.click, x, y)

    def long_press(self, target, duration=1.0):
        x, y = target.center if isinstance(target, Node) else target
        self.agent(self.u2.long_click, x, y, duration)

    def swipe(self, start, end, duration=0.4):
        self.agent(self.u2.swipe, start[0], start[1], end[0], end[1], duration=duration)

    def press(self, key):
        self.agent(self.u2.press, key)

    def shell(self, *command):
        return self.u2.shell(list(command)).output

    def foreground(self):
        return self.agent(self.u2.app_current).get('package')

    def resumed_package(self):
        """The package of the activity Android shows in front (its resumed activity), or None when
        the dump has none in a format this knows."""
        dump = self.shell('dumpsys', 'activity', 'activities')
        # Android 10 and later name the top one; earlier builds and some vendors use the others.
        for marker in ('topResumedActivity=', 'mResumedActivity: ', 'ResumedActivity: '):
            match = re.search(re.escape(marker) + r'ActivityRecord\{\S+ \S+ ([\w.]+)/', dump)
            if match:
                return match.group(1)
        self.warn('dumpsys shows no resumed activity in a known format, so the runner cannot tell which '
                  'app is in front; starting and stopping Dokuen\'s session may fail')
        return None

    def start_app(self, package):
        self.u2.app_start(package)

    def screenshot(self, label):
        self.shots += 1
        name = '%03d-%s.png' % (self.shots, re.sub(r'[^A-Za-z0-9.-]+', '-', label).strip('-'))
        image = self.u2.screenshot()
        image.save(os.path.join(self.folder, name))
        return name, image

    def release_ui(self):
        """Stops uiautomator2's agent on the device, so that something else can drive the UI.
        The next UI call starts it again."""
        self.u2.stop_uiautomator()
        for attempt in range(20):
            pids = re.findall(r'^\s*(\d+)\s.*' + re.escape(AGENT_MAIN_CLASS), self.shell('ps', '-A', '-o', 'PID,ARGS'),
                              re.M)
            if not pids:
                return
            if attempt == 10:
                self.shell('kill', *pids)
            time.sleep(0.5)
        raise RuntimeError('uiautomator2\'s agent on the device did not stop')

    def keyboard_shown(self):
        output = self.shell('dumpsys', 'input_method')
        return 'mInputShown=true' in output or 'isInputViewShown=true' in output

    def hide_keyboard(self):
        if self.keyboard_shown():
            self.press('back')
            time.sleep(0.5)

    def type_text(self, text):
        """Types ASCII text at the cursor, a character at a time, as the keyboard would."""
        for char in text:
            self.shell('input', 'text', '%s' if char == ' ' else char)


def by_text(nodes, text, cls=None):
    return [node for node in nodes if node.text == text and (cls is None or node.cls == cls)]


def by_desc(nodes, desc):
    return [node for node in nodes if node.desc == desc]


def first(found):
    return found[0] if found else None


# A device's serial matches this when adb reaches the device over the network. Such a serial is a
# host:port (adb connect, or wireless debugging connected by address), or the mDNS service name that
# wireless debugging connects through on Android 11 and later, which may end in a dot.
NETWORK_SERIAL = re.compile(r':\d+$|\._adb(-tls-connect)?\._tcp\.?$')


def over_network(serial):
    """Whether adb reaches the device with this serial over the network rather than over USB."""
    return NETWORK_SERIAL.search(serial) is not None


# ---------------------------------------------------------------------------------------------
# The test page


class TestPage:
    """The test page in Chrome on the device, through the page server's /control endpoints."""

    def __init__(self, device, port, adb, serial):
        self.device = device
        self.port = port
        self.adb = adb
        self.serial = serial
        self.base = 'http://127.0.0.1:%d' % port
        self.server = None

    def ensure_served(self):
        try:
            urllib.request.urlopen(self.base + '/control/layout?id=0', timeout=3)
        except (urllib.error.URLError, OSError):
            print('Nothing serves the test page on port %d; serving it until this run ends.' % self.port)
            self.server = plan.start_page_server(self.port)

    def request(self, path, body=None):
        data = None if body is None else json.dumps(body).encode('utf-8')
        with urllib.request.urlopen(urllib.request.Request(self.base + path, data), timeout=10) as response:
            return json.loads(response.read().decode('utf-8')) if response.status == 200 else None

    def bring_to_front(self):
        """Shows the test page in Chrome, opening it if Chrome shows something else."""
        nodes = self.device.nodes()
        url = first([node for node in nodes if node.rid == 'url_bar'])
        if self.device.foreground() == CHROME and url is not None and url.text.endswith(PAGE_PATH):
            return
        if self.device.foreground() != CHROME:
            self.device.start_app(CHROME)
            nodes = self.device.wait(lambda nodes: [node for node in nodes if node.rid == 'url_bar'], 10) or []
            url = first(nodes)
        if url is None or not url.text.endswith(PAGE_PATH):
            plan.open_page_on_device(self.adb, self.serial, self.port)
            time.sleep(2)

    def locate(self, line, text, scale=1):
        """Scrolls the page's line to the middle of the screen, scale times its usual size; returns
        the screen rectangle of each character of text in it, (left, top, right, bottom) in pixels."""
        for attempt in range(2):
            self.bring_to_front()
            command = self.request('/control/command', {'line': line, 'text': text, 'scale': scale})['id']
            deadline = time.time() + 8
            layout = None
            while layout is None and time.time() < deadline:
                time.sleep(0.3)
                layout = self.request('/control/layout?id=%d' % command)
            if layout is None:
                # The page in Chrome is not polling: an old copy of the page, or another tab.
                plan.open_page_on_device(self.adb, self.serial, self.port)
                time.sleep(3)
                continue
            if not layout['found']:
                raise StepFailed('The test page has no line %r' % line)
            # Chrome's page can be missing from the UI dump for a moment after it scrolls.
            web_view = self.device.wait(lambda nodes: first([node for node in nodes
                                                             if node.cls == 'WebView' and node.package == CHROME]), 5)
            if web_view is None:
                raise StepFailed('Chrome shows no web page')
            factor = layout['dpr'] * layout['scale']
            return [(web_view.left + (left - layout['offsetLeft']) * factor,
                     web_view.top + (top - layout['offsetTop']) * factor,
                     web_view.left + (right - layout['offsetLeft']) * factor,
                     web_view.top + (bottom - layout['offsetTop']) * factor)
                    for left, top, right, bottom in layout['rects']]
        raise StepFailed('The test page in Chrome does not answer. Is it open, and is it the page '
                         'served on port %d?' % self.port)

    def copy_image(self):
        """Puts an image on the clipboard as a person would: opens the server's test image in
        Chrome, long-presses it and chooses Copy image. Then shows the test page again."""
        device = self.device
        url = 'http://localhost:%d/%s' % (self.port, plan.IMAGE_NAME)
        plan.run_adb(self.adb, self.serial, ['shell', 'am', 'start', '-a', 'android.intent.action.VIEW', '-d', url,
                                             '-p', CHROME])
        web_view = device.wait(lambda nodes: first([node for node in nodes if node.cls == 'WebView'
                                                    and node.package == CHROME]), 10)
        if web_view is None:
            raise StepFailed('Chrome did not show the test image')
        time.sleep(1.5)
        image = first([node for node in device.nodes() if node.package == CHROME and node.cls == 'Image'])
        device.long_press(image if image is not None else web_view)
        copy = device.wait(lambda nodes: first(by_text(nodes, 'Copy image')), 5)
        if copy is None:
            device.press('back')
            raise StepFailed('Long-pressing the image in Chrome offered no Copy image')
        device.tap(copy)
        time.sleep(1)
        plan.open_page_on_device(self.adb, self.serial, self.port)
        time.sleep(2)


# ---------------------------------------------------------------------------------------------
# Dokuen


class Entry:
    """One entry of a lookup result, as far as the dictionary window shows it."""

    def __init__(self, headword, reading, header, links):
        self.headword = headword
        self.reading = reading
        self.header = header  # the headword's nodes, left to right
        self.links = links  # the plugin's link nodes, in order

    def link(self, card_format):
        for node in self.links:
            match = LINK_PATTERN.match(node.text)
            if match and match.group(3) == card_format:
                return node
        return None

    def __repr__(self):
        return '%s【%s】 %s' % (self.headword, self.reading, [node.text for node in self.links])


class Popup:
    """The dictionary window as one UI dump shows it."""

    def __init__(self, nodes, window, density, screen_height):
        self.window = window
        self.density = density
        self.nodes = [node for node in nodes if node.package == HOST and window.contains(node)]
        self.texts = sorted([node for node in self.nodes if node.text], key=lambda node: (node.top, node.left))
        # The result scrolls inside the window; when it fits, there is no scrollable node.
        scrollers = [node for node in self.nodes if node.scrollable]
        self.scroller = max(scrollers, key=lambda node: node.height) if scrollers else None
        bar = first(by_desc(nodes, 'Read Aloud'))
        dismiss = first(by_desc(nodes, 'Dismiss status'))
        # The bottom bar holds either Dokuen's buttons or, after an action, a status message with
        # a button to dismiss it.
        status_nodes = [node for node in self.texts if dismiss is not None and not LINK_PATTERN.match(node.text)
                        and abs(node.center[1] - dismiss.center[1]) < 40 * density]
        self.status = ' '.join(node.text for node in status_nodes) if status_nodes else None
        if self.scroller is not None:
            self.content_bottom = self.scroller.bottom
        elif status_nodes:
            self.content_bottom = min(node.top for node in status_nodes) - 5
        elif bar is not None:
            self.content_bottom = bar.top - 5
        else:
            self.content_bottom = window.bottom
        # The window can reach below the screen, under Android's gesture bar.
        self.visible_bottom = min(self.content_bottom, screen_height - SCREEN_BOTTOM_MARGIN)
        self.links = [node for node in self.texts if LINK_PATTERN.match(node.text) and node.bottom <= self.visible_bottom]

    def text(self):
        return '\n'.join(node.text for node in self.texts)

    def loading(self):
        """Whether Dokuen still shows its placeholder while the plugin builds the result."""
        return any(node.text.startswith('Loading definition') for node in self.texts)

    def headers(self):
        """Each headword row the window shows: (headword nodes, ruby nodes)."""
        content = [node for node in self.texts if node.bottom <= self.visible_bottom]
        # A term's headword is one line in the headword's font, in Japanese script only (a line of
        # ordinary text that wraps can be as tall, but has spaces, and a redirect to another word
        # starts with an arrow); a kanji's is the one, much larger, glyph.
        large = [node for node in content if HEADWORD_TEXT.match(node.text) and (
            HEADWORD_HEIGHT[0] <= node.height / self.density <= HEADWORD_HEIGHT[1]
            or (len(node.text) == 1 and node.height / self.density > HEADWORD_HEIGHT[1]))]
        if not large:
            return []
        # Headwords start at the content's left edge; example sentences and the like are indented.
        edge = self.scroller.left if self.scroller else min(node.left for node in large)
        rows = []
        for node in large:
            if LINK_PATTERN.match(node.text):
                continue
            row = sorted([other for other in large if abs(other.bottom - node.bottom) <= 6],
                         key=lambda other: other.left)
            if row[0] is not node or (rows and rows[-1][0] == row):
                continue
            ruby = [other for other in content if abs(other.bottom - node.top) <= 6 * self.density
                    and other.height / self.density < HEADWORD_HEIGHT[0]
                    and other.left < row[-1].right and other.right > row[0].left]
            # A headword starts at the content's left edge, or its reading does when the headword
            # is centered under a longer reading; example sentences are indented.
            if min([row[0].left] + [other.left for other in ruby]) > edge + 3 * self.density:
                continue
            rows.append((row, sorted(ruby, key=lambda other: other.left)))
        return rows

    def entries(self):
        """The entries whose headword is shown, with the links shown below each before the next."""
        headers = self.headers()
        entries = []
        for index, (row, ruby) in enumerate(headers):
            following = headers[index + 1][0][0].top if index + 1 < len(headers) else 1 << 30
            headword = ''.join(node.text for node in row)
            reading, used = '', set()
            for node in row:
                above = [r for r in ruby if r.left < node.right and r.right > node.left]
                if above:
                    reading += ''.join(r.text for r in above if id(r) not in used)
                    used.update(id(r) for r in above)
                else:
                    reading += node.text
            links = [node for node in self.links if row[0].bottom < node.top < following]
            entries.append(Entry(headword, reading, row, links))
        return entries


class Dokuen:
    """Dokuen's session, overlay and dictionary window."""

    def __init__(self, run):
        self.run = run
        self.device = run.device
        self.button = (155, 305)  # the floating button's center, updated whenever a dump shows it

    def floating_button(self, nodes):
        button = first([node for node in nodes if node.rid == 'floating_button'])
        if button is not None:
            self.button = button.center
        return button

    def overlay_nodes(self, nodes):
        return [node for node in nodes if node.package == HOST and node.rid in ('overlay_container', 'dictionary_popup')]

    def overlay_shown(self, nodes=None):
        nodes = self.device.nodes() if nodes is None else nodes
        self.floating_button(nodes)
        return bool(self.overlay_nodes(nodes)) or (
            any(node.package == HOST for node in nodes)
            and not any(node.rid == 'floating_button' for node in nodes)
            and self.device.foreground() != HOST)

    def session_running(self):
        nodes = self.device.nodes()
        return self.floating_button(nodes) is not None or bool(self.overlay_nodes(nodes))

    def start_session(self):
        """Starts Dokuen's session, sharing Chrome's screen, and returns to the test page.

        It acts on whichever screen of the flow is shown (Dokuen's main page, its screen sharing
        instructions, Android's share prompt and app picker) until the session runs, so it also
        finishes a start that an earlier, interrupted attempt left halfway."""
        device = self.device
        deadline = time.time() + 90
        while time.time() < deadline:
            nodes = device.wait(lambda nodes: nodes, 1)
            own = [node for node in nodes if node.package == HOST]
            mode = first([node for node in nodes if node.rid == 'screen_share_mode_options'])
            if self.floating_button(nodes) is not None or by_text(own, 'Stop'):
                break
            if by_text(nodes, 'Choose app to share'):
                # The picker slides in; a tap before it settles misses.
                time.sleep(1.5)
                nodes = device.nodes()
                chrome = first(by_desc(nodes, 'Chrome')) or first(by_text(nodes, 'Chrome'))
                if chrome is None:
                    raise StepFailed('Android\'s app picker does not offer Chrome')
                device.tap(chrome)
            elif mode is not None:
                if not by_text(nodes, 'Share one app'):
                    device.tap(mode)
                    device.tap(device.wait(lambda nodes: first(by_text(nodes, 'Share one app')), 5) or mode)
                else:
                    device.tap(first(by_text(nodes, 'Next')) or first(by_text(nodes, 'Start now')) or mode)
            elif by_text(own, 'I Understand'):
                device.tap(first(by_text(own, 'I Understand')))
            elif by_text(own, 'Start'):
                device.tap(first(by_text(own, 'Start')))
            else:
                self.to_main_page()
            time.sleep(1.5)
        else:
            raise StepFailed('Dokuen\'s session did not start')
        self.run.page.bring_to_front()
        if device.wait(lambda nodes: self.floating_button(nodes), 10) is None:
            raise StepFailed('Dokuen\'s floating button did not appear after starting the session')

    def to_main_page(self):
        """Brings Dokuen to the front. Dokuen reopens on the screen it was left on (its Settings, for
        example), so when one of its screens other than the main page is in front, this goes back
        one screen toward the main page. Android's resumed activity decides: app_current can name
        another app's activity while Dokuen's screen is shown, and Dokuen's floating button is on
        screen over every app. The main page has the Settings icon; while it shows, this does
        nothing, because Back would leave Dokuen."""
        if self.device.resumed_package() != HOST:
            self.device.start_app(HOST)
        elif not any(node.package == HOST and node.desc == 'Settings' for node in self.device.nodes()):
            self.device.press('back')

    def stop_session(self):
        """Stops Dokuen's session from its main page, dismissing anything left over it."""
        device = self.device
        for _ in range(8):
            nodes = device.wait(lambda nodes: nodes, 1)
            own = [node for node in nodes if node.package == HOST]
            if by_text(nodes, 'Choose app to share'):
                device.press('back')
            elif any(node.rid == 'screen_share_mode_options' for node in nodes):
                # Android's share prompt, from a start that did not finish.
                device.tap(first(by_text(nodes, 'Cancel')))
            elif by_text(own, 'I Understand'):
                # Dokuen's screen sharing instructions, which Back does not close; the share prompt
                # that follows is cancelled.
                device.tap(first(by_text(own, 'I Understand')))
            elif by_text(own, 'Stop'):
                device.tap(first(by_text(own, 'Stop')))
            elif by_text(own, 'Start'):
                return
            else:
                self.to_main_page()
            time.sleep(1.5)
        raise StepFailed('Dokuen\'s session did not stop')

    def restart_session(self):
        self.stop_session()
        self.start_session()

    def ensure_session(self):
        if not self.session_running():
            self.start_session()

    def show_overlay(self):
        nodes = self.device.nodes()
        if self.overlay_shown(nodes):
            return
        if self.floating_button(nodes) is None:
            self.start_session()
        self.device.tap(self.button)
        if not self.device.wait(lambda nodes: self.overlay_nodes(nodes), 15):
            # The screen sharing can end on its own (when Chrome opens another page, for one),
            # which ends the session: start a new one and try again.
            self.run.note('Dokuen\'s overlay did not appear; starting a new session')
            self.restart_session()
            self.device.tap(self.button)
            if not self.device.wait(lambda nodes: self.overlay_nodes(nodes), 15):
                raise StepFailed('Dokuen\'s overlay did not appear after tapping its floating button')
        # The overlay appears before the text is recognized; give the OCR time to finish.
        time.sleep(1.5)

    def hide_overlay(self):
        nodes = self.device.nodes()
        if not self.overlay_shown(nodes):
            return
        window = first([node for node in nodes if node.rid == 'dictionary_popup'])
        if window is not None:
            # The dictionary window can cover the floating button: close it first.
            close = first([node for node in nodes if node.desc == 'Close' and window.contains(node)])
            if close is not None:
                self.device.tap(close)
                self.device.wait(lambda nodes: not any(node.rid == 'dictionary_popup' for node in nodes), 5)
        self.device.tap(self.button)
        if self.device.wait(lambda nodes: not self.overlay_nodes(nodes) and self.floating_button(nodes), 10) is None:
            raise StepFailed('Dokuen\'s overlay did not hide after tapping its floating button')

    def scan(self, word, line=None, repair=False):
        """Scans word on the test page, from its own line or from line: shows Dokuen's overlay over
        it and drags across exactly its characters. Returns the dictionary window.

        When the plugin's log shows what it looked up, and that is not word, the OCR misread it.
        With repair, the misread characters are then corrected with the dictionary window's
        Repair, as the plan has the tester do for the words the OCR is known to misread. Otherwise,
        as the plan has the tester zoom in, the word's own line is enlarged and scanned again."""
        if repair:
            popup = self.scan_once(word, line, 1)
            looked_up = self.looked_up()
            if looked_up is None or looked_up == word:
                return popup
            self.run.misreads.append((word, looked_up, 1))
            if len(looked_up) != len(word):
                raise StepFailed('Dokuen\'s OCR reads %s as %s, which Repair cannot correct character by '
                                 'character; correct it by hand, and do the step yourself' % (word, looked_up))
            for wrong, right in zip(looked_up, word):
                if wrong != right:
                    self.repair(wrong, right)
            self.run.note('the OCR read %s as %s; corrected it with Repair' % (word, looked_up))
            looked_up = self.looked_up()
            if looked_up != word:
                raise StepFailed('After Repair, the plugin looked up %s, not %s' % (looked_up, word))
            return self.wait_settled(self.wait_popup(20))
        # Enlarged, the line must still fit on one line of the screen.
        scales = [1] if line else [1] + [scale for scale in (1.6, 2.2)
                                         if (len(word) + 1) * 32 * scale * self.device.density < self.device.width * 0.85]
        looked_up = None
        for scale in scales:
            popup = self.scan_once(word, line, scale)
            looked_up = self.looked_up()
            if looked_up is None or looked_up == word:
                return popup
            self.run.misreads.append((word, looked_up, scale))
            self.run.note('the OCR read %s as %s; scanned again with the line enlarged' % (word, looked_up))
        raise StepFailed('Dokuen\'s OCR reads %s as %s, even with the line enlarged; zoom in or repair the OCR by '
                         'hand, and do the step yourself' % (word, looked_up))

    def repair(self, wrong, right):
        """Corrects a character the OCR misread with the dictionary window's Repair: replaces the
        box showing wrong with right, and confirms, which looks the corrected text up. The box
        takes Japanese input, so right is pasted rather than typed."""
        device = self.device
        button = first(by_desc(device.nodes(), 'Repair'))
        if button is None:
            raise StepFailed('The dictionary window has no Repair button')
        device.tap(button)
        title = device.wait(lambda nodes: first(by_text(nodes, 'Correct mis-identified characters')), 10)
        if title is None:
            raise StepFailed('Repair did not open')
        time.sleep(1)

        def repair_box(nodes):
            # The character boxes are right below Repair's title.
            title = first(by_text(nodes, 'Correct mis-identified characters'))
            return first([node for node in nodes if node.package == HOST and node.text == wrong and title is not None
                          and title.bottom <= node.top < title.bottom + 300 * device.density])

        def editing(nodes):
            return first([node for node in nodes if node.package == HOST and node.cls == 'EditText'])
        for attempt in range(2):
            box = device.wait(repair_box, 5)
            if box is None:
                raise StepFailed('Repair shows no box for %s' % wrong)
            device.tap(box)
            if device.wait(editing, 5):
                break
        else:
            raise StepFailed('Tapping the box for %s in Repair did not let it be edited' % wrong)
        device.u2.set_clipboard(right)
        time.sleep(0.5)
        # Entering a box selects its text, so the paste replaces it.
        device.shell('input', 'keycombination', 'KEYCODE_CTRL_LEFT', 'KEYCODE_V')
        time.sleep(1)
        edited = first([node for node in device.nodes() if node.package == HOST and node.cls == 'EditText'])
        if edited is None or edited.text != right:
            raise StepFailed('Repair\'s box shows %r after pasting %s' % (edited.text if edited else None, right))
        device.hide_keyboard()
        confirm = first([node for node in device.nodes() if node.package == HOST and node.text == 'Confirm'])
        if confirm is None:
            raise StepFailed('Repair has no Confirm button')
        device.tap(confirm)
        time.sleep(2)

    def looked_up(self):
        """The text the plugin last looked up, from its log (debug builds only), or None."""
        log = self.run.adb('logcat', '-d', '-t', '400', '-s', 'YomitanService:D')
        matches = re.findall(r"onLookup: contextText='(.*?)', cursorStartIndex=(\d+), cursorEndIndex=(\d+)", log)
        if not matches:
            return None
        context, start, end = matches[-1]
        return context[int(start):int(end)]

    def scan_once(self, word, line, scale):
        self.hide_overlay()
        self.ensure_session()
        rects = self.run.page.locate(line or word + '。', word, scale)
        first_char, last_char = rects[0], rects[-1]
        y = (first_char[1] + first_char[3]) / 2
        if len(rects) == 1:
            start = (first_char[0] + (first_char[2] - first_char[0]) * 0.2, y)
            end = (first_char[0] + (first_char[2] - first_char[0]) * 0.8, y)
        else:
            start = ((first_char[0] + first_char[2]) / 2, y)
            end = ((last_char[0] + last_char[2]) / 2, y)
        self.show_overlay()
        for attempt in range(3):
            self.device.swipe(start, end, duration=0.5)
            if self.device.wait(lambda nodes: self.popup(nodes), 8) is not None:
                # The plugin's first lookup after its process starts opens its databases.
                popup = self.wait_popup(30)
                if popup is None:
                    raise StepFailed('The dictionary window shows no result for %s' % word)
                return self.wait_settled(popup)
            time.sleep(1.5)
        raise StepFailed('Dragging across %s on Dokuen\'s overlay opened no dictionary window' % word)

    def wait_settled(self, popup, timeout=15.0):
        """Waits until the dictionary window stops changing. Dokuen can show a quick result from
        another source first and replace it with the plugin's a moment later."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            time.sleep(1.2)
            again = self.wait_popup(10)
            if again is None:
                return popup
            if again.text() == popup.text() and again.window.bounds == popup.window.bounds:
                return again
            popup = again
        return popup

    def popup(self, nodes=None):
        nodes = self.device.nodes() if nodes is None else nodes
        window = first([node for node in nodes if node.rid == 'dictionary_popup'])
        if window is None:
            return None
        return Popup(nodes, window, self.device.density, self.device.height)

    def wait_popup(self, timeout=10.0, ready=None):
        """Waits for the dictionary window to show a result (or, with ready, what ready accepts)."""
        def find(nodes):
            popup = self.popup(nodes)
            if popup is None or popup.loading():
                return None
            if ready is not None:
                return popup if ready(popup) else None
            return popup if popup.texts else None
        return self.device.wait(find, timeout)

    def scroll_popup(self, popup, down=True, fling=False):
        """Scrolls the dictionary window's content by about half its height, or, flinging, by
        much more."""
        if popup.scroller is None:
            return popup
        # Inside the scrolling content: a drag elsewhere in the window moves or resizes it.
        area = popup.scroller
        x = area.left + 20
        top, bottom = area.top + 40, popup.visible_bottom - 40
        middle = (top + bottom) // 2
        span = (bottom - top) // 2
        start, end = ((x, middle + span // 2), (x, middle - span // 2)) if down else \
            ((x, middle - span // 2), (x, middle + span // 2))
        self.device.swipe(start, end, duration=0.1 if fling else 0.6)
        time.sleep(1.5 if fling else 0.6)
        return self.popup()

    def scroll_popup_to_top(self):
        popup = self.popup()
        for _ in range(30):
            if popup is None:
                raise StepFailed('The dictionary window is not shown')
            before = popup.text()
            popup = self.scroll_popup(popup, down=False, fling=True)
            if popup is not None and popup.text() == before:
                break
        if popup is None:
            raise StepFailed('The dictionary window closed')
        return popup

    def find_entry(self, headword, reading=None, card_format=None):
        """Scrolls the dictionary window to the entry headword【reading】 (any reading if None)
        and returns it, with the link of card_format shown if one is given."""
        popup = self.scroll_popup_to_top()
        seen_header = False
        for _ in range(25):
            if popup is None:
                raise StepFailed('The dictionary window closed')
            for entry in popup.entries():
                if entry.headword == headword and (reading is None or entry.reading == reading):
                    if card_format is None or entry.link(card_format) is not None:
                        return entry
                    seen_header = True
            if seen_header and card_format is not None:
                # The entry is taller than the window: its links are the first ones below its
                # headword, now scrolled into view.
                links = [node for node in popup.links if node.top > popup.window.top]
                headers = popup.headers()
                next_header = headers[0][0][0].top if headers else 1 << 30
                own = [node for node in links if node.top < next_header]
                if own:
                    entry = Entry(headword, reading, [], own)
                    if entry.link(card_format) is not None:
                        return entry
            before = popup.text()
            popup = self.scroll_popup(popup)
            if popup is not None and popup.text() == before:
                break
        raise StepFailed('The result has no entry %s【%s】%s' % (
            headword, reading or '*', ' with a link for %s' % card_format if card_format else ''))

    def all_entries(self):
        """Every entry of the result, top to bottom: (headword, reading)."""
        popup = self.scroll_popup_to_top()
        found = []
        for _ in range(40):
            shown = [(entry.headword, entry.reading) for entry in popup.entries()]
            # Consecutive views overlap: skip the entries the last view already showed.
            overlap = max([size for size in range(min(len(found), len(shown)) + 1)
                           if found[len(found) - size:] == shown[:size]] or [0])
            found += shown[overlap:]
            before = popup.text()
            popup = self.scroll_popup(popup)
            if popup is None or popup.text() == before:
                break
        return found

    def edge_link(self, card_format, last):
        """The link of card_format on the result's first or last entry: the first such link from
        the top, or the last one at the bottom."""
        popup = self.scroll_popup_to_top()
        for _ in range(80):
            links = [node for node in popup.links if LINK_PATTERN.match(node.text).group(3) == card_format]
            if links and not last:
                return links[0]
            before = popup.text()
            popup = self.scroll_popup(popup, fling=last)
            if popup is None:
                raise StepFailed('The dictionary window closed')
            if popup.text() == before:
                break
        links = [node for node in popup.links if LINK_PATTERN.match(node.text).group(3) == card_format]
        if not links:
            raise StepFailed('The %s entry shows no %s link' % ('last' if last else 'first', card_format))
        return links[-1] if last else links[0]

    def result_size(self):
        """The number of entries the plugin's last lookup found, from its log (debug builds only),
        or None."""
        log = self.run.adb('logcat', '-d', '-t', '400', '-s', 'YomitanService:D')
        counts = re.findall(r'onLookup completed\. Found (\d+) entr', log)
        return int(counts[-1]) if counts else None

    def tap_link(self, entry, card_format, timeout=30.0):
        """Taps entry's link for card_format and waits up to timeout seconds for the result: the
        refreshed window or a message. Returns (popup, link text afterwards or None, status message
        or None)."""
        link = entry.link(card_format)
        before = link.text
        self.device.tap(link)

        def changed(popup):
            # Dokuen shows "Loading definition..." while the plugin handles the tap.
            if popup.status is not None and popup.status.startswith('Loading'):
                return False
            return popup.status is not None or any(
                LINK_PATTERN.match(node.text) and LINK_PATTERN.match(node.text).group(3) == card_format
                and node.text != before for node in popup.links)
        # The first write after the plugin's process starts also opens AnkiDroid's provider.
        popup = self.wait_popup(timeout, changed)
        # The refreshed link can show a moment before the message does.
        time.sleep(1.5)
        popup = self.wait_popup(5) or popup or self.popup()
        after = None
        if popup is not None:
            labels = [node.text for node in popup.links if LINK_PATTERN.match(node.text).group(3) == card_format]
            after = labels[0] if labels else None
        return popup, after, popup.status if popup is not None else None

    def crop_ui(self, nodes=None):
        """Dokuen's crop UI, if shown: its nodes."""
        nodes = self.device.nodes() if nodes is None else nodes
        own = [node for node in nodes if node.package == HOST]
        if by_text(own, 'Confirm') and by_text(own, 'Cancel') and (by_text(own, 'Use a file instead')
                                                                     or by_text(own, 'Skip')):
            return own
        return None

    def crop_and_confirm(self):
        """Drags the crop's top-left corner a third of the way in, then confirms."""
        nodes = self.crop_ui()
        if nodes is None:
            raise StepFailed('Dokuen\'s crop UI is not shown')
        texts = sorted([node for node in nodes if node.text], key=lambda node: node.top)
        title = texts[0]
        below = first([node for node in texts if node.text in ('Use a file instead', 'Skip', 'Cancel')])
        # The image fills the space between the title and the buttons, keeping the screen's aspect
        # ratio, centered; the crop starts as the whole image.
        top = title.bottom + 42
        height = below.top - 30 - top
        width = height * self.device.width / self.device.height
        left = (self.device.width - width) / 2
        self.device.swipe((left + 4, top + 4), (left + width / 3, top + height / 3), 0.8)
        time.sleep(0.5)
        self.device.tap(first(by_text(self.crop_ui(), 'Confirm')))
        time.sleep(2)

    def crop_choose_newest_photo(self):
        """In the crop UI, chooses a file instead: the newest photo in Android's photo picker. Then
        confirms the crop UI, which now shows that photo, without cropping it."""
        device = self.device
        device.tap(first(by_text(self.crop_ui() or [], 'Use a file instead')) or (0, 0))
        grid = device.wait(lambda nodes: first(by_desc(nodes, 'Media grid')), 15)
        if grid is None:
            raise StepFailed('"Use a file instead" opened no photo picker')

        def photos(nodes):
            grid_now = first(by_desc(nodes, 'Media grid'))
            return [node for node in nodes if grid_now is not None and node.clickable and grid_now.contains(node)
                    and node is not grid_now] or None
        shown = device.wait(photos, 10)
        if shown:
            # Newest first: the first is the top left one.
            device.tap(min(shown, key=lambda node: (node.top, node.left)))
        else:
            # The picker can show its photos without marking them as tappable: tap where the top
            # left one is, in a grid of three columns.
            grid = first(by_desc(device.nodes(), 'Media grid')) or grid
            side = grid.width / 3
            device.tap((grid.left + side / 2, grid.top + side / 2))
        # Some versions of the picker return the photo when it is tapped; others check it and wait
        # for Done.
        done = device.wait(lambda nodes: self.crop_ui(nodes) or first(
            [node for node in nodes if node.text == 'Done' and node.package != HOST]), 10)
        if isinstance(done, Node):
            device.tap(done)
        if device.wait(lambda nodes: self.crop_ui(nodes), 15) is None:
            raise StepFailed('Choosing a photo did not return to the crop UI')
        time.sleep(1)
        device.tap(first(by_text(self.crop_ui() or [], 'Confirm')) or (0, 0))
        time.sleep(2)

    def crop_continue_without_image(self):
        self.device.tap(first(by_text(self.crop_ui() or [], 'Skip')) or (0, 0))
        time.sleep(2)

    def crop_cancel(self):
        self.device.tap(first(by_text(self.crop_ui() or [], 'Cancel')) or (0, 0))
        time.sleep(2)

    def tap_and_wait_for_new_result(self, point, what):
        """Taps point, which shows another result in the window, and waits for it; taps once more
        if the tap was lost."""
        before = self.popup()
        before_text = before.text() if before is not None else None
        for attempt in range(2):
            self.device.tap(point)
            popup = self.wait_popup(10, lambda popup: popup.text() != before_text)
            if popup is not None:
                return popup
        raise StepFailed('Tapping %s did not change the dictionary window' % what)

    def back_to(self, headword, reading):
        """Taps the dictionary window's back button until it shows the entry headword【reading】
        again, at most three times (a lost or doubled tap leaves another result in between)."""
        for _ in range(3):
            back = first(by_desc(self.device.nodes(), 'Back'))
            if back is None:
                raise StepFailed('The dictionary window has no back button')
            self.device.tap(back)
            time.sleep(1.5)
            popup = self.wait_popup(10)
            if popup is not None and any(entry.headword == headword and entry.reading == reading
                                         for entry in popup.entries()):
                return popup
        raise StepFailed('The back button did not return to %s【%s】' % (headword, reading))

    def tap_headword_character(self, entry, char):
        for node in entry.header:
            index = node.text.find(char)
            if index >= 0:
                x = node.left + node.width * (index + 0.5) / len(node.text)
                return self.tap_and_wait_for_new_result((x, node.center[1]), '%s in the headword' % char)
        raise StepFailed('%s is not in the headword %s' % (char, entry.headword))


# ---------------------------------------------------------------------------------------------
# The plugin's dashboard


class Dashboard:
    """The plugin's dashboard. Every lookup of a control scrolls the tab until it is shown."""

    def __init__(self, run):
        self.run = run
        self.device = run.device
        # Every text of the open tab, until something changes it.
        self.cached_texts = None

    def mine(self, nodes):
        return [node for node in nodes if node.package == plan.PACKAGE]

    def open(self, tab='Anki Export'):
        """Shows the dashboard at tab. Dokuen's overlay is hidden first: the plugin's settings can
        only change while it is (see "Changing a plugin setting" in the plan)."""
        dokuen = self.run.dokuen
        if dokuen.overlay_shown():
            dokuen.hide_overlay()
        if self.device.foreground() != plan.PACKAGE:
            self.device.start_app(plan.PACKAGE)
        self.hide_keyboard()

        def find_tab(nodes):
            # On screen in full: while the dashboard slides in, its tabs are still off to the side.
            return first([node for node in self.mine(nodes) if node.text == tab and node.top < 1000
                          and node.left >= 0 and node.right <= self.device.width])
        tab_node = self.device.wait(find_tab, 8)
        if tab_node is None:
            # A menu or dialog left open by an earlier step covers the dashboard.
            self.device.press('back')
            tab_node = self.device.wait(find_tab, 8)
        if tab_node is None:
            raise StepFailed('The plugin\'s dashboard shows no %s tab' % tab)
        # A text only that tab shows, near its top, tells which tab is open.
        marker = TAB_MARKERS[tab]
        for _ in range(3):
            tab_node = self.device.wait(find_tab, 5) or tab_node
            nodes = self.device.nodes()
            # The dump marks the open tab's half of the tab row as selected, and the other tab's as
            # clickable.
            if any(node.selected and node.contains(tab_node) for node in self.mine(nodes)):
                # The tab is open, though perhaps scrolled away from its marker. It is not tapped,
                # since that would do nothing, and the open tab has no clickable area beside its
                # label to tap when the floating button covers the label.
                shown = first(by_text(self.mine(nodes), marker))
            else:
                self.device.tap(self.clear_of_button(tab_node))
                time.sleep(1.2)
                shown = self.device.wait(lambda nodes: first(by_text(self.mine(nodes), marker)), 3)
            if shown:
                break
            self.to_top()
            if first(by_text(self.mine(self.device.nodes()), marker)):
                break
        else:
            raise StepFailed('The dashboard did not switch to its %s tab' % tab)
        if tab != getattr(self, 'tab', None):
            self.cached_texts = None
        self.tab = tab

    def clear_of_button(self, node):
        """A point to tap for node that Dokuen's floating button does not cover, since the button
        floats over the dashboard and takes any tap that lands on it. That is node's center when the
        button is elsewhere. Otherwise it is a point of the smallest clickable node around node, nearest
        the center (a tab that is not open takes taps across its half of the tab row, which is much
        wider than the button)."""
        nodes = self.device.nodes()
        button = self.run.dokuen.floating_button(nodes)
        if button is None:
            return node.center
        # A margin around the button, as taps near its edge may still reach it.
        margin = int(8 * self.device.density)

        def covered(x, y):
            return (button.left - margin <= x <= button.right + margin
                    and button.top - margin <= y <= button.bottom + margin)
        x, y = node.center
        if not covered(x, y):
            return node.center
        areas = [area for area in self.mine(nodes) if area.clickable and area.contains(node)]
        area = min(areas, key=lambda area: area.width * area.height) if areas else node
        step = max(1, int(8 * self.device.density))
        for offset in range(step, area.width, step):
            for candidate in (x - offset, x + offset):
                if area.left < candidate < area.right and not covered(candidate, y):
                    return (candidate, y)
        raise StepFailed('The floating button covers every tappable point of the %r tab' % (node.text or node.desc))

    def changed(self):
        self.cached_texts = None

    def all_texts(self):
        """texts(), kept until a setting changes."""
        if self.cached_texts is None:
            self.cached_texts = self.texts()
        return self.cached_texts

    def list_view(self, nodes):
        lists = [node for node in self.mine(nodes) if node.scrollable and node.height > 800]
        return min(lists, key=lambda node: node.width * node.height) if lists else None

    def visible_area(self, nodes):
        """The part of the screen the tab's list shows, between the tab strip and the dashboard's
        footer, which covers the bottom of the list."""
        view = self.list_view(nodes)
        top = view.top if view else 0
        bottom = min(view.bottom if view else self.device.height, self.device.height - 140)
        footer = first([node for node in self.mine(nodes) if node.text == FOOTER_TEXT])
        if footer is not None:
            # The footer's bar starts well above its button's text (on a Pixel 6, 83 px above it).
            # It is the screen-wide node around the text that starts below the list's top; the
            # list's own nodes are inset from the screen's sides, though they may reach under it.
            bar = [node.top for node in self.mine(nodes) if node.contains(footer) and node.top > top
                   and node.left <= 0 and node.right >= self.device.width]
            bottom = min(bottom, min(bar) - 10 if bar else footer.top - 40)
        return top, bottom

    def scroll(self, down=True, fraction=0.45):
        nodes = self.device.nodes()
        view = self.list_view(nodes)
        if view is None:
            raise StepFailed('The dashboard shows no scrollable list')
        top, bottom = self.visible_area(nodes)
        # Dragging at the list's left edge, beside every control, so the drag cannot move a slider.
        x = view.left + 20
        span = int((bottom - top) * fraction)
        middle = (top + bottom) // 2
        if down:
            self.device.swipe((x, middle + span // 2), (x, middle - span // 2), 0.5)
        else:
            self.device.swipe((x, middle - span // 2), (x, middle + span // 2), 0.5)
        time.sleep(0.5)

    def to_top(self):
        last = None
        for _ in range(60):
            texts = [node.text for node in self.mine(self.device.nodes()) if node.text]
            if texts == last:
                return
            last = texts
            self.scroll(down=False, fraction=0.8)

    def to_bottom(self):
        last = None
        for _ in range(60):
            texts = [node.text for node in self.mine(self.device.nodes()) if node.text]
            if texts == last:
                return
            last = texts
            self.scroll(down=True, fraction=0.8)

    def texts(self):
        """Every text the current tab shows, top to bottom, scrolling through all of it."""
        self.to_top()
        seen, last = [], None
        for _ in range(40):
            nodes = self.mine(self.device.nodes())
            texts = [node.text for node in sorted(nodes, key=lambda node: (node.top, node.left)) if node.text]
            for text in texts:
                if text not in seen:
                    seen.append(text)
            descs = [node.desc for node in nodes if node.desc]
            for desc in descs:
                if desc not in seen:
                    seen.append(desc)
            if texts == last:
                break
            last = texts
            self.scroll(fraction=0.6)
        return seen

    def find(self, match, what):
        """Scrolls the tab until match(nodes) returns a node shown in full, and returns it."""
        for attempt in range(2):
            last = None
            # Until the end of the tab; with every field's overwrite menu shown, the editor is long.
            for _ in range(120):
                nodes = self.device.nodes()
                found = match(nodes)
                top, bottom = self.visible_area(nodes)
                if found is not None:
                    if found.top < top + 10:
                        self.scroll(down=False, fraction=0.25)
                        continue
                    if found.bottom > bottom - 20:
                        self.scroll(down=True, fraction=0.25)
                        continue
                    return found
                texts = [node.text for node in self.mine(nodes) if node.text]
                if texts == last:
                    break
                last = texts
                self.scroll()
            self.to_top()
        raise StepFailed('The dashboard shows no %s' % what)

    def has(self, match):
        """Whether match(nodes) finds a node anywhere in the tab."""
        try:
            self.find(match, '')
            return True
        except StepFailed:
            return False

    @staticmethod
    def label_of(field, nodes):
        labels = [node for node in nodes if node.cls == 'TextView' and node.text and field.contains(node)]
        return labels[0].text if labels else None

    def field_match(self, label, index=0):
        def match(nodes):
            fields = [node for node in self.mine(nodes) if node.cls == 'EditText' and self.label_of(node, nodes) == label]
            return fields[index] if len(fields) > index else None
        return match

    def field(self, label, index=0):
        return self.find(self.field_match(label, index), 'field %s' % label)

    def field_value(self, label):
        node = self.field(label)
        return '' if node.text == label else node.text

    def switch_match(self, label, reach=80):
        """A match for the switch right of the text label, its center at most reach pixels above
        or below the text's."""
        def match(nodes):
            text = first([node for node in self.mine(nodes) if node.text == label and node.cls == 'TextView'])
            if text is None:
                return None
            return first([node for node in self.mine(nodes) if node.checkable and node.left > text.left
                          and abs(node.center[1] - text.center[1]) < reach])
        return match

    def switch(self, label, reach=80):
        return self.find(self.switch_match(label, reach), 'switch %s' % label)

    def set_switch(self, label, on, reach=80):
        self.changed()
        node = self.switch(label, reach)
        if node.checked != on:
            self.device.tap(node)
            changed = self.device.wait(lambda nodes: (lambda found: found is not None and found.checked == on)(
                self.switch_match(label, reach)(nodes)), 5)
            if not changed:
                raise StepFailed('The %s switch did not turn %s' % (label, 'on' if on else 'off'))

    def menu_after_tap(self, node):
        """Taps node, which opens a menu, and returns the menu's items, top to bottom."""
        before = {(other.text, other.bounds) for other in self.device.nodes()}
        self.device.tap(node)
        time.sleep(0.8)

        def items(nodes):
            new = [other for other in self.mine(nodes) if other.text and other.cls == 'TextView'
                   and (other.text, other.bounds) not in before]
            return sorted(new, key=lambda other: other.top) or None
        return self.device.wait(items, 5) or []

    def menu_options(self, label, index=0):
        """The options of the dropdown labeled label, in order; leaves the menu closed."""
        items = self.menu_after_tap(self.field(label, index))
        self.device.press('back')
        time.sleep(0.5)
        return [item.text for item in items]

    def choose(self, label, option, index=0):
        """Picks option in the dropdown labeled label."""
        self.changed()
        items = self.menu_after_tap(self.field(label, index))
        item = first([node for node in items if node.text == option])
        if item is None:
            self.device.press('back')
            raise StepFailed('The %s menu has no %s; it offers %s' % (label, option, [node.text for node in items]))
        self.device.tap(item)
        time.sleep(0.8)
        value = self.field(label, index).text
        if value != option:
            raise StepFailed('%s shows %r after choosing %r' % (label, value, option))

    def set_slider_value(self, label_prefix, value, maximum=100):
        """Sets the slider below the text starting with label_prefix (which shows its value after
        a colon) by tapping its track, then nudging until the text shows value."""
        self.changed()

        def text_node(nodes):
            return first([node for node in self.mine(nodes) if node.text.startswith(label_prefix)])

        def slider(nodes):
            label = text_node(nodes)
            if label is None:
                return None
            below = [node for node in self.mine(nodes) if node.cls == 'SeekBar' and node.top >= label.bottom - 5]
            return min(below, key=lambda node: node.top) if below else None
        track = self.find(slider, 'the %s slider' % label_prefix)
        # A Material slider's track is inset by half its thumb on each side.
        inset = 10 * self.device.density
        span = track.width - 2 * inset
        x = track.left + inset + span * value / maximum
        for _ in range(6):
            self.device.tap((x, track.center[1]))
            time.sleep(0.6)
            shown = int(re.search(r'(\d+)\s*$', text_node(self.device.nodes()).text).group(1))
            if shown == value:
                return
            x += (value - shown) * span / maximum
        raise StepFailed('The %s slider shows %d, not %d' % (label_prefix, shown, value))

    def hide_keyboard(self):
        self.device.hide_keyboard()

    def field_node(self, label, index=0, match=None):
        """The field labeled label, or, with match, the field match finds."""
        return self.find(match, 'field %s' % label) if match else self.field(label, index)

    def focus(self, label, index=0, match=None):
        """Taps the field labeled label (or the field match finds) until it has focus, and returns
        it as shown once focused. A tap can miss the field, and typing would then reach no field or
        the wrong one."""
        match = match or self.field_match(label, index)

        def focused(nodes):
            found = match(nodes)
            return found if found is not None and found.focused else None
        for _ in range(3):
            node = self.field_node(label, index, match)
            # Left of center: a field's right end holds its Insert marker button.
            self.device.tap((node.left + min(150, node.width // 3), node.center[1]))
            time.sleep(0.6)
            node = self.device.wait(focused, 3)
            if node is not None:
                return node
        raise StepFailed('The %s field did not take focus when tapped' % label)

    def clear_focused(self, label):
        """Empties the field labeled label and leaves it focused, with the keyboard shown. Like
        set_text, it checks that the field is empty, and empties it again if not."""
        self.changed()
        for attempt in range(3):
            node = self.focus(label)
            self.erase_focused(node.text if node.text != label else '', attempt)
            time.sleep(0.5)
            if self.field(label).text in ('', label):
                return
        raise StepFailed('The %s field could not be emptied' % label)

    def erase_focused(self, text, attempt):
        """Erases the focused field's text: through accessibility first, and then, since that does
        not always take, the way a keyboard would, deleting the characters on both sides of the
        cursor, wherever the tap that focused the field put it."""
        if attempt == 0:
            self.device.u2(focused=True).clear_text()
        else:
            count = len(text) + 5
            self.device.shell('input', 'keyevent', *(['KEYCODE_DEL'] * count + ['KEYCODE_FORWARD_DEL'] * count))

    def set_text(self, label, value, index=0, match=None):
        """Replaces the text of the field labeled label (or of the field match finds), then hides
        the keyboard. Now and then the text is not set (and a key reaches the field from the
        keyboard instead), so it is checked and set again."""
        self.changed()
        shown = None
        for attempt in range(3):
            node = self.focus(label, index, match)
            if attempt:
                self.erase_focused(node.text if node.text != label else '', attempt)
            self.device.u2(focused=True).set_text(value)
            time.sleep(0.6)
            self.hide_keyboard()
            shown = self.field_node(label, index, match).text
            if shown == value or (value == '' and shown == label):
                return
        raise StepFailed('The %s field shows %r after setting it to %r' % (label, shown, value))

    # The card format editor

    def tab_row(self, nodes):
        """The strip of card format tabs: scrollable while its tabs do not fit, and otherwise the
        widest short node holding its + button."""
        short = [node for node in self.mine(nodes) if node.height < 80 * self.device.density]
        scrollable = [node for node in short if node.scrollable]
        if scrollable:
            return scrollable[0]
        plus = first(by_desc(self.mine(nodes), 'Add card format'))
        rows = [node for node in short if plus is not None and node.contains(plus)]
        return max(rows, key=lambda node: node.width) if rows else None

    def visible_format_tabs(self, nodes):
        row = self.tab_row(nodes)
        if row is None:
            return []
        tabs = [node for node in self.mine(nodes) if node.cls == 'TextView' and node.text
                and row.contains(node) and not any(field.contains(node) for field in nodes if field.cls == 'EditText')]
        return sorted(tabs, key=lambda node: node.left)

    def scroll_tabs(self, to_end):
        """Scrolls the format tabs to their start or end by tapping the edge-most tab shown, which
        the strip then scrolls into view. (A swipe there would also turn the dashboard's page.)
        Returns the tab names seen, left to right."""
        names = []
        last = None
        for _ in range(8):
            tabs = self.visible_format_tabs(self.device.nodes())
            visible = [tab.text for tab in tabs]
            if to_end:
                names += [name for name in visible if name not in names]
            else:
                names = [name for name in visible if name not in names] + names
            if not tabs or visible == last:
                break
            last = visible
            self.device.tap(tabs[-1] if to_end else tabs[0])
            time.sleep(0.6)
        return names

    def format_names(self):
        """The names on the card format tabs, left to right."""
        self.find(lambda nodes: self.tab_row(nodes), 'card format tabs')
        self.scroll_tabs(to_end=False)
        return self.scroll_tabs(to_end=True)

    def select_format(self, name):
        """Opens the editor of the card format named name."""
        self.find(lambda nodes: self.tab_row(nodes), 'card format tabs')
        self.scroll_tabs(to_end=False)
        for _ in range(8):
            tabs = self.visible_format_tabs(self.device.nodes())
            tab = first([node for node in tabs if node.text == name])
            if tab is not None:
                self.device.tap(tab)
                time.sleep(0.8)
                shown = self.field_value('Name')
                if shown != name:
                    raise StepFailed('The format tab %s opened the editor of %s' % (name, shown))
                return
            if not tabs:
                break
            before = [node.text for node in tabs]
            self.device.tap(tabs[-1])
            time.sleep(0.6)
            if [node.text for node in self.visible_format_tabs(self.device.nodes())] == before:
                break
        raise StepFailed('There is no card format tab %s' % name)

    def add_format(self):
        """Taps the tab strip's +; returns the message shown, if any."""
        self.changed()
        self.find(lambda nodes: self.tab_row(nodes), 'card format tabs')
        self.scroll_tabs(to_end=True)
        plus = first(by_desc(self.mine(self.device.nodes()), 'Add card format'))
        if plus is None:
            raise StepFailed('The card format tabs have no + button')
        self.device.tap(plus)
        message = self.device.wait(lambda nodes: first([node for node in nodes if 'up to 5 card formats' in node.text]), 3)
        time.sleep(0.8)
        return message.text if message else None

    def type_radio(self, type_label):
        """The radio button of the open format's entry type type_label (Term or Kanji)."""
        def match(nodes):
            row = self.tab_row(nodes)
            name = self.field_match('Name')(nodes)
            if row is None or name is None:
                return None
            text = first([node for node in self.mine(nodes) if node.text == type_label and node.cls == 'TextView'
                          and node.top > name.bottom])
            if text is None:
                return None
            radios = [node for node in self.mine(nodes) if node.checkable and node.right <= text.left + 10
                      and abs(node.center[1] - text.center[1]) < 60]
            return max(radios, key=lambda node: node.right) if radios else None
        return self.find(match, 'the %s type button' % type_label)

    def set_type(self, type_label):
        self.device.tap(self.type_radio(type_label))
        time.sleep(0.5)
        if not self.type_radio(type_label).checked:
            raise StepFailed('The %s type button is not selected after tapping it' % type_label)

    def type_selected(self, type_label):
        return self.type_radio(type_label).checked

    def wait_fields_loaded(self, first_field):
        """Waits until choosing a note type has filled in the format's fields."""
        deadline = time.time() + 20
        while time.time() < deadline:
            nodes = self.device.nodes()
            if not any(node.text.startswith('Loading fields') for node in nodes):
                try:
                    self.field(first_field)
                    return
                except StepFailed:
                    pass
            time.sleep(0.5)
        raise StepFailed('The fields of the note type did not appear')

    def editor_fields(self, names):
        """The fields of the open format whose labels are in names, in order: name -> (value,
        overwrite mode or None)."""
        self.to_top()
        fields, last = {}, None
        for _ in range(60):
            nodes = self.device.nodes()
            top, bottom = self.visible_area(nodes)
            # Only fields shown in full: a field the footer or the list's edge cuts off may have
            # lost its label, and is shown in full by the next scroll.
            edits = sorted([node for node in self.mine(nodes) if node.cls == 'EditText'
                            and node.top >= top and node.bottom <= bottom], key=lambda node: node.top)
            previous = None
            for node in edits:
                label = self.label_of(node, nodes)
                if label in names:
                    value = '' if node.text == label else node.text
                    mode = fields.get(label, (None, None))[1]
                    fields[label] = (value, mode)
                    previous = label
                elif label == 'Overwrite' and previous is not None:
                    fields[previous] = (fields[previous][0], node.text)
                else:
                    previous = None
            texts = [node.text for node in self.mine(nodes) if node.text]
            if texts == last or any(node.text == 'Images' and top <= node.top < bottom for node in nodes):
                break
            last = texts
            self.scroll(fraction=0.4)
        return fields

    def set_overwrite_mode(self, field, mode):
        """Sets the overwrite mode shown below the field labeled field."""
        self.changed()

        def match(nodes):
            edits = sorted([node for node in self.mine(nodes) if node.cls == 'EditText'], key=lambda node: node.top)
            for index, node in enumerate(edits[:-1]):
                if self.label_of(node, nodes) == field and self.label_of(edits[index + 1], nodes) == 'Overwrite':
                    return edits[index + 1]
            return None
        node = self.find(match, 'the Overwrite menu of %s' % field)
        items = self.menu_after_tap(node)
        item = first([other for other in items if other.text == mode])
        if item is None:
            self.device.press('back')
            raise StepFailed('The Overwrite menu of %s has no %s' % (field, mode))
        self.device.tap(item)
        time.sleep(0.6)

    def overwrite_options(self, field):
        def match(nodes):
            edits = sorted([node for node in self.mine(nodes) if node.cls == 'EditText'], key=lambda node: node.top)
            for index, node in enumerate(edits[:-1]):
                if self.label_of(node, nodes) == field and self.label_of(edits[index + 1], nodes) == 'Overwrite':
                    return edits[index + 1]
            return None
        items = self.menu_after_tap(self.find(match, 'the Overwrite menu of %s' % field))
        self.device.press('back')
        time.sleep(0.5)
        return [item.text for item in items]

    def dictionary_order(self):
        """The dictionaries tab's cards, top to bottom: their titles."""
        titles = [title for title, _ in plan.DICTIONARIES]
        return [text for text in self.texts() if text in titles]

    def move_dictionary(self, title, direction):
        """Taps the Move Up or Move Down button of title's card."""
        self.changed()
        desc = 'Move Up' if direction == 'up' else 'Move Down'

        def match(nodes):
            label = first([node for node in self.mine(nodes) if node.text == title])
            if label is None:
                return None
            cards = [node for node in self.mine(nodes) if node.clickable and node.contains(label)
                     and node.height > label.height * 2]
            card = min(cards, key=lambda node: node.height) if cards else None
            if card is None:
                return None
            button = first([node for node in self.mine(nodes) if node.desc == desc and card.contains(node)])
            return first([node for node in self.mine(nodes) if node.clickable and button is not None
                          and node.contains(button) and node is not card]) if button else None
        self.device.tap(self.find(match, 'the %s button of %s' % (desc, title)))
        time.sleep(1.0)

    # The Audio card

    def audio_title(self, nodes):
        """The Audio card's title, recognized by the explanation below it."""
        title = first([node for node in self.mine(nodes) if node.text == AUDIO_TITLE and node.cls == 'TextView'])
        if title is None or not any(node.text == AUDIO_EXPLANATION and node.top > title.top for node in nodes):
            return None
        return title

    def audio_add_match(self, nodes):
        """The Audio card's add button, the one text in the tab that reads Add source."""
        return first([node for node in self.mine(nodes) if node.text == AUDIO_ADD and node.cls == 'TextView'])

    def audio_order_match(self, nodes):
        """The Audio card's source order explanation, when the Enable Default Audio Sources label is
        above it, as in desktop's dialog."""
        label = first([node for node in self.mine(nodes) if node.text == AUDIO_DEFAULT_SOURCES])
        explanation = first([node for node in self.mine(nodes) if node.text == AUDIO_EXPLANATION])
        if label is None or explanation is None or label.bottom > explanation.top:
            return None
        return explanation

    def audio_url_match(self, number):
        """A match for the URL field of the source numbered number (1 for the first)."""
        def match(nodes):
            fields = sorted([node for node in self.mine(nodes) if node.cls == 'EditText'], key=lambda node: node.top)
            labels = [self.label_of(node, nodes) for node in fields]
            for index, label in enumerate(labels):
                if label == 'Source %d' % number:
                    following = index + 1
                    if following < len(fields) and labels[following] == AUDIO_URL_LABEL:
                        return fields[following]
            return None
        return match

    def audio_find(self, match, what):
        """Scrolls the Audio card, which is at the end of the tab, until match(nodes) returns a node
        shown in full, and returns it. It looks from the tab's end up to the card's title, so that a
        control the card does not show is not looked for in the whole tab."""
        nodes = self.device.nodes()
        top, bottom = self.visible_area(nodes)
        found = match(nodes)
        if found is not None and found.top >= top + 10 and found.bottom <= bottom - 20:
            return found
        self.to_bottom()
        last = None
        for _ in range(15):
            nodes = self.device.nodes()
            top, bottom = self.visible_area(nodes)
            found = match(nodes)
            if found is not None:
                if found.top < top + 10:
                    self.scroll(down=False, fraction=0.25)
                    continue
                if found.bottom > bottom - 20:
                    self.scroll(down=True, fraction=0.25)
                    continue
                return found
            title = self.audio_title(nodes)
            texts = [node.text for node in self.mine(nodes) if node.text]
            if (title is not None and title.top >= top) or texts == last:
                break
            last = texts
            self.scroll(down=False, fraction=0.4)
        raise StepFailed('The Audio card shows no %s' % what)

    def audio_switch_match(self, nodes):
        return self.switch_match(AUDIO_DEFAULT_SOURCES, AUDIO_SWITCH_REACH * self.device.density)(nodes)

    def audio_switch(self):
        """The Enable Default Audio Sources switch."""
        return self.audio_find(self.audio_switch_match, 'switch %s' % AUDIO_DEFAULT_SOURCES)

    def set_audio_switch(self, on):
        self.audio_switch()
        self.set_switch(AUDIO_DEFAULT_SOURCES, on, AUDIO_SWITCH_REACH * self.device.density)

    def audio_shows(self, match):
        """Whether match(nodes) finds a node in the Audio card."""
        try:
            self.audio_find(match, '')
            return True
        except StepFailed:
            return False

    def audio_sources(self):
        """The Audio card's sources, in order, as (type label, URL) pairs, where URL is the text
        of the source's URL field, or None for a source that shows none."""
        self.to_bottom()
        rows = {}
        last = None
        seen_title = False
        for _ in range(15):
            nodes = self.device.nodes()
            top, bottom = self.visible_area(nodes)
            fields = sorted([node for node in self.mine(nodes) if node.cls == 'EditText' and node.top >= top
                             and node.bottom <= bottom], key=lambda node: node.top)
            # A URL field belongs to the source right above it, which must be shown with it.
            number = None
            for node in fields:
                label = self.label_of(node, nodes)
                match = AUDIO_SOURCE_LABEL.match(label or '')
                if match:
                    number = int(match.group(1))
                    rows.setdefault(number, [None, None])[0] = node.text
                elif label == AUDIO_URL_LABEL and number is not None:
                    rows[number][1] = '' if node.text == AUDIO_URL_LABEL else node.text
                else:
                    number = None
            title = self.audio_title(nodes)
            seen_title = title is not None and title.top >= top
            texts = [node.text for node in self.mine(nodes) if node.text]
            if seen_title or texts == last:
                break
            last = texts
            self.scroll(down=False, fraction=0.4)
        if not seen_title:
            raise StepFailed('The Anki Export tab shows no Audio card')
        numbers = sorted(rows)
        if numbers != list(range(1, len(numbers) + 1)):
            raise StepFailed('The Audio card\'s sources are numbered %s' % numbers)
        return [tuple(rows[number]) for number in numbers]

    def remove_audio_sources(self):
        """Removes every source of the Audio card with its remove button."""
        self.changed()
        for _ in range(len(self.audio_sources())):
            self.device.tap(self.audio_find(lambda nodes: first(by_desc(self.mine(nodes), AUDIO_REMOVE)),
                                            'remove button'))
            time.sleep(0.8)

    def add_audio_source(self):
        """Taps the Audio card's Add source button."""
        self.changed()
        self.device.tap(self.audio_find(self.audio_add_match, 'Add source button'))
        time.sleep(0.8)

    def set_audio_settings(self, sources, default_sources, timeout):
        """Sets the Audio card: removes every source, adds one per (type label, URL or None) of
        sources, in order, sets the Enable Default Audio Sources switch to default_sources, and
        types timeout into the idle download timeout field. Then checks the sources it shows."""
        self.remove_audio_sources()
        for number, (source_type, url) in enumerate(sources, 1):
            self.add_audio_source()
            label = 'Source %d' % number
            self.audio_find(self.field_match(label), 'the %s menu' % label)
            self.choose(label, source_type)
            if url is not None:
                match = self.audio_url_match(number)
                self.audio_find(match, 'URL field for %s' % label)
                self.set_text(AUDIO_URL_LABEL, url, match=match)
        self.set_audio_switch(default_sources)
        self.audio_find(self.field_match(AUDIO_TIMEOUT), 'field %s' % AUDIO_TIMEOUT)
        if self.field_value(AUDIO_TIMEOUT) != str(timeout):
            self.set_text(AUDIO_TIMEOUT, str(timeout))
        shown = self.audio_sources()
        wanted = [(source_type, url) for source_type, url in sources]
        if shown != wanted:
            raise StepFailed('The Audio card shows the sources %r after setting them to %r' % (shown, wanted))


# ---------------------------------------------------------------------------------------------
# AnkiDroid's notes, through the test helper


ANKIDROID_PERMISSION = 'com.ichi2.anki.permission.READ_WRITE_DATABASE'
PRO_MESSAGE_MARK = 'images require Dokuen Pro'
# The card formats whose note type has image fields: an add through one of them without Dokuen Pro
# shows a message about it.
IMAGE_FORMATS = ['AuditVocab', 'AuditSub', 'AuditQuoteKey', 'AuditBadMarker']


class Collection:
    """AnkiDroid's notes, read and written through the plugin's test helper. Each call restarts
    the plugin's process, so the next scan restarts Dokuen's session first."""

    def __init__(self, run):
        self.run = run

    def helper(self, action, **arguments):
        # Android lets one client at a time drive the UI. The helper takes it (to grant itself
        # the AnkiDroid permission), so uiautomator2's agent lets go of it first; the next UI
        # call starts the agent again.
        self.run.device.release_ui()
        result = plan.run_helper(self.run.args.adb, self.run.args.serial, action, **arguments)
        self.run.plugin_restarted = True
        return result

    def notes(self, query):
        return self.helper('dumpNotes', query=query)['notes']

    def count(self, query):
        return len(self.notes(query))

    def delete(self, query):
        return self.helper('deleteNotes', query=query)['deleted']

    def add(self, model, deck, fields, tags=()):
        spec = {'model': model, 'deck': deck, 'fields': fields, 'tags': list(tags)}
        encoded = base64.b64encode(json.dumps(spec).encode('utf-8')).decode('ascii')
        return self.helper('addNote', spec=encoded)['id']

    def create_decks(self):
        spec = base64.b64encode(json.dumps({'decks': plan.DECKS, 'models': plan.NOTE_TYPES})
                                     .encode('utf-8')).decode('ascii')
        return self.helper('setupCollection', spec=spec)

    def newest(self, query):
        notes = self.notes(query)
        if not notes:
            raise StepFailed('AnkiDroid has no note matching %s' % query)
        note = max(notes, key=lambda item: item['id'])
        return dict(zip(note['fieldNames'], note['fields']))


# ---------------------------------------------------------------------------------------------
# The run


def step_status(result):
    if result['skipped']:
        return 'SKIPPED'
    if result['error'] is not None:
        return result['error_kind']
    if any(not check['ok'] for check in result['checks']):
        return 'FAIL'
    deferred = result.get('deferred', [])
    if any(item['answer'] is False for item in deferred):
        return 'FAIL'
    if any(item['answer'] is None for item in deferred):
        return 'PENDING'
    if result['review']:
        return 'REVIEW'
    if result['asked']:
        return 'MANUAL' if result['kind'] == 'manual' else 'PARTIAL'
    return 'PASS'


def summary(result):
    if result['skipped']:
        return result['skipped']
    if result['error']:
        return result['error']
    failed = [check['message'] for check in result['checks'] if not check['ok']]
    failed += ['%s: no (%s)' % (item['question'], item['detail']) for item in result.get('deferred', [])
               if item['answer'] is False]
    if failed:
        return '; '.join(failed)
    pending = [item['question'] for item in result.get('deferred', []) if item['answer'] is None]
    if pending:
        return '%d questions for the review' % len(pending)
    if result['review']:
        return '; '.join(result['review'])
    return '%d checks' % len(result['checks'])


def write_report(folder, report):
    with open(os.path.join(folder, 'report.json'), 'w', encoding='utf-8') as out:
        json.dump(report, out, ensure_ascii=False, indent=1)
    with open(os.path.join(folder, 'report.md'), 'w', encoding='utf-8') as out:
        out.write('# Anki test plan: Android run\n\n')
        for warning in report.get('warnings', []):
            out.write('**Warning:** %s\n\n' % warning)
        if report.get('misreads'):
            out.write('**OCR misreads** (word, what the OCR read, the line\'s scale): %s\n\n' % plan.md_cell(
                ', '.join('%s as %s at %sx' % tuple(misread) for misread in report['misreads'])))
        out.write('Statuses: PASS (every check held), FAIL (a check did not hold), ERROR (the script could '
                  'not finish the step), PENDING (a question deferred to the review at the end; answer it '
                  'with the review command), REVIEW (a person has to decide), PARTIAL (checked, but a '
                  'question for the tester was skipped), MANUAL (a tester step not done), SKIPPED (a '
                  'step for another configuration, the plan\'s R10).\n\n')
        out.write('Configuration: %s.\n\n' % report.get('configuration', 'not known'))
        out.write('| Step | Kind | Status | Summary |\n|---|---|---|---|\n')
        for result in report['steps']:
            out.write('| %s | %s | %s | %s |\n' % (result['step'], result['kind'], result['status'],
                                                   plan.md_cell(summary(result))))
        for result in report['steps']:
            out.write('\n## %s (%s)\n\n%s\n\n' % (result['step'], result['status'], result['text']))
            for check in result['checks']:
                out.write('- %s %s\n' % ('ok' if check['ok'] else '**FAIL**', plan.md_cell(check['message'])))
                if check['evidence'] is not None and not check['ok']:
                    out.write('  - %s\n' % plan.md_cell(check['evidence']))
            for item in result.get('deferred', []):
                if item['answer'] is None:
                    out.write('- **pending:** %s\n' % plan.md_cell(item['question']))
                else:
                    out.write('- review: %s -> %s\n' % (plan.md_cell(item['question']), 'yes' if item['answer']
                                                         else '**no**: %s' % plan.md_cell(item['detail'])))
                for index, path in enumerate(item['images']):
                    out.write('  - image: [%s](%s)\n' % (path, path.replace(os.sep, '/')) if path else
                              '  - image %d: **missing** (AnkiDroid has no such file)\n' % (index + 1))
            for note in result['notes']:
                out.write('- note: %s\n' % plan.md_cell(note))
            for item in result['review']:
                out.write('- **review:** %s\n' % plan.md_cell(item))
            for question in result['asked']:
                out.write('- not answered: %s\n' % plan.md_cell(question))
            if result['error']:
                out.write('- **%s**\n' % plan.md_cell(result['error']))
            for name in result['screenshots']:
                out.write('- screenshot: [%s](%s)\n' % (name, name))


def review_html(folder, report):
    """review.html: each deferred question with its images side by side and the expected value."""
    parts = ['<!doctype html><meta charset="utf-8"><title>Anki test plan review</title>',
             '<style>body{font-family:sans-serif;margin:16px}img{max-width:45%;margin:4px;'
             'border:1px solid #888;vertical-align:top}.q{margin:24px 0}.missing{color:#b00020}</style>', '<h1>Review</h1>']
    number = 0
    for result in report['steps']:
        for item in result.get('deferred', []):
            if item['answer'] is not None:
                continue
            number += 1
            parts.append('<div class="q"><h2>%d. Step %s</h2><p>%s</p><p>Expected: %s</p>%s</div>' % (
                number, result['step'], html.escape(item['question']), html.escape(item['expected'] or ''),
                ''.join('<img src="%s">' % html.escape(path.replace(os.sep, '/')) if path else
                        '<p class="missing">(image %d missing: AnkiDroid has no such file; see the report\'s notes)</p>'
                        % (index + 1) for index, path in enumerate(item['images']))))
    path = os.path.join(folder, 'review.html')
    with open(path, 'w', encoding='utf-8') as out:
        out.write('\n'.join(parts))
    return path


def ask_terminal():
    """One review answer: (True, ''), (False, what the tester saw), or (None, '') to stop."""
    while True:
        try:
            answer = input('   [y]es / [n]o / [s]top: ').strip().lower()
        except (EOFError, KeyboardInterrupt):
            return None, ''
        if answer in ('y', 'yes'):
            return True, ''
        if answer in ('n', 'no'):
            try:
                return False, input('   What did you see? ').strip()
            except (EOFError, KeyboardInterrupt):
                return False, ''
        if answer in ('s', 'stop'):
            return None, ''


def review(folder, report, ask):
    """Asks every unanswered deferred question, with review.html open, and records the answers."""
    pending = [(result, item) for result in report['steps'] for item in result.get('deferred', [])
               if item['answer'] is None]
    if not pending:
        print('Nothing to review.')
        return
    webbrowser.open('file:///' + os.path.abspath(review_html(folder, report)).replace(os.sep, '/'))
    print('\nReview: %d questions; review.html shows each one\'s images.' % len(pending))
    for number, (result, item) in enumerate(pending, 1):
        print('\n%d. [%s] %s\n   Expected: %s' % (number, result['step'], item['question'], item['expected'] or ''))
        for index, path in enumerate(item['images']):
            if path is None:
                print('   Image %d is missing: AnkiDroid has no such file.' % (index + 1))
        try:
            answer, detail = ask()
        except KeyboardInterrupt:
            answer = None
        if answer is None:
            print('Stopped; the rest stay PENDING (run the review command again).')
            break
        item['answer'], item['detail'] = answer, detail
        # Each answer is kept at once, so that stopping loses none.
        result['status'] = step_status(result)
        write_report(folder, report)


def plan_steps():
    """Each step of Part 1 of the plan: ID -> its text, with its sub-bullets."""
    steps, current, in_procedure = {}, None, False
    with open(PLAN_FILE, encoding='utf-8') as source:
        for line in source:
            if line.startswith('# Part 1'):
                in_procedure = True
                continue
            if line.startswith('# Part 2'):
                break
            if not in_procedure:
                continue
            match = re.match(r'^- \[ \] \*\*(\d+\.\d+)\*\* (.*\S)\s*$', line)
            sub = re.match(r'^\s+- (.*\S)\s*$', line)
            if match:
                current = match.group(1)
                steps[current] = match.group(2)
            elif sub and current:
                steps[current] += '\n  - ' + sub.group(1)
            else:
                current = None
    return steps


def step_key(step_id):
    phase, number = step_id.split('.')
    return int(phase), int(number)


class Step:
    def __init__(self, step_id, kind, function):
        self.id = step_id
        self.kind = kind
        self.function = function
        self.phase = int(step_id.split('.')[0])
        # For a manual step: what the tester does (shown, then waited for), and what to confirm.
        self.do = None
        self.questions = []


class Condition:
    """The configuration a phase is for: host 'old' (older than SDK 0.7.0) or 'new' (SDK 0.7.0 or
    later), and, on a new host, whether Dokuen Pro is active (None: either)."""

    def __init__(self, host, pro=None):
        self.host = host
        self.pro = pro

    def describe(self):
        if self.host == 'old':
            return 'a host older than SDK 0.7.0'
        return 'a host at SDK 0.7.0 or later, %s Dokuen Pro' % ('with' if self.pro else 'without')

    def short(self):
        return 'old host' if self.host == 'old' else ('new, Pro' if self.pro else 'new, no Pro')


# The phases of the plan that are for one configuration only (its R10).
PHASES = {11: Condition('old'), 12: Condition('new', pro=False), 13: Condition('new', pro=True)}


STEPS = {}


def step(step_id, kind='auto'):
    """Registers the function as the automation of the plan's step step_id."""
    def register(function):
        STEPS[step_id] = Step(step_id, kind, function)
        return function
    return register


def manual(step_id, do=None, questions=()):
    """Registers a step the tester does. do: what to do, shown and waited for (Enter), with
    nothing to confirm. questions: what to confirm, each naming what to look at and the expected
    value. A step with neither is shown and asks one question per "Confirm ..." sentence of its
    text, as a step of the plan this script does not know does."""
    STEPS[step_id] = Step(step_id, 'manual', None)
    STEPS[step_id].do = do
    STEPS[step_id].questions = list(questions)


def confirmations(text):
    """The "Confirm ..." sentences of a step's text, one question each."""
    sentences = re.split(r'(?<=[.!?])\s+', text.replace('\n  - ', ' '))
    return [sentence for sentence in sentences if sentence.startswith('Confirm')]


class Run:
    def __init__(self, args, folder):
        self.args = args
        self.folder = folder
        self.texts = plan_steps()
        self.device = Device(args.serial, folder)
        self.device.warn = self.warn_once
        self.page = TestPage(self.device, args.port, args.adb, args.serial)
        self.page.ensure_served()
        self.dokuen = Dokuen(self)
        self.dashboard = Dashboard(self)
        self.anki = Collection(self)
        # Dokuen only reconnects to the plugin when its session starts, and the plugin may have been
        # reinstalled (by setup) or restarted (by the helper) since this session began: the first
        # scan starts a new session.
        self.plugin_restarted = True
        # Whether Dokuen Pro is active: from --pro, or else learned from the first add on a host at
        # SDK 0.7.0 or later (None until then).
        self.pro = {'yes': True, 'no': False}.get(args.pro)
        # The SDK version of the host, as the plugin saved it ('' for a host older than SDK 0.7.0;
        # None until ensure_configuration has read it).
        self.host = None
        # Why ensure_configuration failed, once it has failed. A failed read is not tried again.
        self.configuration_error = None
        self.results = []
        self.current = None
        self.warnings = []
        # Words the OCR misread: (word, what it read, the line's scale).
        self.misreads = []
        # Values a later step compares with, such as 13.3's screenshot for 13.8.
        self.state = {}
        # The screen must stay on for the whole run; the device's own setting comes back in close().
        self.stay_on = self.adb('shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in').strip()
        self.adb('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '7')
        self.device.u2.screen_on()
        if 'isKeyguardShowing=true' in self.adb('shell', 'dumpsys', 'window'):
            self.device.u2.unlock()

    def close(self):
        if self.stay_on.isdigit():
            self.adb('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', self.stay_on)
        if self.page.server is not None:
            self.page.server.shutdown()

    # Recording

    def check(self, ok, message, evidence=None):
        """Records one check of the current step."""
        self.current['checks'].append({'ok': bool(ok), 'message': message, 'evidence': evidence})
        print('    %s %s' % ('ok  ' if ok else 'FAIL', message))
        if not ok and evidence is not None:
            print('         %s' % str(evidence).replace('\n', '\n         '))
        return bool(ok)

    def expect(self, actual, expected, what):
        return self.check(actual == expected, '%s is %r' % (what, expected),
                          None if actual == expected else 'found %r' % (actual,))

    def note(self, message):
        """Records something the tester should know about the step, without a verdict."""
        if self.current is not None:
            self.current['notes'].append(message)
        print('    note %s' % message)

    def review(self, message):
        """Records something a person has to decide."""
        self.current['review'].append(message)
        print('    REVIEW %s' % message)

    def warn_once(self, message):
        if message not in self.warnings:
            self.warnings.append(message)
            print('\nWARNING: %s\n' % message)

    def shot(self, label):
        name, image = self.device.screenshot('%s-%s' % (self.current['step'] if self.current else 'run', label))
        if self.current is not None:
            self.current['screenshots'].append(name)
        return image

    # The tester

    def ask(self, question):
        """Asks the tester a yes/no question: True, False, or None when skipped or unattended."""
        if self.args.unattended:
            self.current['asked'].append(question)
            return None
        while True:
            try:
                answer = input('    ? %s [y]es / [n]o / [s]kip: ' % question).strip().lower()
            except EOFError:
                answer = 's'
            if answer in ('y', 'yes'):
                return True
            if answer in ('n', 'no'):
                try:
                    detail = input('      What did you see? ').strip()
                except EOFError:
                    detail = ''
                if detail:
                    self.current['notes'].append('tester: %s' % detail)
                return False
            if answer in ('s', 'skip'):
                return None

    def confirm(self, question):
        """A check only a person can make."""
        answer = self.ask(question)
        if answer is None:
            self.current['asked'].append(question)
            print('    skip %s' % question)
        else:
            self.check(answer, 'tester: %s' % question)
        return answer

    def wait_for_tester(self, instruction):
        """Asks the tester to do something; returns once they have (False if they could not)."""
        if self.args.unattended:
            raise StepFailed('Needs the tester: %s' % instruction)
        print('    > %s' % instruction)
        answer = self.ask('Done?')
        if not answer:
            raise StepFailed('The tester did not: %s' % instruction)

    # Steps

    def configuration_line(self):
        if self.host is None:
            return 'not known yet'
        line = 'host %s' % plan.host_description(self.host)
        if plan.host_at_least(self.host, plan.IMAGES_SDK):
            line += ', Dokuen Pro %s' % {True: 'active', False: 'not active', None: 'not known yet'}[self.pro]
        return line

    def ensure_configuration(self):
        """Learns the host's SDK level: starts a Dokuen session, scans a word (which initializes
        the plugin), and reads the version the plugin saved."""
        if self.host is not None or not self.installed(plan.PACKAGE):
            return
        if self.configuration_error is not None:
            raise StepFailed('The configuration could not be read: %s' % self.configuration_error)
        try:
            self.scan('食べる')
            self.dokuen.hide_overlay()
            state = self.anki.helper('dumpPluginState')
            self.host = state.get('hostSdkVersion')
            if self.host is None:
                raise RuntimeError('The plugin saved no host SDK version after a scan; is the installed '
                                   'plugin older than this script?')
        except Exception as error:
            self.configuration_error = str(error) or type(error).__name__
            raise
        print('\nConfiguration: %s' % self.configuration_line())

    def phase_skip_reason(self, phase):
        """Why the phase is not for this run's configuration, or None when it is."""
        condition = PHASES.get(phase)
        if condition is None:
            return None
        self.ensure_configuration()
        new = plan.host_at_least(self.host, plan.IMAGES_SDK)
        if (condition.host == 'new') != new:
            return 'for %s; this host is %s' % (condition.describe(), plan.host_description(self.host))
        if condition.pro is not None:
            if self.pro is None and not self.args.unattended:
                self.pro = self.ask('Is Dokuen Pro active on this device?')
            if self.pro is None:
                return 'for %s; whether Dokuen Pro is active is not known (pass --pro yes or --pro no)' % (
                    condition.describe())
            if self.pro != condition.pro:
                return 'for %s' % condition.describe()
        return None

    def run_step(self, step_object):
        text = self.texts.get(step_object.id, '(not in the plan)')
        print('\n[%s] %s (%s)\n    %s' % (step_object.id, '-' * 60, step_object.kind,
                                         text.replace('\n', '\n    ')))
        self.current = {'step': step_object.id, 'kind': step_object.kind, 'text': text, 'checks': [],
                        'notes': [], 'review': [], 'asked': [], 'screenshots': [], 'error': None,
                        'skipped': None, 'deferred': []}
        started = time.time()
        try:
            reason = self.phase_skip_reason(step_object.phase)
            if reason:
                self.current['skipped'] = reason
            if self.current['skipped']:
                print('    skipped: %s' % self.current['skipped'])
            elif step_object.kind == 'manual':
                questions = step_object.questions or confirmations(text)
                if step_object.do or not questions:
                    self.wait_for_tester(step_object.do or 'Do the step above')
                for question in questions:
                    self.confirm(question)
            else:
                step_object.function(self)
        except StepFailed as error:
            self.current['error'] = str(error)
            self.current['error_kind'] = 'FAIL'
            print('    FAIL %s' % error)
        except Exception as error:  # A bug in this script or an unexpected screen: record it, go on.
            self.current['error'] = '%s: %s' % (type(error).__name__, error)
            self.current['error_kind'] = 'ERROR'
            traceback.print_exc()
        self.current['seconds'] = round(time.time() - started, 1)
        self.current['status'] = step_status(self.current)
        if self.current['status'] in ('FAIL', 'ERROR'):
            try:
                self.shot('failure')
            except Exception:
                pass
        print('    => %s' % self.current['status'])
        self.results.append(self.current)
        self.current = None

    def report_data(self):
        """What report.json holds, and what write_report and review work on."""
        return {'configuration': self.configuration_line(), 'warnings': self.warnings,
                'misreads': self.misreads, 'steps': self.results}

    def write_report(self):
        write_report(self.folder, self.report_data())

    # Deferred questions

    def pull_media(self, value):
        """Copies the media file a field's <img> names into the report's media folder; returns the
        local path, or None (recorded as a note) when AnkiDroid has no such file."""
        name = image_file(value)
        if name is None:
            return None
        local = plan.pull_anki_media(self.args.adb, self.args.serial, name, os.path.join(self.folder, 'media'))
        if local is None:
            self.note('AnkiDroid has no media file %s' % name)
        return local

    def defer(self, question, images=(), expected=None):
        """A judgment only a person can make, asked in the review at the end of the run."""
        # A missing image (None: AnkiDroid has no such file) keeps its position, so that the images the
        # question names by position stay the right ones.
        item = {'question': question, 'images': [os.path.relpath(path, self.folder) if path else None for path in images],
                'expected': expected, 'answer': None, 'detail': ''}
        self.current['deferred'].append(item)
        print('    deferred: %s' % question)

    # Shared actions

    def adb(self, *arguments):
        return plan.run_adb(self.args.adb, self.args.serial, list(arguments))

    def installed(self, package):
        return ('package:%s' % package) in self.adb('shell', 'pm', 'list', 'packages', package).split()

    def permission_granted(self):
        output = self.adb('shell', 'dumpsys', 'package', plan.PACKAGE)
        return re.search(re.escape(ANKIDROID_PERMISSION) + r': granted=true', output) is not None

    def scan(self, word, line=None, repair=False):
        if self.plugin_restarted:
            # The helper restarted the plugin's process; a new session reconnects Dokuen to it.
            self.dokuen.restart_session()
            self.plugin_restarted = False
        return self.dokuen.scan(word, line, repair)

    def observe_pro(self, status, card_format, written):
        """Learns whether Dokuen Pro is active from an add: one through a format with image fields
        shows a message about them without it, and none with it."""
        if not written or card_format not in IMAGE_FORMATS:
            return
        if self.host is None or not plan.host_at_least(self.host, plan.IMAGES_SDK):
            # A host older than SDK 0.7.0 shows no message about images, with or without Dokuen Pro.
            return
        if status and PRO_MESSAGE_MARK not in status:
            # A write shows at most one message, and one about an unknown marker, rejected media, or
            # an empty {audio} takes the place of the one about images, so this add shows nothing
            # about Dokuen Pro.
            return
        observed = not status
        if self.pro is None:
            self.pro = observed
            print('    (Dokuen Pro is %s on this device)' % ('active' if observed else 'not active'))
        elif self.pro != observed:
            self.warn_once('--pro says Dokuen Pro is %s, but an add shows that it is %s' % (
                'active' if self.pro else 'not active', 'active' if observed else 'not active'))

    def add(self, word, headword, reading, card_format, line=None, outcome='Added'):
        """Scans word, adds headword【reading】 via card_format, and checks that the note was
        written: the link reads "<outcome>: <card_format>"."""
        self.scan(word, line)
        entry = self.dokuen.find_entry(headword, reading, card_format)
        popup, label, status = self.dokuen.tap_link(entry, card_format)
        self.check_written(label, status, card_format, outcome)
        return popup, label, status

    def check_written(self, label, status, card_format, outcome='Added'):
        outcomes = outcome if isinstance(outcome, tuple) else (outcome,)
        written = label is not None and any(label.endswith('%s: %s' % (word, card_format)) for word in outcomes)
        if not written and status and PRO_MESSAGE_MARK in status:
            # A host that shows a message instead of the refreshed result after a write.
            written = status.startswith('Note added') or status.startswith('Note updated')
            self.observe_pro(status, card_format, written)
            return self.check(written, 'the note was written (the link was not refreshed: %s)' % status)
        self.observe_pro(status, card_format, written)
        return self.check(written, 'the link reads %s' % ' or '.join('"%s: %s"' % (word, card_format) for word in outcomes),
                          'link: %r, message: %r' % (label, status))

    def expect_message(self, status, expected, contains=False):
        ok = status is not None and (expected in status if contains else status == expected)
        return self.check(ok, 'the message %s "%s"' % ('includes' if contains else 'reads', expected),
                          'message: %r' % status)

    def set_duplicate_settings(self, behavior=None, scope=None, all_models=None):
        dashboard = self.dashboard
        dashboard.open()
        if behavior is not None:
            dashboard.choose('When a duplicate is detected', behavior)
        if scope is not None:
            dashboard.choose('Duplicate card scope', scope)
        if all_models is not None:
            dashboard.set_switch('Check for duplicates across all models', all_models)

    def restore(self, what, action):
        """Calls action, which puts back what a step changed, from a finally block. A failure is
        recorded as a failed check, so that it does not hide the step's own error."""
        try:
            action()
        except Exception as error:
            self.check(False, 'the step could not put back %s' % what, '%s: %s' % (type(error).__name__, error))

    @contextlib.contextmanager
    def audio_settings(self, sources, default_sources=False, timeout=0):
        """Sets the Audio card for the length of the with block: sources, a list of (type label,
        URL or None), the Enable Default Audio Sources switch, and the idle download timeout. Then,
        even when the block fails, puts back the defaults: no sources, the switch on, and 0."""
        def defaults():
            self.dashboard.open()
            self.dashboard.set_audio_settings([], True, 0)
        try:
            self.dashboard.open()
            self.dashboard.set_audio_settings(sources, default_sources, timeout)
            yield
        finally:
            self.restore('the audio settings (no sources, the default sources on, and no timeout)', defaults)

    def network_validated(self):
        """Whether Android has validated one of the device's networks as reaching the internet.
        dumpsys lists each network with its capabilities, which include VALIDATED once Android has."""
        dump = self.adb('shell', 'dumpsys', 'connectivity')
        return any(re.search(r'Capabilities: [A-Z_&]*\bVALIDATED\b', line) for line in dump.splitlines()
                   if 'NetworkAgentInfo' in line and 'nc{' in line)

    def wait_network(self, validated, timeout):
        """Waits up to timeout seconds for network_validated() to return validated, and returns whether
        it did."""
        deadline = time.time() + timeout
        while self.network_validated() != validated:
            if time.time() > deadline:
                return False
            time.sleep(1)
        return True

    @contextlib.contextmanager
    def network_off(self):
        """Turns Wi-Fi and mobile data off for the length of the with block, and then, even when the
        block fails, on again, waiting up to 30 seconds for a validated network. adb must reach the
        device over USB: over the network (adb connect, or wireless debugging), turning it off would
        cut the connection. Without --serial, the serial checked is that of the device adb picks."""
        serial = self.args.serial or self.adb('get-serialno').strip()
        if over_network(serial):
            raise StepFailed('adb reaches the device over the network (%s), which this step turns off; connect the '
                             'device over USB and run the step again' % serial)

        def network_on():
            self.adb('shell', 'svc', 'wifi', 'enable')
            self.adb('shell', 'svc', 'data', 'enable')
            if not self.wait_network(True, 30):
                raise StepFailed('Android has validated no network 30 seconds after Wi-Fi and mobile data were '
                                 'turned back on')
        try:
            self.adb('shell', 'svc', 'wifi', 'disable')
            self.adb('shell', 'svc', 'data', 'disable')
            if not self.wait_network(False, 20):
                raise StepFailed('The device still has a validated network after Wi-Fi and mobile data were '
                                 'turned off')
            yield
        finally:
            self.restore('Wi-Fi and mobile data', network_on)

    def audio_mark(self):
        """Where the test page server's log of /audio/ requests ends now, for audio_requests_since
        (0 when another process serves the page)."""
        return len(self.page.server.audio_requests) if self.page.server is not None else 0

    def audio_requests_since(self, mark):
        """The test page server's log of /audio/ requests after its first mark entries."""
        if self.page.server is None:
            raise StepFailed('Another process (setup or serve) serves the test page, so this run cannot read its '
                             'log of audio requests; read the requests that process prints, or stop it and run '
                             'the step again')
        return self.page.server.audio_requests[mark:]

    def note_unexpected_audio(self, mark):
        """Notes each request since mark that the test page's server had no audio for."""
        if self.page.server is None:
            self.warn_once('Another process (setup or serve) serves the test page, so the report does not note '
                           'the requests to the local audio sources that it had no audio for; that process '
                           'prints them')
            return
        for request in self.audio_requests_since(mark):
            if not request['expected']:
                self.note('the test page\'s server has no audio for %s, and answered it with a 404' % request['path'])

    def link_color(self, node, image):
        """The color name of a link, from the pixels of its text that differ from the background."""
        pixels = [image.getpixel((x, y))[:3] for x in range(node.left, node.right, 2)
                  for y in range(node.top, node.bottom, 2)]
        if not pixels:
            return None
        background = max(set(pixels), key=pixels.count)

        def distance(a, b):
            return sum((x - y) ** 2 for x, y in zip(a, b)) ** 0.5
        ink = [pixel for pixel in pixels if distance(pixel, background) > 90]
        if not ink:
            return None
        ink.sort(key=lambda pixel: distance(pixel, background))
        sample = ink[len(ink) * 3 // 4]
        name, color = min(LINK_COLORS.items(), key=lambda item: distance(sample, item[1]))
        return name if distance(sample, color) <= COLOR_TOLERANCE else 'unknown %02X%02X%02X' % sample

    def check_links(self, entry, expected, image):
        """expected: card format -> (label word, color, glyph or None)."""
        for card_format, (word, color, glyph) in expected.items():
            link = entry.link(card_format)
            if not self.check(link is not None, '%s has a link for %s' % (entry.headword, card_format)):
                continue
            match = LINK_PATTERN.match(link.text)
            self.expect(match.group(2), word, 'the %s link\'s label' % card_format)
            if glyph is not None:
                self.expect(match.group(1), glyph, 'the %s link\'s glyph' % card_format)
            self.expect(self.link_color(link, image), color, 'the %s link\'s color' % card_format)

    def entry_dictionaries(self, headword, reading):
        """The dictionaries of an entry's definitions, in the order the window shows them."""
        entry = self.dokuen.find_entry(headword, reading, 'AuditVocab')
        popup = self.dokuen.popup()
        if not entry.header:
            raise StepFailed('The %s entry is taller than the window; compare its definitions by hand' % headword)
        bottom = entry.links[0].top if entry.links else popup.content_bottom
        titles = [title for title, _ in plan.DICTIONARIES[:2]]
        found = []
        for node in popup.texts:
            if entry.header[0].top <= node.top < bottom:
                for title in titles:
                    if title in node.text:
                        found.append(title)
        return found


# ---------------------------------------------------------------------------------------------
# Phases 1 and 2: before you start, and setup

ANKI_SETTING_LABELS = ['Card tags', 'Check for card duplicates', 'Check for duplicates across all models',
                       'Duplicate card scope', 'When a duplicate is detected', 'Card Formats', 'Images']
OVERWRITE_MODES = ['Skip', 'Prepend', 'Append', 'Fill if empty', 'Overwrite if available', 'Overwrite']
TAGS_TYPED = 'jlpt-n3, vocab;core2k listening'
TAGS_FORMATTED = 'jlpt-n3 vocab core2k listening'
FORMAT_NAMES = [card_format[0] for card_format in plan.CARD_FORMATS]

@step('1.1')
def device_connected(run):
    state = run.adb('get-state').strip()
    run.expect(state, 'device', 'adb\'s state of the device')


# How Dokuen's Plugin Manager lists this plugin: its manifest's plugin_name and plugin_description.
PLUGIN_LIST_NAME = 'Yomitan'
PLUGIN_LIST_DESCRIPTION = 'Dokuen port of the popular Yomitan browser extension.'


@step('1.2')
def host_lists_plugin(run):
    run.check(run.installed(HOST), 'Dokuen is installed')
    run.check(run.installed(plan.PACKAGE), 'the plugin is installed')
    device = run.device
    device.start_app(HOST)
    # Dokuen reopens on the screen it was left on; back out to its main page, which has Settings.
    for _ in range(4):
        if device.wait(lambda nodes: first([node for node in nodes if node.package == HOST
                                            and node.desc == 'Settings']), 3) is not None:
            break
        device.press('back')
        time.sleep(1)
        if device.resumed_package() != HOST:
            device.start_app(HOST)
    # Settings is an icon on Dokuen's main page (its description), and a list row or tab after it.
    for label in ('Settings', 'Plugin Manager', 'Dictionary Plugins'):
        target = device.wait_settled(lambda nodes: first([node for node in nodes if node.package == HOST
                                                          and label in (node.text, node.desc)]), 10)
        if target is None:
            raise StepFailed('Dokuen shows no "%s"' % label)
        device.tap(target)
        time.sleep(1.5)
    listed = device.wait(lambda nodes: nodes if by_text(nodes, PLUGIN_LIST_DESCRIPTION) else None, 10) or []
    name = first(by_text([node for node in listed if node.package == HOST], PLUGIN_LIST_NAME))
    run.check(name is not None and by_text(listed, PLUGIN_LIST_DESCRIPTION),
              'Dokuen\'s Dictionary Plugins lists "%s" (%s)' % (PLUGIN_LIST_NAME, PLUGIN_LIST_DESCRIPTION))
    if name is not None:
        # Each plugin's card has a toggle, described "Selected" or "Not selected", on its title's row.
        toggles = [node for node in listed if node.package == HOST and node.desc in ('Selected', 'Not selected')]
        # Only a toggle whose row contains the title's center is the plugin's own.
        toggle = min([node for node in toggles if node.top <= name.center[1] <= node.bottom],
                     key=lambda node: abs(node.center[1] - name.center[1]), default=None)
        run.check(toggle is not None and toggle.desc == 'Selected',
                  'the plugin is selected, so that Dokuen looks words up with it',
                  'its toggle reads %r' % toggle.desc if toggle else 'no toggle was found on the plugin\'s row')
    # Leave Dokuen on its main page, where the session's Start and Stop are.
    for _ in range(2):
        device.press('back')
        time.sleep(1)
    device.press('home')


@step('1.3')
def device_online(run):
    # Android's Settings mark a connection that Android has not validated "No internet" or "Connected
    # without internet".
    if not run.network_validated():
        raise StepFailed('The device has no internet connection, which the audio sources need')
    run.check(True, 'Android has validated one of the device\'s connections as reaching the internet')


@step('2.1')
def run_setup(run):
    # The test helper, which setup runs, needs the UI that uiautomator2's agent holds.
    run.device.release_ui()
    run.args.clean = True
    problems = plan.setup_android(run.args)
    run.plugin_restarted = True
    for problem in problems:
        run.check(False, problem)
    run.check(not problems, 'setup found no problems')


@step('2.2')
def setup_complete(run):
    run.check(True, 'setup completed (2.1 records any problem it found)')


@step('2.3')
def serving(run):
    run.page.ensure_served()
    run.page.bring_to_front()
    run.check(True, 'the test page is served and open in Chrome on the device')
    run.ensure_configuration()
    run.note('configuration: %s' % run.configuration_line())


# ---------------------------------------------------------------------------------------------
# Phase 3: plugin defaults, AnkiDroid, and permissions


@step('3.1')
def remove_ankidroid(run):
    if run.dokuen.session_running():
        run.dokuen.stop_session()
    run.adb('shell', 'pm', 'revoke', plan.PACKAGE, ANKIDROID_PERMISSION)
    # Android also remembers that the permission was denied before: after two denials it stops
    # asking, and the plugin offers "Open settings" instead of 3.4's Grant permission. Clearing
    # that makes every run start as the first one does.
    run.adb('shell', 'pm', 'clear-permission-flags', plan.PACKAGE, ANKIDROID_PERMISSION, 'user-set', 'user-fixed')
    run.adb('shell', 'pm', 'uninstall', '-k', '--user', '0', ANKIDROID)
    run.check(not run.installed(ANKIDROID), 'AnkiDroid is uninstalled')
    run.check(not run.permission_granted(), 'the plugin does not hold the AnkiDroid permission')


@step('3.2')
def export_off_by_default(run):
    dashboard = run.dashboard
    dashboard.open()
    run.check(not dashboard.switch('Enable Anki export').checked, 'Enable Anki export is off')
    texts = dashboard.texts()
    shown = [label for label in ANKI_SETTING_LABELS if label in texts]
    run.check(not shown, 'nothing is shown below the switch', 'shown: %s' % shown)


def tap_enable(run):
    run.dashboard.open()
    run.device.tap(run.dashboard.switch('Enable Anki export'))
    time.sleep(1)


@step('3.3')
def install_ankidroid(run):
    device = run.device
    tap_enable(run)
    nodes = device.wait(lambda nodes: nodes if by_text(nodes, 'AnkiDroid required') else None, 5) or []
    run.check(by_text(nodes, 'AnkiDroid required'), 'the "AnkiDroid required" dialog appears')
    button = first(by_text(nodes, 'Install AnkiDroid'))
    if not run.check(button is not None, 'it has an Install AnkiDroid button'):
        return
    device.tap(button)

    # The Play Store may open as a sheet over the app that was shown before, so it is recognized
    # by its nodes rather than by the foreground app.
    def store_or_browser(nodes):
        if any(node.package == PLAY_STORE for node in nodes):
            return 'Play Store'
        url = first([node for node in nodes if node.rid == 'url_bar'])
        return 'browser' if url is not None and 'play.google.com' in url.text else None
    opened = device.wait(store_or_browser, 15)
    run.check(opened, 'the Play Store or a browser opens at AnkiDroid')
    if opened == 'Play Store':
        install = device.wait(lambda nodes: first([node for node in nodes if node.package == PLAY_STORE
                                                   and node.text == 'Install']), 20)
        if install is not None:
            device.tap(install)
            deadline = time.time() + 300
            while not run.installed(ANKIDROID) and time.time() < deadline:
                time.sleep(5)
    if not run.installed(ANKIDROID):
        # Only when the Play Store's screens were not as expected; an unattended run fails here.
        run.wait_for_tester('Install AnkiDroid from the Play Store')
    run.check(run.installed(ANKIDROID), 'AnkiDroid is installed')
    run.dashboard.open()


def answer_permission_prompt(run, allow, opener):
    """Calls opener, which shows the "Allow Anki access" dialog, taps Continue, and then Allow or
    Don't allow in Android's prompt, and checks that the permission is then granted or not. A tap
    while a dialog still animates in can be lost, so the whole sequence is tried twice."""
    device = run.device
    pattern = r'^Allow$' if allow else r'^Don.t allow$'

    def prompt_button(nodes):
        return first([node for node in nodes if re.match(pattern, node.text)])
    prompted = False
    for attempt in range(2):
        opener()
        dialog = device.wait(lambda nodes: first(by_text(nodes, 'Allow Anki access')), 5)
        if attempt == 0:
            run.check(dialog is not None, 'the "Allow Anki access" dialog appears')
        time.sleep(1)
        device.tap(device.wait(lambda nodes: first(by_text(nodes, 'Continue')), 5) or (0, 0))
        if device.wait(prompt_button, 15) is None:
            run.note('no permission prompt after Continue; trying again')
            continue
        prompted = True
        # The prompt slides in; tap its button once it has settled, and again if the tap was lost.
        time.sleep(1)
        for _ in range(2):
            button = prompt_button(device.nodes())
            if button is None:
                break
            device.tap(button)
            time.sleep(1.5)
        if run.permission_granted() == allow:
            run.check(True, 'Android\'s permission prompt appears')
            return
        run.note('the permission is %s after answering the prompt; trying again' % (
            'still not granted' if allow else 'granted'))
    run.check(prompted, 'Android\'s permission prompt appears')
    raise StepFailed('The permission is %s after answering the prompt' % ('not granted' if allow else 'granted'))


def tap_grant(run):
    run.dashboard.open()
    run.device.tap(run.dashboard.find(lambda nodes: first(by_text(nodes, 'Grant permission')), 'Grant permission'))
    time.sleep(1)


@step('3.4')
def deny_permission(run):
    answer_permission_prompt(run, False, lambda: tap_enable(run))
    dashboard = run.dashboard
    dashboard.open()
    run.check(not dashboard.switch('Enable Anki export').checked, 'Enable Anki export stays off')
    run.check(dashboard.has(lambda nodes: first(by_text(nodes, 'Anki export needs AnkiDroid access to add cards.'))),
              'the row "Anki export needs AnkiDroid access to add cards." appears')
    run.check(dashboard.has(lambda nodes: first(by_text(nodes, 'Grant permission'))),
              'it has a Grant permission button')


def grant_permission(run):
    answer_permission_prompt(run, True, lambda: tap_grant(run))
    run.dashboard.open()


@step('3.5')
def allow_permission(run):
    grant_permission(run)
    run.check(run.dashboard.switch('Enable Anki export').checked, 'Enable Anki export turns on')


@step('3.6')
def revoke_permission(run):
    # Revoking it in Android's Settings and with pm revoke do the same: the permission goes, and
    # Android stops the plugin's process.
    run.adb('shell', 'pm', 'revoke', plan.PACKAGE, ANKIDROID_PERMISSION)
    run.note('the permission was revoked with "pm revoke", which is what Android\'s Settings does')
    dashboard = run.dashboard
    dashboard.open()
    run.check(dashboard.switch('Enable Anki export').checked, 'Enable Anki export is still on')
    run.check(dashboard.has(lambda nodes: first(by_text(nodes, 'Permission needed'))), 'a "Permission needed" row is shown')
    run.check(dashboard.has(lambda nodes: first(by_text(nodes, 'Grant permission'))), 'it has a Grant permission button')


@step('3.7')
def regrant_permission(run):
    grant_permission(run)
    dashboard = run.dashboard
    run.check(dashboard.switch('Enable Anki export').checked, 'Enable Anki export is on')
    run.check(not dashboard.has(lambda nodes: first(by_text(nodes, 'Permission needed'))
                                or first(by_text(nodes, 'Grant permission'))), 'no permission row is shown')


@step('3.8')
def default_settings(run):
    dashboard = run.dashboard
    dashboard.open()
    run.expect(dashboard.field_value('Card tags'), 'dokuen-yomitan', 'Card tags')
    run.expect(dashboard.switch('Check for card duplicates').checked, True, 'Check for card duplicates')
    run.expect(dashboard.switch('Check for duplicates across all models').checked, False,
               'Check for duplicates across all models')
    run.expect(dashboard.field_value('Duplicate card scope'), 'Collection', 'Duplicate card scope')
    run.expect(dashboard.field_value('When a duplicate is detected'), 'Allow adding', 'When a duplicate is detected')
    names = dashboard.format_names()
    run.expect(names, ['Expression', 'Reading', 'Kanji'], 'the card formats')
    for name, entry_type, icon in (('Expression', 'Term', 'Big Circle'), ('Reading', 'Term', 'Small Circle'),
                                   ('Kanji', 'Kanji', 'Big Circle')):
        if name not in names:
            continue
        dashboard.select_format(name)
        run.check(dashboard.type_selected(entry_type), '%s has type %s' % (name, entry_type))
        run.expect(dashboard.field_value('Button'), icon, '%s\'s icon' % name)
        run.expect(dashboard.field_value('Deck'), '', '%s\'s deck' % name)
        run.expect(dashboard.field_value('Model'), '', '%s\'s note type' % name)
    check_audio_defaults(run)


def check_no_audio_sources(run):
    """Checks that the Audio card lists no sources and, as desktop's empty list does, shows no text
    in their place."""
    dashboard = run.dashboard
    run.expect(dashboard.audio_sources(), [], 'the audio sources')
    run.check(not dashboard.audio_shows(lambda nodes: first(by_text(dashboard.mine(nodes), AUDIO_EMPTY))),
              'the Audio card does not read "%s"' % AUDIO_EMPTY)


def check_audio_defaults(run):
    """Checks that the Audio card shows its defaults, in its order: the default sources on, no
    sources, and no idle download timeout."""
    dashboard = run.dashboard
    run.expect(dashboard.audio_switch().checked, True, AUDIO_DEFAULT_SOURCES)
    check_no_audio_sources(run)
    dashboard.audio_find(dashboard.field_match(AUDIO_TIMEOUT), 'field %s' % AUDIO_TIMEOUT)
    run.expect(dashboard.field_value(AUDIO_TIMEOUT), '0', AUDIO_TIMEOUT)


@step('3.9')
def no_tags_and_flags(run):
    run.dashboard.open()
    found = [text for text in run.dashboard.texts() if 'flags' in text.lower()]
    run.check(not found, 'nothing like "Show card tags and flags" is in the Anki Export tab', found)


# ---------------------------------------------------------------------------------------------
# Phase 4: plugin card formats


def build_format(run, tab, name, entry_type, icon, deck, note_type, changes, check_defaults):
    """Builds a card format in the editor as Phase 4 does, the note type last. tab is the format
    to edit, or None to add one with the tab strip's +."""
    dashboard = run.dashboard
    dashboard.open()
    if tab is None:
        message = dashboard.add_format()
        run.check(message is None, 'a format is added', message)
    else:
        dashboard.select_format(tab)
    dashboard.set_text('Name', name)
    dashboard.set_type(entry_type)
    dashboard.choose('Button', icon)
    dashboard.choose('Deck', deck)
    dashboard.choose('Model', note_type)
    names = plan.TERM_FIELDS if note_type == 'AuditTerm' else plan.KANJI_FIELDS
    dashboard.wait_fields_loaded(names[0])
    if check_defaults:
        fields = dashboard.editor_fields(names)
        run.expect(len(fields), len(names), 'the number of field rows')
        run.expect(list(fields), names, 'the field rows, in order')
        expected = plan.field_values(note_type, {})
        expected['single-frequency-number'] = ''
        wrong = {field: value for field, (value, _) in fields.items() if value != expected.get(field)}
        run.check(not wrong, 'every field shows its own marker (single-frequency-number empty)', wrong)
        for field in wrong:
            dashboard.set_text(field, expected[field])
    for field, value in changes:
        dashboard.set_text(field, value)


@step('4.1')
def build_audit_vocab(run):
    build_format(run, 'Expression', 'AuditVocab', 'Term', 'Big Circle', 'AuditTest', 'AuditTerm',
                 [('single-frequency-number', '{single-frequency-number}')], True)


@step('4.2')
def build_audit_kanji(run):
    build_format(run, 'Reading', 'AuditKanjiFmt', 'Kanji', 'Diamond', 'AuditTest', 'AuditKanji', [], True)


@step('4.3')
def build_audit_sub(run):
    build_format(run, 'Kanji', 'AuditSub', 'Term', 'Square', 'AuditTest::Sub', 'AuditTerm',
                 [('single-frequency-number', '{single-frequency-number}')], False)


@step('4.4')
def build_audit_quote_key(run):
    build_format(run, None, 'AuditQuoteKey', 'Term', 'Small Circle', 'AuditTest', 'AuditTerm',
                 [('Expression', '{expression}"'), ('single-frequency-number', '{single-frequency-number}')], False)


@step('4.5')
def build_audit_bad_marker(run):
    build_format(run, None, 'AuditBadMarker', 'Term', 'Square', 'AuditTest', 'AuditTerm',
                 [('Reading', '{expresion}'), ('single-frequency-number', '{single-frequency-number}')], False)


# ---------------------------------------------------------------------------------------------
# Phase 5: settings


def anki_texts(run):
    run.dashboard.open()
    return run.dashboard.all_texts()


def check_absent(run, patterns, what):
    found = [text for text in anki_texts(run) if any(re.search(pattern, text, re.I) for pattern in patterns)]
    run.check(not found, 'the plugin has no %s' % what, found)


@step('5.1')
def compare_enable(run):
    run.dashboard.open()
    run.check(run.dashboard.switch('Enable Anki export').checked, 'the plugin\'s Enable switch is on')


@step('5.2')
def compare_ankiconnect(run):
    check_absent(run, [r'AnkiConnect', r'API key', r'server address'], 'AnkiConnect server address or API key')


@step('5.3')
def compare_tags_label(run):
    run.dashboard.open()
    run.dashboard.field('Card tags')
    run.check(True, 'the plugin labels a text field "Card tags"')


@step('5.4')
def compare_duplicates_label(run):
    run.dashboard.open()
    run.dashboard.switch('Check for card duplicates')
    run.check(True, 'the plugin has a "Check for card duplicates" switch')


@step('5.5')
def compare_all_models_label(run):
    run.dashboard.open()
    run.dashboard.switch('Check for duplicates across all models')
    run.check(True, 'the plugin has a "Check for duplicates across all models" switch')


@step('5.6')
def compare_scope_options(run):
    run.dashboard.open()
    run.expect(run.dashboard.menu_options('Duplicate card scope'), ['Collection', 'Deck', 'Deck root'],
               'the scope options')


@step('5.7')
def compare_behavior_options(run):
    run.dashboard.open()
    run.expect(run.dashboard.menu_options('When a duplicate is detected'),
               ['Prevent adding', 'Allow overwriting', 'Allow adding'], 'the duplicate behavior options')


@step('5.8')
def compare_screenshot_format(run):
    dashboard = run.dashboard
    dashboard.open()
    run.expect(dashboard.menu_options('Image format'), ['PNG', 'JPEG'], 'the Image format options')

    def quality_shown():
        return dashboard.has(lambda nodes: first([node for node in dashboard.mine(nodes)
                                                  if node.text.startswith('JPEG quality')]))
    dashboard.choose('Image format', 'JPEG')
    run.check(quality_shown(), 'a JPEG quality slider is shown while JPEG is chosen')
    dashboard.choose('Image format', 'PNG')
    run.check(not quality_shown(), 'no JPEG quality slider is shown while PNG is chosen')


@step('5.9')
def compare_timeout(run):
    dashboard = run.dashboard
    dashboard.open()
    run.check(dashboard.audio_shows(dashboard.field_match(AUDIO_TIMEOUT)),
              'the Audio card has a field labeled "%s"' % AUDIO_TIMEOUT)
    run.check(dashboard.audio_shows(lambda nodes: first(by_text(dashboard.mine(nodes), AUDIO_TIMEOUT_DESCRIPTION))),
              'the field\'s description reads "%s"' % AUDIO_TIMEOUT_DESCRIPTION)


@step('5.10')
def compare_suspend(run):
    check_absent(run, [r'suspend'], 'Suspend new cards')


@step('5.11')
def compare_note_viewer(run):
    check_absent(run, [r'note viewer', r'\bbrowse\b', r'view added'], 'note viewer setting')


@step('5.12')
def compare_tags_and_flags(run):
    check_absent(run, [r'flags'], 'Show card tags and flags')


@step('5.13')
def compare_sync(run):
    check_absent(run, [r'\bsync'], 'Force Anki sync on adding card')


@step('5.14')
def compare_formats(run):
    texts = anki_texts(run)
    run.expect(run.dashboard.format_names(), FORMAT_NAMES, 'the card formats, in order')
    run.check('Save' not in texts and 'Cancel' not in texts, 'the editor has no Save or Cancel button')


@step('5.15')
def compare_templates(run):
    check_absent(run, [r'template'], 'Configure Anki card templates')


DUPLICATE_CONTROLS = ['Check for duplicates across all models', 'Duplicate card scope', 'When a duplicate is detected']


@step('5.16')
def toggle_duplicates(run):
    dashboard = run.dashboard
    dashboard.open()
    dashboard.set_switch('Check for card duplicates', False)
    shown = [label for label in DUPLICATE_CONTROLS if label in dashboard.texts()]
    run.check(not shown, 'the scope and behavior controls disappear', shown)
    dashboard.set_switch('Check for card duplicates', True)
    missing = [label for label in DUPLICATE_CONTROLS if label not in dashboard.texts()]
    run.check(not missing, 'they reappear', missing)


@step('5.17')
def all_models_persists(run):
    dashboard = run.dashboard
    dashboard.open()
    dashboard.set_switch('Check for duplicates across all models', True)
    run.device.press('home')
    time.sleep(1.5)
    dashboard.open()
    run.check(dashboard.switch('Check for duplicates across all models').checked, 'it is still on after reopening')
    dashboard.set_switch('Check for duplicates across all models', False)


@step('5.18')
def overwrite_mode_options(run):
    dashboard = run.dashboard
    run.set_duplicate_settings(behavior='Allow overwriting')
    dashboard.select_format('AuditVocab')
    run.expect(dashboard.overwrite_options('Expression'), OVERWRITE_MODES, 'the Expression field\'s overwrite modes')


@step('5.19')
def overwrite_glossary_append(run):
    dashboard = run.dashboard
    dashboard.open()
    dashboard.select_format('AuditVocab')
    dashboard.set_overwrite_mode('Glossary', 'Append')
    fields = dashboard.editor_fields(plan.TERM_FIELDS)
    run.expect(fields.get('Glossary', (None, None))[1], 'Append', 'Glossary\'s overwrite mode')
    others = {field: mode for field, (_, mode) in fields.items() if field != 'Glossary' and mode != 'Fill if empty'}
    run.check(not others, 'every other field\'s mode reads Fill if empty', others)


@step('5.20')
def back_to_allow_adding(run):
    dashboard = run.dashboard
    run.set_duplicate_settings(behavior='Allow adding')
    run.expect(dashboard.switch('Check for card duplicates').checked, True, 'Check for card duplicates')
    run.expect(dashboard.switch('Check for duplicates across all models').checked, False,
               'Check for duplicates across all models')
    run.expect(dashboard.field_value('Duplicate card scope'), 'Collection', 'Duplicate card scope')
    run.expect(dashboard.field_value('When a duplicate is detected'), 'Allow adding', 'When a duplicate is detected')


@step('5.21')
def sixth_format_refused(run):
    dashboard = run.dashboard
    dashboard.open()
    message = dashboard.add_format()
    run.check(message is not None and 'up to 5' in message, 'the plugin refuses with a message', message)
    run.expect(dashboard.format_names(), FORMAT_NAMES, 'the card formats')


# What the Audio card's add button adds, one source per tap, as desktop's does.
AUDIO_ADD_ORDER = ['JapanesePod101', 'Jisho.org', 'LanguagePod101']
# The Audio card's source types, which are desktop's without its two text-to-speech types.
AUDIO_SOURCE_TYPES = ['JapanesePod101', 'LanguagePod101', 'Jisho.org', '(Commons) Lingua Libre',
                      '(Commons) Wiktionary', 'Custom URL', 'Custom URL (JSON)']


@step('5.22')
def compare_audio_texts(run):
    dashboard = run.dashboard
    dashboard.open()
    run.check(dashboard.audio_shows(dashboard.audio_order_match),
              'the Audio card shows "%s" above the source order explanation, worded as in desktop: "%s"'
              % (AUDIO_DEFAULT_SOURCES, AUDIO_EXPLANATION))
    run.check(dashboard.audio_shows(dashboard.audio_switch_match),
              'its default sources switch is labeled "%s"' % AUDIO_DEFAULT_SOURCES)


@step('5.23')
def compare_audio_add(run):
    dashboard = run.dashboard
    dashboard.open()
    if dashboard.audio_sources():
        run.note('the Audio card listed sources before the step, so the step removed them first')
        dashboard.remove_audio_sources()
    run.check(dashboard.audio_shows(dashboard.audio_add_match), 'the Audio card\'s add button is labeled "%s"' % AUDIO_ADD)
    for _ in AUDIO_ADD_ORDER:
        dashboard.add_audio_source()
    run.expect(dashboard.audio_sources(), [(source_type, None) for source_type in AUDIO_ADD_ORDER],
               'the sources (type, URL), in order')


@step('5.24')
def compare_audio_types(run):
    dashboard = run.dashboard
    dashboard.open()
    dashboard.audio_find(dashboard.field_match('Source 1'), 'the Source 1 menu')
    run.expect(dashboard.menu_options('Source 1'), AUDIO_SOURCE_TYPES, 'the Source 1 menu\'s options')
    dashboard.audio_find(dashboard.field_match('Source 1'), 'the Source 1 menu')
    dashboard.choose('Source 1', 'Custom URL')
    run.check(dashboard.audio_shows(dashboard.audio_url_match(1)), 'a URL field appears below the Source 1 menu')
    dashboard.audio_find(dashboard.field_match('Source 1'), 'the Source 1 menu')
    dashboard.choose('Source 1', 'JapanesePod101')
    sources = dashboard.audio_sources()
    run.check(sources[:1] == [('JapanesePod101', None)], 'the URL field disappears', sources)


@step('5.25')
def compare_audio_remove(run):
    dashboard = run.dashboard
    dashboard.open()
    dashboard.remove_audio_sources()
    check_no_audio_sources(run)


# ---------------------------------------------------------------------------------------------
# Phase 6: add the notes


def simple_add(step_id, word, headword, reading, card_format='AuditVocab'):
    @step(step_id)
    def add(run):
        run.add(word, headword, reading, card_format)
    return add


simple_add('6.1', '食べる', '食べる', 'たべる')
simple_add('6.2', '動き出す', '動き出す', 'うごきだす')
simple_add('6.3', 'お見舞い', 'お見舞い', 'おみまい')
simple_add('6.4', '食べさせられた', '食べる', 'たべる')
simple_add('6.5', '牛', '牛', 'ぎゅう')


@step('6.6')
def add_kyoto(run):
    run.scan('京都')
    run.expect(run.entry_dictionaries('京都', 'きょうと'), [plan.JITENDEX, plan.JMNEDICT],
               'the dictionaries of 京都【きょうと】\'s definitions, in order')
    entry = run.dokuen.find_entry('京都', 'きょうと', 'AuditVocab')
    run.check_written(*run.dokuen.tap_link(entry, 'AuditVocab')[1:], card_format='AuditVocab')


simple_add('6.7', '学習辞典', '学習辞典', 'がくしゅうじてん')
simple_add('6.8', '時雨', '時雨', 'しぐれ')
simple_add('6.9', '鳥居', '鳥居', 'とりい')


@step('6.10')
def add_chidori(run):
    run.scan('千鳥', repair=True)
    entry = run.dokuen.find_entry('千鳥', 'ちどり', 'AuditVocab')
    run.check(True, 'the scan read 千鳥 (after Repair, if the OCR misread it), and the entry\'s reading is ちどり')
    run.check_written(*run.dokuen.tap_link(entry, 'AuditVocab')[1:], card_format='AuditVocab')


simple_add('6.11', '順路', '順路', 'じゅんろ')
simple_add('6.12', '記号', '記号', 'きごう')


# The entries the plan's 6.13 lists for 學校, in order: desktop's, from the reference capture
# (headword, reading; an entry with no separate reading shows its headword as its reading).
GAKKOU_ENTRIES = [
    ('学校', 'がっこう'),
    ('學校', '學校'),
    ('学', 'がく'),
    ('學', '學'),
    ('学', '学'),
]


@step('6.13')
def add_gakkou(run):
    run.scan('學校', repair=True)
    run.expect(run.dokuen.all_entries(), GAKKOU_ENTRIES, 'the result\'s entries (headword, reading), in order')
    entry = run.dokuen.find_entry('學校', None, 'AuditVocab')
    run.check_written(*run.dokuen.tap_link(entry, 'AuditVocab')[1:], card_format='AuditVocab')


def add_kanji_from_headword(run, word, reading, kanji, line=None):
    run.scan(word, line)
    entry = run.dokuen.find_entry(word, reading)
    run.dokuen.tap_headword_character(entry, kanji)
    entry = run.dokuen.find_entry(kanji, None, 'AuditKanjiFmt')
    run.check_written(*run.dokuen.tap_link(entry, 'AuditKanjiFmt')[1:], card_format='AuditKanjiFmt')


@step('6.14')
def add_kanji_shoku(run):
    add_kanji_from_headword(run, '食べる', 'たべる', '食')


@step('6.15')
def add_from_paragraph(run):
    run.add('動き出す', '動き出す', 'うごきだす', 'AuditVocab', line=plan.TEST_PARAGRAPH)


@step('6.16')
def add_kanji_dou(run):
    # The same result as 6.15's, which is still shown.
    popup = run.dokuen.popup()
    if popup is None:
        run.scan('動き出す', plan.TEST_PARAGRAPH)
    entry = run.dokuen.find_entry('動き出す', 'うごきだす')
    run.dokuen.tap_headword_character(entry, '動')
    entry = run.dokuen.find_entry('動', None, 'AuditKanjiFmt')
    run.check_written(*run.dokuen.tap_link(entry, 'AuditKanjiFmt')[1:], card_format='AuditKanjiFmt')


@step('6.17')
def type_tags(run):
    dashboard = run.dashboard
    dashboard.open()
    dashboard.clear_focused('Card tags')
    run.device.type_text(TAGS_TYPED)
    time.sleep(0.5)
    run.expect(dashboard.field('Card tags').text, TAGS_TYPED, 'Card tags')


@step('6.18')
def type_more_tags(run):
    run.device.type_text(', ')
    time.sleep(0.5)
    run.expect(run.dashboard.field('Card tags').text, TAGS_TYPED + ', ',
               'Card tags, as typed (neither wiped nor rewritten)')


@step('6.19')
def tags_done(run):
    run.device.press('enter')
    time.sleep(1)
    run.dashboard.hide_keyboard()
    run.expect(run.dashboard.field('Card tags').text, TAGS_FORMATTED, 'Card tags after Done')


simple_add('6.20', '食べる', '食べる', 'たべる')


@step('6.21')
def clear_tags(run):
    dashboard = run.dashboard
    dashboard.open()
    dashboard.clear_focused('Card tags')
    run.device.press('enter')
    time.sleep(1)
    dashboard.hide_keyboard()
    run.expect(dashboard.field_value('Card tags'), '', 'Card tags')


@step('6.22')
def add_bad_marker(run):
    popup, label, status = run.add('食べる', '食べる', 'たべる', 'AuditBadMarker')
    run.expect_message(status, 'Note added to Anki, but card format "AuditBadMarker" uses unknown marker(s): {expresion}',
                       contains=True)
    run.check(status is not None and '{expresion}' in status,
              'the message names {expresion}, as desktop\'s does (the shapes match)', status)


def move_dictionary_above(run, title, other):
    dashboard = run.dashboard
    dashboard.open('Dictionaries')
    for _ in range(len(plan.DICTIONARIES)):
        order = dashboard.dictionary_order()
        if order.index(title) < order.index(other):
            return order
        dashboard.move_dictionary(title, 'up')
    return dashboard.dictionary_order()


@step('6.23')
def jmnedict_first(run):
    order = move_dictionary_above(run, plan.JMNEDICT, plan.JITENDEX)
    run.check(order.index(plan.JMNEDICT) < order.index(plan.JITENDEX), 'JMnedict is above Jitendex', order)
    # The plugin reads the dictionary order when a session starts.
    run.dokuen.restart_session()
    run.plugin_restarted = False


@step('6.24')
def add_kyoto_again(run):
    run.scan('京都')
    run.expect(run.entry_dictionaries('京都', 'きょうと'), [plan.JMNEDICT, plan.JITENDEX],
               'the dictionaries of 京都【きょうと】\'s definitions, in order')
    entry = run.dokuen.find_entry('京都', 'きょうと', 'AuditVocab')
    run.check_written(*run.dokuen.tap_link(entry, 'AuditVocab')[1:], card_format='AuditVocab')


@step('6.25')
def jitendex_first(run):
    order = move_dictionary_above(run, plan.JITENDEX, plan.JMNEDICT)
    run.expect(order, [title for title, _ in plan.DICTIONARIES], 'the dictionary order')
    run.dokuen.restart_session()
    run.plugin_restarted = False


AUDIO_TIMED_OUT = 'Note added to Anki, but {audio} is empty because its download timed out.'
AUDIO_UNREACHABLE = 'Note added to Anki, but {audio} is empty because no audio source could be reached.'
AUDIO_OFFLINE = 'Note added to Anki, but {audio} is empty because there is no network connection.'


def audio_add(run, word, headword, reading, outcome='Added'):
    """Adds headword【reading】 via AuditVocab, as run.add does, and notes each request the test
    page's server had no audio for. Returns the message shown."""
    mark = run.audio_mark()
    try:
        return run.add(word, headword, reading, 'AuditVocab', outcome=outcome)[2]
    finally:
        run.note_unexpected_audio(mark)


def remote_audio_add(step_id, label, source_type):
    """A step that adds AUDIO_WORDS[label] with source_type as the only audio source."""
    word, reading = plan.AUDIO_WORDS[label]

    @step(step_id)
    def add(run):
        with run.audio_settings([(source_type, None)]):
            audio_add(run, word, word, reading)
    return add


remote_audio_add('6.26', 'AU1', 'LanguagePod101')
remote_audio_add('6.27', 'AU2', 'Jisho.org')
remote_audio_add('6.28', 'AU3', '(Commons) Lingua Libre')


@step('6.29')
def add_wiktionary_audio(run):
    word, reading = plan.AUDIO_WORDS['AU4']
    with run.audio_settings([('(Commons) Wiktionary', None)]):
        status = audio_add(run, word, word, reading)
    run.check(not status or '{audio}' not in status, 'no message about {audio} is shown', status)


def local_audio_add(step_id, sources, word='食べる', reading='たべる', default_sources=False, timeout=0,
                    message=None):
    """A step that adds word【reading】 with sources, a list of (type label, key of plan.audio_urls,
    or None for a remote source), and checks the message, if one is given."""
    @step(step_id)
    def add(run):
        urls = plan.audio_urls(run.args.port)
        with run.audio_settings([(source_type, urls[key] if key else None) for source_type, key in sources],
                                default_sources, timeout):
            status = audio_add(run, word, word, reading)
        if message is not None:
            run.expect_message(status, message, contains=True)
    return add


local_audio_add('6.30', [('Custom URL', 'custom')])
local_audio_add('6.31', [('Custom URL (JSON)', 'json')])
local_audio_add('6.32', [('Custom URL', 'missing'), ('Jisho.org', None)], '京都', 'きょうと')
local_audio_add('6.33', [('Custom URL', 'missing')], default_sources=True)
local_audio_add('6.34', [('Custom URL', 'unknown_type')])
local_audio_add('6.35', [('Custom URL', 'stall')], timeout=2000, message=AUDIO_TIMED_OUT)
local_audio_add('6.36', [('Custom URL', 'closed')], message=AUDIO_UNREACHABLE)


@step('6.37')
def audio_defaults_again(run):
    dashboard = run.dashboard
    dashboard.open()
    dashboard.remove_audio_sources()
    dashboard.set_audio_switch(True)
    check_audio_defaults(run)


# ---------------------------------------------------------------------------------------------
# Phase 7: duplicate detection
#
# The plan has the tester search, add and delete notes in AnkiDroid. Here the test helper does
# that through AnkiDroid's API, which is what those steps set up and check; what is under test is
# the plugin's duplicate check.

ALREADY_EXISTS = 'This note already exists in Anki.'
PENCIL = '"Expression:鉛筆"'
PENCIL_ROOT_ONLY = '"Expression:鉛筆" deck:AuditTest -deck:AuditTest::*'


def tap_add(run, word, headword, reading, card_format='AuditVocab'):
    run.scan(word)
    entry = run.dokuen.find_entry(headword, reading, card_format)
    return run.dokuen.tap_link(entry, card_format)


def expect_duplicate(run, word, headword, reading, card_format='AuditVocab'):
    _, label, status = tap_add(run, word, headword, reading, card_format)
    run.expect_message(status, ALREADY_EXISTS, contains=True)


def seed_pencil(run, deck, glossary):
    run.anki.add('AuditTerm', deck, {'Expression': '鉛筆', 'Reading': 'えんぴつ', 'Glossary': glossary},
                 ['audit-fixture-dup-scope'])


@step('7.1')
def no_chair_or_pencil(run):
    run.expect(run.anki.count('"Expression:椅子" or "Expression:鉛筆"'), 0, 'the number of 椅子 and 鉛筆 notes')


@step('7.2')
def chair_added(run):
    run.set_duplicate_settings(behavior='Allow adding', scope='Collection', all_models=False)
    run.add('椅子', '椅子', 'いす', 'AuditVocab')


@step('7.3')
def chair_prevented(run):
    run.set_duplicate_settings(behavior='Prevent adding')
    expect_duplicate(run, '椅子', '椅子', 'いす')
    run.expect(run.anki.count('"Expression:椅子"'), 1, 'the number of 椅子 notes')


@step('7.4')
def chair_overwritten(run):
    run.set_duplicate_settings(behavior='Allow overwriting')
    _, label, status = tap_add(run, '椅子', '椅子', 'いす')
    run.check_written(label, status, 'AuditVocab', ('Added', 'Overwritten'))


@step('7.5')
def chair_added_again(run):
    run.set_duplicate_settings(behavior='Allow adding')
    _, label, status = tap_add(run, '椅子', '椅子', 'いす')
    run.check_written(label, status, 'AuditVocab')
    run.expect(run.anki.count('"Expression:椅子"'), 2, 'the number of 椅子 notes')


@step('7.6')
def seed_deck(run):
    seed_pencil(run, 'AuditTest', 'seed-deck')
    run.note('Seed-Deck was added through AnkiDroid\'s API rather than its editor')
    run.expect(run.anki.count(PENCIL_ROOT_ONLY), 1, 'the number of 鉛筆 notes in AuditTest itself')


@step('7.7')
def seed_sub(run):
    seed_pencil(run, 'AuditTest::Sub', 'seed-sub')
    run.expect(run.anki.count(PENCIL), 2, 'the number of 鉛筆 notes')
    run.expect(run.anki.count(PENCIL_ROOT_ONLY), 1, 'the number of them in AuditTest itself')
    run.expect(run.anki.count(PENCIL + ' deck:AuditTest::Sub'), 1, 'the number of them in AuditTest::Sub')


@step('7.8')
def pencil_collection(run):
    run.set_duplicate_settings(behavior='Prevent adding', scope='Collection')
    expect_duplicate(run, '鉛筆', '鉛筆', 'えんぴつ')


@step('7.9')
def pencil_deck(run):
    run.set_duplicate_settings(scope='Deck')
    expect_duplicate(run, '鉛筆', '鉛筆', 'えんぴつ')


@step('7.10')
def delete_seed_deck(run):
    run.anki.delete(PENCIL_ROOT_ONLY)
    run.expect(run.anki.count(PENCIL), 1, 'the number of 鉛筆 notes left (Seed-Sub)')


@step('7.11')
def pencil_deck_excludes_sub(run):
    run.set_duplicate_settings(scope='Deck')
    _, label, status = tap_add(run, '鉛筆', '鉛筆', 'えんぴつ')
    run.check_written(label, status, 'AuditVocab')


@step('7.12')
def delete_added_pencil(run):
    run.anki.delete(PENCIL_ROOT_ONLY)
    run.expect(run.anki.count(PENCIL), 1, 'the number of 鉛筆 notes left (Seed-Sub)')


@step('7.13')
def pencil_deck_root(run):
    run.set_duplicate_settings(scope='Deck root')
    expect_duplicate(run, '鉛筆', '鉛筆', 'えんぴつ')


@step('7.14')
def reseed_deck(run):
    run.anki.delete(PENCIL)
    seed_pencil(run, 'AuditTest', 'seed-deck')
    run.expect(run.anki.count(PENCIL_ROOT_ONLY), 1, 'the number of 鉛筆 notes in AuditTest itself')
    run.expect(run.anki.count(PENCIL), 1, 'the number of 鉛筆 notes')


@step('7.15')
def pencil_deck_root_from_sub(run):
    run.set_duplicate_settings(scope='Deck root')
    expect_duplicate(run, '鉛筆', '鉛筆', 'えんぴつ', 'AuditSub')


@step('7.16')
def pencil_deck_from_sub(run):
    run.set_duplicate_settings(scope='Deck')
    _, label, status = tap_add(run, '鉛筆', '鉛筆', 'えんぴつ', 'AuditSub')
    run.check_written(label, status, 'AuditSub')
    run.expect(run.anki.count(PENCIL + ' deck:AuditTest::Sub'), 1, 'the number of 鉛筆 notes in AuditTest::Sub')


@step('7.17')
def kanji_pencil(run):
    run.anki.delete(PENCIL)
    run.anki.add('AuditKanji', 'AuditTest', {'Character': '鉛筆', 'Kunyomi': 'n/a', 'Onyomi': 'n/a', 'Glossary': 'n/a'})
    run.expect(run.anki.count('"Character:鉛筆"'), 1, 'the number of AuditKanji 鉛筆 notes')
    run.expect(run.anki.count(PENCIL), 0, 'the number of AuditTerm 鉛筆 notes')


@step('7.18')
def other_note_type_not_checked(run):
    run.set_duplicate_settings(scope='Collection', all_models=False)
    _, label, status = tap_add(run, '鉛筆', '鉛筆', 'えんぴつ')
    run.check_written(label, status, 'AuditVocab')


@step('7.19')
def all_models_checked(run):
    run.anki.delete(PENCIL)
    run.set_duplicate_settings(all_models=True)
    expect_duplicate(run, '鉛筆', '鉛筆', 'えんぴつ')


@step('7.20')
def quote_key_added(run):
    run.set_duplicate_settings(behavior='Allow adding', all_models=False)
    _, label, status = tap_add(run, '手紙', '手紙', 'てがみ', 'AuditQuoteKey')
    run.check_written(label, status, 'AuditQuoteKey')


@step('7.21')
def quote_key_duplicate(run):
    run.set_duplicate_settings(behavior='Prevent adding')
    expect_duplicate(run, '手紙', '手紙', 'てがみ', 'AuditQuoteKey')
    run.expect(run.anki.count('手紙'), 1, 'the number of notes 手紙 finds')


@step('7.22')
def duplicates_back_to_defaults(run):
    run.set_duplicate_settings(behavior='Allow adding', scope='Collection', all_models=False)
    dashboard = run.dashboard
    run.expect(dashboard.field_value('When a duplicate is detected'), 'Allow adding', 'When a duplicate is detected')
    run.expect(dashboard.field_value('Duplicate card scope'), 'Collection', 'Duplicate card scope')
    run.expect(dashboard.switch('Check for duplicates across all models').checked, False,
               'Check for duplicates across all models')


# ---------------------------------------------------------------------------------------------
# Phase 8: check script


@step('8.1')
def run_check_script(run):
    # The check runs the test helper, which needs the UI that uiautomator2's agent holds.
    run.device.release_ui()
    run.args.reference = plan.REFERENCE_FILE
    folder = os.path.join(run.args.out, '%s-check' % datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
    os.makedirs(folder, exist_ok=True)
    result = plan.command_check(run.args, folder)
    run.plugin_restarted = True
    run.state['check_folder'] = folder
    run.note('the check script\'s report: %s' % os.path.abspath(os.path.join(folder, 'report.md')))
    run.check(result == 0, 'the check script reports no FAIL or MISSING (8.2 reviews any that it does)')


@step('8.2')
def queue_check_findings(run):
    folder = run.state.get('check_folder')
    if folder is None:
        raise StepFailed('8.1 did not run in this run; review its report by hand')
    with open(os.path.join(folder, 'report.json'), encoding='utf-8') as source:
        results = json.load(source)['results']
    findings = [item for item in results if item['status'] in ('FAIL', 'REVIEW', 'MISSING')]
    for item in findings:
        run.defer('%s %s %s: %s. Does R9 of the plan list this as a known exception?' % (
            item['step'], item['status'], item['subject'], item['message']),
            expected='desktop: %s | plugin: %s' % (plan.shorten(item['desktop'] or ''), plan.shorten(item['plugin'] or '')))
    run.check(True, '%d check results to review' % len(findings))


@step('8.3')
def new_session(run):
    run.dokuen.restart_session()
    run.plugin_restarted = False
    run.check(run.dokuen.session_running(), 'Dokuen\'s session runs again')


# ---------------------------------------------------------------------------------------------
# Phase 9: icons and link states

TERM_FORMATS = ['AuditVocab', 'AuditSub', 'AuditQuoteKey', 'AuditBadMarker']
GLYPHS = {'AuditVocab': '●', 'AuditSub': '■', 'AuditQuoteKey': '◉', 'AuditBadMarker': '■', 'AuditKanjiFmt': '◆'}


@step('9.1')
def overwrite_for_icons(run):
    run.set_duplicate_settings(behavior='Allow overwriting')
    run.expect(run.dashboard.field_value('When a duplicate is detected'), 'Allow overwriting',
               'When a duplicate is detected')


@step('9.2')
def plugin_icons(run):
    run.scan('猫')
    entry = run.dokuen.find_entry('猫', 'ねこ', 'AuditBadMarker')
    image = run.shot('neko')
    run.check_links(entry, {name: ('Add', 'green', GLYPHS[name]) for name in TERM_FORMATS}, image)
    run.scan('犬')
    entry = run.dokuen.find_entry('犬', 'いぬ')
    run.dokuen.tap_headword_character(entry, '犬')
    entry = run.dokuen.find_entry('犬', None, 'AuditKanjiFmt')
    image = run.shot('inu-kanji')
    run.check_links(entry, {'AuditKanjiFmt': ('Add', 'green', GLYPHS['AuditKanjiFmt'])}, image)


def taberu_links(run, card_format='AuditBadMarker'):
    entry = run.dokuen.find_entry('食べる', 'たべる', card_format)
    return entry, run.shot('taberu')


@step('9.3')
def links_turn_orange(run):
    run.scan('食べる')
    entry, image = taberu_links(run)
    run.check_links(entry, {name: ('Add', 'green', None) for name in TERM_FORMATS}, image)
    _, label, status = run.dokuen.tap_link(run.dokuen.find_entry('食べる', 'たべる', 'AuditQuoteKey'), 'AuditQuoteKey')
    run.check_written(label, status, 'AuditQuoteKey')
    entry, image = taberu_links(run)
    expected = {name: ('Add', 'orange', None) for name in TERM_FORMATS}
    expected['AuditQuoteKey'] = ('Added', 'orange', None)
    run.check_links(entry, expected, image)


@step('9.4')
def second_tap_overwrites(run):
    entry = run.dokuen.find_entry('食べる', 'たべる', 'AuditQuoteKey')
    _, label, status = run.dokuen.tap_link(entry, 'AuditQuoteKey')
    run.check_written(label, status, 'AuditQuoteKey', 'Overwritten')
    entry, image = taberu_links(run, 'AuditQuoteKey')
    run.check_links(entry, {'AuditQuoteKey': ('Overwritten', 'orange', None)}, image)


@step('9.5')
def links_turn_purple(run):
    run.set_duplicate_settings(behavior='Allow adding')
    run.scan('食べる')
    entry = run.dokuen.find_entry('食べる', 'たべる', 'AuditVocab')
    _, label, status = run.dokuen.tap_link(entry, 'AuditVocab')
    run.check_written(label, status, 'AuditVocab')
    entry, image = taberu_links(run)
    expected = {name: ('Add', 'purple', None) for name in TERM_FORMATS}
    expected['AuditVocab'] = ('Added', 'purple', None)
    run.check_links(entry, expected, image)


@step('9.6')
def no_view_link(run):
    popup = run.dokuen.popup() or run.scan('食べる')
    found = [node.text for node in popup.texts if re.search(r'\b(View in Anki|Edit note|View note)\b', node.text)
             or node.text in ('View', 'Edit')]
    run.check(not found, 'there is no link to view a note in AnkiDroid', found)


# ---------------------------------------------------------------------------------------------
# Phase 10: errors and edge cases

EXPIRED = 'This Anki link has expired. Please look up the word again.'


@step('10.1')
def back_keeps_links(run):
    run.scan('食べる')
    entry = run.dokuen.find_entry('食べる', 'たべる')
    run.dokuen.tap_headword_character(entry, '食')
    run.dokuen.back_to('食べる', 'たべる')
    entry = run.dokuen.find_entry('食べる', 'たべる', 'AuditVocab')
    _, label, status = run.dokuen.tap_link(entry, 'AuditVocab')
    run.check_written(label, status, 'AuditVocab')


@step('10.2')
def large_result(run):
    run.scan('かける')
    # Counting the entries on screen would mean scrolling through dozens of screens.
    size = run.dokuen.result_size()
    if size is None:
        size = len(run.dokuen.all_entries())
    run.check(size > 10, 'the result has more than 10 entries', '%d entries' % size)
    for which in ('first', 'last'):
        link = run.dokuen.edge_link('AuditVocab', last=(which == 'last'))
        _, label, status = run.dokuen.tap_link(Entry('', '', [], [link]), 'AuditVocab')
        run.check(status is None or 'expired' not in status, 'the %s entry\'s link has not expired' % which, status)
        run.check_written(label, status, 'AuditVocab')


@step('10.3')
def links_expire(run):
    before = run.anki.count('"Expression:食べる"')
    run.scan('食べる')
    for _ in range(10):
        entry = run.dokuen.find_entry('食べる', 'たべる')
        run.dokuen.tap_headword_character(entry, '食')
        run.dokuen.back_to('食べる', 'たべる')
    entry = run.dokuen.find_entry('食べる', 'たべる', 'AuditVocab')
    _, label, status = run.dokuen.tap_link(entry, 'AuditVocab')
    run.expect_message(status, EXPIRED)
    run.expect(run.anki.count('"Expression:食べる"'), before, 'the number of 食べる notes (none added)')


def delete_sub_deck(run):
    """Deletes AuditTest::Sub in AnkiDroid's deck list, as a person would (AnkiDroid's API cannot
    delete decks): long-press it, Delete deck."""
    device = run.device
    run.dokuen.hide_overlay()
    device.start_app(ANKIDROID)

    def sub_deck(nodes):
        decks = [node for node in nodes if node.package == ANKIDROID and node.rid == 'deck_name']
        parent = first([node for node in decks if node.text == 'AuditTest'])
        if parent is None:
            return None
        # Sub is the deck listed, indented, right below AuditTest.
        below = sorted([node for node in decks if node.top >= parent.bottom - 5], key=lambda node: node.top)
        if below and below[0].text == 'Sub' and below[0].left > parent.left:
            return below[0]
        expander = first([node for node in nodes if node.package == ANKIDROID and node.desc == 'Expand'
                          and abs(node.center[1] - parent.center[1]) < 30])
        if expander is not None:
            device.tap(expander)
        return None
    sub = device.wait(sub_deck, 15)
    if sub is None:
        raise StepFailed('AnkiDroid\'s deck list shows no AuditTest::Sub')
    device.long_press(sub)
    menu = device.wait(lambda nodes: first(by_text(nodes, 'Delete deck')), 5)
    if menu is None or not device.wait(lambda nodes: first(by_text(nodes, 'AuditTest::Sub')), 2):
        device.press('back')
        raise StepFailed('Long-pressing AuditTest::Sub in AnkiDroid offered no Delete deck')
    device.tap(menu)
    deleted = device.wait(lambda nodes: first([node for node in nodes if 'deleted from' in node.text]), 10)
    run.check(deleted is not None, 'AnkiDroid deleted the deck AuditTest::Sub')


@step('10.4')
def missing_deck(run):
    delete_sub_deck(run)
    _, label, status = tap_add(run, '手紙', '手紙', 'てがみ', 'AuditSub')
    run.check(status is not None and 'AuditTest::Sub' in status and 'AuditSub' in status,
              'a message says that AuditSub\'s deck AuditTest::Sub no longer exists', status)
    run.expect(run.anki.count('手紙'), 1, 'the number of notes 手紙 finds (the one from 7.20)')


@step('10.5')
def recreate_deck(run):
    run.anki.create_decks()
    dashboard = run.dashboard
    dashboard.open()
    dashboard.select_format('AuditSub')
    dashboard.choose('Deck', 'AuditTest::Sub')


@step('10.6')
def offline_audio(run):
    with run.network_off():
        status = audio_add(run, '食べる', '食べる', 'たべる')
        run.expect_message(status, AUDIO_OFFLINE, contains=True)


# How long, in seconds, the plugin waits for an audio lookup that makes no progress, and how much
# longer than that 10.7's add may take, the write included.
AUDIO_LOOKUP_TIMEOUT = 30
AUDIO_LOOKUP_SLACK = 20


@step('10.7')
def audio_lookup_timeout(run):
    url = plan.audio_urls(run.args.port)['json_stall']
    with run.audio_settings([('Custom URL (JSON)', url)]):
        mark = run.audio_mark()
        try:
            run.scan('食べる')
            entry = run.dokuen.find_entry('食べる', 'たべる', 'AuditVocab')
            started = time.time()
            _, label, status = run.dokuen.tap_link(entry, 'AuditVocab',
                                                   timeout=AUDIO_LOOKUP_TIMEOUT + AUDIO_LOOKUP_SLACK)
            seconds = time.time() - started
        finally:
            run.note_unexpected_audio(mark)
        run.check_written(label, status, 'AuditVocab')
        run.check(AUDIO_LOOKUP_TIMEOUT - 5 <= seconds <= AUDIO_LOOKUP_TIMEOUT + AUDIO_LOOKUP_SLACK,
                  'the add finished after about %d seconds' % AUDIO_LOOKUP_TIMEOUT, '%.1f seconds' % seconds)
        run.expect_message(status, AUDIO_UNREACHABLE, contains=True)


def custom_audio_requests(run, mark):
    return [request for request in run.audio_requests_since(mark) if request['path'].startswith('/audio/custom/')]


@step('10.8')
def overwrite_skips_audio(run):
    dashboard = run.dashboard

    def overwrite_defaults():
        run.set_duplicate_settings(behavior='Allow overwriting')
        dashboard.select_format('AuditVocab')
        dashboard.set_overwrite_mode('audio', 'Fill if empty')
        run.set_duplicate_settings(behavior='Allow adding')
    # This fails before anything is changed when this run cannot read the server's log.
    run.audio_requests_since(run.audio_mark())
    with run.audio_settings([('Custom URL', plan.audio_urls(run.args.port)['custom'])]):
        try:
            run.set_duplicate_settings(behavior='Allow overwriting')
            dashboard.select_format('AuditVocab')
            dashboard.set_overwrite_mode('audio', 'Skip')
            mark = run.audio_mark()
            audio_add(run, '食べる', '食べる', 'たべる', 'Overwritten')
            run.expect(len(custom_audio_requests(run, mark)), 0,
                       'the number of requests under /audio/custom/ during the add with Skip')
            dashboard.open()
            dashboard.select_format('AuditVocab')
            dashboard.set_overwrite_mode('audio', 'Overwrite')
            mark = run.audio_mark()
            audio_add(run, '食べる', '食べる', 'たべる', 'Overwritten')
            run.expect(len(custom_audio_requests(run, mark)), 1,
                       'the number of requests under /audio/custom/ during the add with Overwrite')
        finally:
            run.restore('the audio field\'s overwrite mode (Fill if empty) and Allow adding', overwrite_defaults)


# ---------------------------------------------------------------------------------------------
# Phases 11 to 13: images and clipboard text

CLIPBOARD_TEXT = 'anki test plan clipboard text'
# Where the image that 13.5 chooses in the crop UI is put, for the length of that step.
PICKER_IMAGE_PATH = '/sdcard/Pictures/anki-test-plan-image.png'
CLIPBOARD_LINES = '<b>bold</b>\nsecond line'
NEKO_QUERY = '"Expression:猫"'


def add_neko(run):
    run.scan('猫')
    entry = run.dokuen.find_entry('猫', 'ねこ', 'AuditVocab')
    return run.dokuen.tap_link(entry, 'AuditVocab')


def image_file(value):
    match = re.search(r'<img src="([^"]+)"', value or '')
    return match.group(1) if match else None


@step('11.1')
def no_images_on_old_host(run):
    set_screenshot_behavior(run, 'Full screen')
    run.set_duplicate_settings(behavior='Allow adding')
    run.device.u2.set_clipboard(CLIPBOARD_TEXT)
    _, label, status = add_neko(run)
    run.check(run.dokuen.crop_ui() is None, 'no Dokuen UI appears')
    run.check(label is not None and label.endswith('Added: AuditVocab'), 'the link reads "Added: AuditVocab"', label)
    run.check(not status, 'no message is shown', status)
    note = run.anki.newest(NEKO_QUERY)
    for field in ('screenshot', 'clipboard-image', 'clipboard-text'):
        run.expect(note.get(field), '', 'the %s field' % field)


@step('11.2')
def no_crop_ui_on_old_host(run):
    set_screenshot_behavior(run, 'Show crop UI')
    _, label, status = add_neko(run)
    run.check(run.dokuen.crop_ui() is None, 'no crop UI appears')
    run.check(label is not None and label.endswith('Added: AuditVocab'), 'the link reads "Added: AuditVocab"', label)
    set_screenshot_behavior(run, 'Full screen')


@step('13.1')
def clear_neko_notes(run):
    deleted = run.anki.delete(NEKO_QUERY)
    run.note('deleted %d 猫 notes' % deleted)
    run.expect(run.anki.count(NEKO_QUERY), 0, 'the number of 猫 notes')


@step('13.3')
def full_screen_screenshot(run):
    dashboard = run.dashboard
    dashboard.open()
    run.expect(dashboard.field_value('{screenshot} behavior'), 'Full screen', '{screenshot} behavior')
    run.expect(dashboard.field_value('Image format'), 'PNG', 'Image format')
    run.check(not dashboard.has(lambda nodes: first([node for node in nodes if node.text.startswith('JPEG quality')])),
              'there is no JPEG quality slider')
    run.device.u2.set_clipboard(CLIPBOARD_TEXT)
    _, label, status = add_neko(run)
    # The result is shown over the scanned page: a screenshot to compare the note's with.
    run.shot('scanned')
    shot_name = run.current['screenshots'][-1]
    run.check(run.dokuen.crop_ui() is None, 'no Dokuen UI appears')
    run.check_written(label, status, 'AuditVocab')
    note = run.anki.newest(NEKO_QUERY)
    screenshot = image_file(note.get('screenshot'))
    run.check(screenshot is not None and screenshot.endswith('.png'), 'the screenshot field holds a .png image',
              note.get('screenshot'))
    run.expect(note.get('clipboard-image'), '', 'the clipboard-image field')
    pulled = run.pull_media(note.get('screenshot'))
    run.state['png_screenshot'] = pulled
    run.defer('Does the note\'s screenshot (first image) show the whole screen Dokuen scanned: the test page in '
              'Chrome, with 猫 on it, like the device screenshot beside it?',
              [pulled, os.path.join(run.folder, shot_name)], 'the whole screen')


def set_screenshot_behavior(run, behavior):
    run.dashboard.open()
    run.dashboard.choose('{screenshot} behavior', behavior)


@step('13.4')
def crop_screenshot(run):
    set_screenshot_behavior(run, 'Show crop UI')
    add_neko(run)
    crop = run.device.wait(lambda nodes: run.dokuen.crop_ui(nodes), 10)
    if not run.check(crop is not None, 'Dokuen\'s crop UI opens'):
        return
    run.shot('crop-ui')
    crop_shot_name = run.current['screenshots'][-1]
    run.check(any('Yomitan' in node.text for node in crop), 'it is titled with the plugin\'s name')
    run.dokuen.crop_and_confirm()
    note = run.anki.newest(NEKO_QUERY)
    run.check(image_file(note.get('screenshot')) is not None, 'the screenshot field holds an image')
    run.defer('Does the note\'s screenshot (first image) show only the part of the screen that was cropped (see '
              'the crop UI beside it), not the whole screen?',
              [run.pull_media(note.get('screenshot')), os.path.join(run.folder, crop_shot_name)],
              'only the cropped part')


def remove_picker_tasks(run):
    """Removes any task of Dokuen's image picker. A picker left open, for example by a step that
    failed, stays in its task, and Dokuen's next "Use a file instead" brings that task back instead
    of starting a new picker, under the crop UI, which then is not hidden."""
    activities = run.adb('shell', 'dumpsys', 'activity', 'activities')
    tasks = re.findall(r'Task\{\w+ #(\d+) [^}]*ImagePickerActivity', activities)
    for task in sorted(set(tasks)):
        run.adb('shell', 'am', 'stack', 'remove', task)
    if tasks:
        time.sleep(1)


@step('13.5')
def crop_from_file(run):
    remove_picker_tasks(run)
    # The image to choose: the test image, put on the device as its newest photo, so that it is the
    # first in Android's photo picker.
    local = os.path.join(run.folder, plan.IMAGE_NAME)
    with open(local, 'wb') as out:
        out.write(plan.test_image_png())
    run.adb('push', local, PICKER_IMAGE_PATH)
    run.adb('shell', 'touch', PICKER_IMAGE_PATH)
    run.adb('shell', 'content', 'call', '--uri', 'content://media', '--method', 'scan_volume', '--arg',
            'external_primary')
    try:
        add_neko(run)
        if run.device.wait(lambda nodes: run.dokuen.crop_ui(nodes), 10) is None:
            raise StepFailed('Dokuen\'s crop UI did not open')
        run.dokuen.crop_choose_newest_photo()
    except StepFailed:
        # Leave no picker or crop UI behind for the steps that follow.
        remove_picker_tasks(run)
        if run.dokuen.crop_ui():
            run.dokuen.crop_cancel()
        raise
    finally:
        run.adb('shell', 'rm', '-f', PICKER_IMAGE_PATH)
        run.adb('shell', 'content', 'call', '--uri', 'content://media', '--method', 'scan_volume', '--arg',
                'external_primary')
    note = run.anki.newest(NEKO_QUERY)
    run.check(image_file(note.get('screenshot')) is not None, 'the screenshot field holds an image')
    run.defer('Is the note\'s screenshot (first image) the same picture as the chosen file beside it (colored '
              'stripes)?', [run.pull_media(note.get('screenshot')), local], 'the chosen file\'s picture')


@step('13.6')
def crop_without_image(run):
    add_neko(run)
    if run.device.wait(lambda nodes: run.dokuen.crop_ui(nodes), 10) is None:
        raise StepFailed('Dokuen\'s crop UI did not open')
    run.dokuen.crop_continue_without_image()
    run.dokuen.wait_popup(10)
    # The window shows the entry from its top again, and the links are at the entry's end.
    label = run.dokuen.find_entry('猫', 'ねこ', 'AuditVocab').link('AuditVocab').text
    run.check(label.endswith('Added: AuditVocab'), 'the link reads "Added: AuditVocab"', label)
    run.expect(run.anki.newest(NEKO_QUERY).get('screenshot'), '', 'the newest 猫 note\'s screenshot field')


@step('13.7')
def crop_cancel(run):
    before = run.anki.count(NEKO_QUERY)
    add_neko(run)
    if run.device.wait(lambda nodes: run.dokuen.crop_ui(nodes), 10) is None:
        raise StepFailed('Dokuen\'s crop UI did not open')
    run.dokuen.crop_cancel()
    run.expect(run.anki.count(NEKO_QUERY), before, 'the number of 猫 notes')


@step('13.8')
def jpeg_screenshot(run):
    dashboard = run.dashboard
    set_screenshot_behavior(run, 'Full screen')
    dashboard.choose('Image format', 'JPEG')
    dashboard.set_slider_value('JPEG quality', 50)
    run.expect(dashboard.has(lambda nodes: first(by_text(nodes, 'JPEG quality: 50'))), True, 'JPEG quality: 50 is shown')
    _, label, status = add_neko(run)
    run.check_written(label, status, 'AuditVocab')
    screenshot = image_file(run.anki.newest(NEKO_QUERY).get('screenshot'))
    run.check(screenshot is not None and screenshot.endswith('.jpg'), 'the screenshot is a .jpg file', screenshot)
    run.defer('Does the .jpg screenshot (first image) look visibly compressed (blocky edges or banding) next to '
              '13.3\'s PNG screenshot beside it?',
              [run.pull_media(run.anki.newest(NEKO_QUERY).get('screenshot')), run.state.get('png_screenshot')],
              'visibly compressed')
    dashboard.open()
    dashboard.choose('Image format', 'PNG')


@step('13.9')
def clipboard_image(run):
    run.dokuen.hide_overlay()
    run.page.copy_image()
    _, label, status = add_neko(run)
    run.check_written(label, status, 'AuditVocab')
    note = run.anki.newest(NEKO_QUERY)
    clipboard = image_file(note.get('clipboard-image'))
    run.check(clipboard is not None and clipboard.endswith('.png'), 'the clipboard-image field holds a .png image',
              note.get('clipboard-image'))
    reference = os.path.join(run.folder, plan.IMAGE_NAME)
    with open(reference, 'wb') as out:
        out.write(plan.test_image_png())
    run.defer('Is the note\'s clipboard image (first image) the same picture as the copied one beside it (colored '
              'stripes)?', [run.pull_media(note.get('clipboard-image')), reference], 'the copied picture')
    run.expect(note.get('clipboard-text'), '', 'the clipboard-text field')
    run.dashboard.open()
    run.dashboard.choose('Image format', 'JPEG')
    _, label, status = add_neko(run)
    clipboard = image_file(run.anki.newest(NEKO_QUERY).get('clipboard-image'))
    run.check(clipboard is not None and clipboard.endswith('.png'), 'with JPEG chosen, the clipboard image is still .png',
              clipboard)
    run.dashboard.open()
    run.dashboard.choose('Image format', 'PNG')


@step('12.2')
def clipboard_lines(run):
    run.device.u2.set_clipboard(CLIPBOARD_LINES)
    _, label, status = add_neko(run)
    run.check_written(label, status, 'AuditVocab')
    note = run.anki.newest(NEKO_QUERY)
    text = note.get('clipboard-text') or ''
    run.check('&lt;b&gt;bold&lt;/b&gt;' in text, 'the clipboard-text field shows <b>bold</b> as typed', text)
    run.check(re.search(r'bold&lt;/b&gt;\s*(<br>|\n)\s*second line', text) is not None,
              'the two lines are one below the other', text)
    run.expect(note.get('clipboard-image'), '', 'the clipboard-image field')


# The same step in the run with Dokuen Pro, which skips Phase 12.
step('13.2')(clipboard_lines)


@step('13.10')
def prevented_without_crop(run):
    set_screenshot_behavior(run, 'Show crop UI')
    run.set_duplicate_settings(behavior='Prevent adding')
    _, label, status = add_neko(run)
    run.expect_message(status, ALREADY_EXISTS, contains=True)
    run.check(run.dokuen.crop_ui() is None, 'no crop UI appeared')


@step('13.11')
def overwrite_skips_screenshot(run):
    run.set_duplicate_settings(behavior='Allow overwriting')
    run.dashboard.select_format('AuditVocab')
    run.dashboard.set_overwrite_mode('screenshot', 'Skip')
    oldest_before = run.anki.notes(NEKO_QUERY)
    _, label, status = add_neko(run)
    run.check(run.dokuen.crop_ui() is None, 'no crop UI appears')
    run.check_written(label, status, 'AuditVocab', 'Overwritten')
    def oldest_screenshot(notes):
        oldest = min(notes, key=lambda item: item['id'])
        return dict(zip(oldest['fieldNames'], oldest['fields'])).get('screenshot')
    if not oldest_before:
        raise StepFailed('There is no 猫 note to overwrite; run the phase from 13.1')
    expected = oldest_screenshot(oldest_before)
    run.check(image_file(expected) is not None, 'the first 猫 note has a screenshot to keep', expected)
    run.expect(oldest_screenshot(run.anki.notes(NEKO_QUERY)), expected, 'the first 猫 note\'s screenshot field (kept)')


@step('12.1')
def images_without_pro(run):
    set_screenshot_behavior(run, 'Full screen')
    run.set_duplicate_settings(behavior='Allow adding')
    run.device.u2.set_clipboard(CLIPBOARD_TEXT)
    _, label, status = add_neko(run)
    run.check(run.dokuen.crop_ui() is None, 'no Dokuen UI appears')
    run.check(label is not None and label.endswith('Added: AuditVocab'), 'the link reads "Added: AuditVocab"', label)
    run.expect_message(status, 'Note added successfully, but with empty {screenshot} and {clipboard-image} fields '
                               '(images require Dokuen Pro).')
    note = run.anki.newest(NEKO_QUERY)
    run.expect(note.get('screenshot'), '', 'the screenshot field')
    run.expect(note.get('clipboard-image'), '', 'the clipboard-image field')
    run.expect(note.get('clipboard-text'), CLIPBOARD_TEXT, 'the clipboard-text field')




# ---------------------------------------------------------------------------------------------
# Phase 14: finish


@step('14.1')
def stop_serving(run):
    if run.page.server is not None:
        run.page.server.shutdown()
        run.page.server = None
        run.check(True, 'the test page is no longer served')
    else:
        run.note('the test page is served by another process (setup or serve); stop it there with Enter')


@step('14.2')
def delete_formats(run):
    dashboard = run.dashboard
    dashboard.open()
    names = dashboard.format_names()
    for name in reversed(names[1:]):
        dashboard.select_format(name)
        run.device.tap(dashboard.find(lambda nodes: first(by_desc(dashboard.mine(nodes), 'Delete card format?')),
                                      'the delete button'))
        confirm = run.device.wait(lambda nodes: first([node for node in nodes if node.text == 'Delete'
                                                       and node.package == plan.PACKAGE]), 5)
        if confirm is None:
            raise StepFailed('Deleting %s asked for no confirmation' % name)
        run.device.tap(confirm)
        time.sleep(1)
    run.expect(len(dashboard.format_names()), 1, 'the number of card formats left')
    run.check(not dashboard.has(lambda nodes: first(by_desc(dashboard.mine(nodes), 'Delete card format?'))),
              'the last format has no delete control')


# ---------------------------------------------------------------------------------------------
# Commands


def selected_steps(args):
    # A step of the plan this script does not know is shown, and asked about by its "Confirm" sentences.
    known = dict(STEPS)
    for step_id in plan_steps():
        if step_id not in known:
            known[step_id] = Step(step_id, 'manual', None)
    ordered = sorted(known.values(), key=lambda item: step_key(item.id))
    if args.steps:
        wanted = [item.strip() for item in args.steps.split(',') if item.strip()]
        unknown = [item for item in wanted if item not in known]
        if unknown:
            raise SystemExit('Unknown steps: %s' % ', '.join(unknown))
        return [known[item] for item in wanted]
    start = step_key(args.start) if args.start else (0, 0)
    end = step_key(args.end) if args.end else (1 << 30, 0)
    chosen = [item for item in ordered if start <= step_key(item.id) <= end]
    return chosen


def command_list(args):
    texts = plan_steps()
    missing = sorted(set(texts) - set(STEPS), key=step_key)
    for item in sorted(STEPS.values(), key=lambda item: step_key(item.id)):
        condition = PHASES.get(item.phase)
        print('%-6s %-8s %-11s %s' % (item.id, item.kind, condition.short() if condition else '',
                                     texts.get(item.id, '(not in the plan)').split('\n')[0][:100]))
    if missing:
        print('\nSteps of the plan this script does not know: %s' % ', '.join(missing))
    return 0


def command_run(args):
    folder = os.path.join(args.out, '%s-android' % datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
    os.makedirs(folder, exist_ok=True)
    steps = selected_steps(args)
    run = Run(args, folder)
    try:
        for item in steps:
            if run.host is None and run.configuration_error is None and item.phase >= 4:
                # Reading the configuration runs the test helper, which grants itself the AnkiDroid
                # permission; Phase 3's permission flow must come first. (A full run reads it in 2.3.)
                try:
                    run.ensure_configuration()
                except Exception as error:
                    # Only Phases 11 to 13 need the configuration; their steps fail with this error.
                    run.warn_once('The configuration could not be read: %s' % error)
            run.run_step(item)
            run.write_report()
    except KeyboardInterrupt:
        print('\nStopped.')
    finally:
        run.write_report()
        run.close()
    if not args.unattended:
        review(folder, run.report_data(), ask_terminal)
    counts = {}
    for result in run.results:
        counts[result['status']] = counts.get(result['status'], 0) + 1
    print('\n' + ', '.join('%s: %d' % item for item in sorted(counts.items())))
    for warning in run.warnings:
        print('WARNING: %s' % warning)
    print('Report: %s' % os.path.abspath(os.path.join(folder, 'report.md')))
    if any(result['status'] == 'PENDING' for result in run.results):
        print('Questions are left for the review: python scripts/anki_test_plan_android.py review %s' % folder)
    return 1 if any(result['status'] in ('FAIL', 'ERROR') for result in run.results) else 0


def command_review(args):
    with open(os.path.join(args.folder, 'report.json'), encoding='utf-8') as source:
        report = json.load(source)
    review(args.folder, report, ask_terminal)
    return 1 if any(result['status'] in ('FAIL', 'ERROR', 'PENDING') for result in report['steps']) else 0


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--adb', default='adb', help='path to adb')
    parser.add_argument('--serial', help='adb device serial, when several devices are connected')
    parser.add_argument('--out', default='anki-test-plan-reports', help='folder for results')
    parser.add_argument('--port', type=int, default=8766, help='port the test page is served on')
    commands = parser.add_subparsers(dest='command', required=True)
    run_parser = commands.add_parser('run', help='run the plan\'s steps')
    run_parser.add_argument('--from', dest='start', help='the first step to run, for example 6.1')
    run_parser.add_argument('--to', dest='end', help='the last step to run')
    run_parser.add_argument('--steps', help='only these steps, comma-separated')
    run_parser.add_argument('--no-build', action='store_true', help='setup (2.1) installs the APKs already built')
    run_parser.add_argument('--unattended', action='store_true',
                            help='never wait for the tester: questions are left unanswered in the report')
    run_parser.add_argument('--pro', choices=['auto', 'yes', 'no'], default='auto',
                            help='whether Dokuen Pro is active, which decides Phases 12 and 13 on a host at SDK '
                                 '0.7.0 or later; auto learns it from the first add')
    commands.add_parser('list', help='list every step and its configuration')
    review_parser = commands.add_parser('review', help='answer the questions a run left for the review')
    review_parser.add_argument('folder', help='the run\'s report folder')
    args = parser.parse_args()
    if args.command == 'list':
        return command_list(args)
    if args.command == 'review':
        return command_review(args)
    try:
        return command_run(args)
    except (RuntimeError, subprocess.CalledProcessError, OSError) as error:
        print('\nERROR: %s' % error)
        return 1


if __name__ == '__main__':
    sys.exit(main())
