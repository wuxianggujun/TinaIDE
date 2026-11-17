#!/usr/bin/env python3
"""Exercise the signed App through accessibility and an SDK-only recreation controller."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import struct
import time
import uuid
import xml.etree.ElementTree as ET

APP = 'com.wuxianggujun.tinaide'
DRIVER = APP + '.qa.lifecycle/com.wuxianggujun.tinaide.testing.ReleaseLifecycleInstrumentation'
NAVIGATION = (('Projects', '项目'), ('Market', '市场'), ('Learn', '学习'), ('Profile', '我的'))
TABS = (('Plugins', '插件'), ('Packages', '软件包'), ('vcpkg',))
CASE_TIMEOUT_SECONDS = 60


def bounds(node):
    match = re.fullmatch(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', node.get('bounds', ''))
    if not match:
        raise ValueError('Accessibility node has no valid bounds')
    return tuple(map(int, match.groups()))


def snapshot_nodes(data):
    root = ET.fromstring(data)
    parents = {child: parent for parent in root.iter() for child in parent}
    return root, parents


def ui_state_found(result):
    # ElementTree leaves are false-like despite representing a matching UI node.
    return isinstance(result, ET.Element) or bool(result)


def validate_screenshot(png, width, height):
    if len(png) < 24 or png[:8] != b'\x89PNG\r\n\x1a\n' or struct.unpack_from('>II', png, 16) != (width, height):
        raise ValueError('Screenshot does not match the actual requested viewport')


class Device:
    def __init__(self, arguments, run_id):
        self.args = arguments
        self.run_id = run_id
        self.deadline = time.monotonic() + CASE_TIMEOUT_SECONDS
        self.width = self.height = 0
        self.density = 1.0
        self.font_scale = 1.0
        self.step = 'startup'
        self.parents = {}

    def adb(self, *arguments, check=True):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('HOST_TIMEOUT_60_SECONDS')
        return subprocess.run([self.args.adb, '-s', self.args.serial, *map(str, arguments)],
                              capture_output=True, check=check, timeout=min(15, remaining)).stdout

    def snapshot(self):
        path = '/data/local/tmp/tina-ui-' + self.run_id + '.xml'
        while time.monotonic() < self.deadline:
            status = self.adb('shell', 'uiautomator', 'dump', path)
            if b'dumped to:' not in status:
                continue
            data = self.adb('shell', 'cat', path)
            self.adb('shell', 'rm', path)
            (self.args.output / 'last-window.xml').write_bytes(data)
            root, parents = snapshot_nodes(data)
            self.parents = parents
            if any(node.get('package') == APP for node in root.iter('node')):
                (self.args.output / 'last-app-window.xml').write_bytes(data)
            return data, root, parents
        raise TimeoutError('No active accessibility window before the deadline')

    def find(self, root, aliases, predicate=lambda node: True):
        return next((node for node in root.iter('node') if node.get('package') == APP
                     and (node.get('text') in aliases or node.get('content-desc') in aliases)
                     and predicate(node)), None)

    def wait_for(self, callback):
        while time.monotonic() < self.deadline:
            data, root, parents = self.snapshot()
            result = callback(root, parents)
            if ui_state_found(result):
                return data, root, parents, result
        raise TimeoutError('Expected UI state did not appear')

    def tap(self, node):
        ancestor = node
        while ancestor is not None:
            if ancestor.get('clickable') == 'true' or ancestor.get('selected') == 'true':
                node = ancestor
                break
            ancestor = self.parents.get(ancestor)
        left, top, right, bottom = bounds(node)
        if not (0 <= left < right <= self.width and 0 <= top < bottom <= self.height):
            raise ValueError('Refusing to tap an offscreen node')
        self.adb('shell', 'input', 'tap', (left + right) // 2, (top + bottom) // 2)

    def selected(self, node, parents):
        while node is not None:
            if node.get('selected') == 'true' or node.get('checked') == 'true':
                return True
            node = parents.get(node)
        return False

    def nav_node(self, root, aliases):
        def in_navigation(node):
            left, top, right, bottom = bounds(node)
            if not (0 <= left < right <= self.width and 0 <= top < bottom <= self.height):
                return False
            return right <= self.density * 88 if self.width > self.height else top >= self.height - self.density * 104
        return self.find(root, aliases, in_navigation)

    def navigate(self, aliases):
        self.step = 'navigate-' + aliases[0]
        _, _, _, node = self.wait_for(lambda root, _: self.nav_node(root, aliases))
        self.tap(node)
        self.wait_for(lambda root, parents: (node := self.nav_node(root, aliases)) is not None
                      and self.selected(node, parents))

    def editable(self, root):
        return [node for node in root.iter('node') if node.get('package') == APP
                and node.get('class') == 'android.widget.EditText']

    def dismiss_keyboard(self):
        state = self.adb('shell', 'dumpsys', 'input_method').decode('utf-8', errors='replace')
        if re.search(r'\bmInputShown=true\b', state):
            # Back is the App's explicit close-search action, not an IME-only action.
            self.adb('shell', 'am', 'broadcast', '-a', APP + '.qa.lifecycle.' + self.run_id,
                     '-p', APP, '--es', 'command', 'hide-ime')

    def recreate(self):
        self.adb('shell', 'am', 'broadcast', '-a', APP + '.qa.lifecycle.' + self.run_id,
                 '-p', APP, '--es', 'command', 'recreate')

    def finish(self):
        self.adb('shell', 'am', 'broadcast', '-a', APP + '.qa.lifecycle.' + self.run_id,
                 '-p', APP, '--es', 'command', 'finish')

    def screenshot(self, filename):
        png = self.adb('exec-out', 'screencap', '-p')
        validate_screenshot(png, self.width, self.height)
        (self.args.output / filename).write_bytes(png)

    def scroll_to_text(self, aliases):
        for _ in range(6):
            _, root, _ = self.snapshot()
            node = self.find(root, aliases)
            if node is not None:
                return node
            containers = [node for node in root.iter('node') if node.get('package') == APP and node.get('scrollable') == 'true'
                          and bounds(node)[3] - bounds(node)[1] > self.density * 100]
            if not containers:
                raise AssertionError('No scrollable local-content viewport')
            left, top, right, bottom = bounds(max(containers, key=lambda node: (bounds(node)[2] - bounds(node)[0]) * (bounds(node)[3] - bounds(node)[1])))
            start = int(bottom - self.density * 24)
            reader = self.find(root, ('Done', '完成')) is not None
            distance = bottom - top - self.density * 48 if reader else self.density * 200
            end = int(max(top + self.density * 24, start - distance))
            self.adb('shell', 'input', 'swipe', (left + right) // 2, start,
                     (left + right) // 2, end, '600')
        raise AssertionError('Expected local content was not reachable: ' + aliases[0])

    def open_course_card(self, title):
        title_text = title.get('text')
        for _ in range(3):
            _, root, _ = self.snapshot()
            title = self.find(root, (title_text,))
            if title is None:
                raise AssertionError('Course title left the visible viewport')
            bottom = bounds(title)[3]
            candidates = [node for node in root.iter('node')
                          if node.get('text') in ('Start Learning', 'Continue', '开始学习', '继续')
                          and bottom <= bounds(node)[1] <= bottom + self.density * 160
                          and bounds(node)[3] < self.height - self.density * 120]
            if candidates:
                self.tap(min(candidates, key=lambda node: bounds(node)[1]))
                return
            self.adb('shell', 'input', 'swipe', self.width // 2, int(self.height - self.density * 160),
                     self.width // 2, int(self.height - self.density * 260), '500')
        raise AssertionError('Course action is not fully visible')


def wait_for_recreation(device):
    while 'TINA_ACTIVITY_RECREATED:' + device.run_id not in (device.args.output / 'lifecycle.log').read_text(encoding='utf-8', errors='replace'):
        if time.monotonic() >= device.deadline:
            raise TimeoutError('Activity recreation was not observed')
        time.sleep(0.1)


def check_feedback(device, report):
    device.step = 'open-feedback'
    device.tap(device.scroll_to_text(('Feedback', '反馈')))
    _, root, _, fields = device.wait_for(lambda root, _: device.editable(root))
    if len(fields) != 2:
        raise AssertionError('Expected message and contact draft fields')
    expected = ('ReleaseQA_Draft_20261118', 'qa@example.invalid')
    for index, value in enumerate(expected):
        device.step = 'fill-feedback-' + str(index)
        _, root, _, fields = device.wait_for(lambda root, _: device.editable(root))
        fields = sorted(fields, key=lambda node: bounds(node)[1])
        device.tap(fields[index])
        device.adb('shell', 'input', 'text', value)
        device.wait_for(lambda root, _: any(node.get('text') == value for node in device.editable(root)))
        device.dismiss_keyboard()
    device.step = 'recreate-feedback'
    device.recreate()
    wait_for_recreation(device)
    device.wait_for(lambda root, _: sorted(node.get('text') for node in device.editable(root)) == sorted(expected)
                    and device.find(root, ('Send feedback', '反馈')) is not None)
    device.dismiss_keyboard()
    device.screenshot('feedback.png')
    report.update(feedbackDialogRetained=True, messageAndContactRetained=True, feedbackSubmitted=False)


def check_tutorial(device, report):
    open_portal(device)
    device.navigate(NAVIGATION[2])
    device.step = 'open-local-course'
    device.open_course_card(device.scroll_to_text(('Lab 1: From project to execution',)))
    device.wait_for(lambda root, _: device.find(root, ('Done', '完成')))
    device.step = 'open-next-local-lesson'
    device.tap(device.scroll_to_text(('Lab 2: C++ values and control flow',)))
    device.wait_for(lambda root, parents: device.find(root, ('Done', '完成')) is not None
                    and (node := device.nav_node(root, NAVIGATION[2])) is not None
                    and device.selected(node, parents))
    device.recreate()
    wait_for_recreation(device)
    device.wait_for(lambda root, _: device.find(root, ('Done', '完成')))
    device.screenshot('tutorial.png')
    report.update(localCourseOpenedWithoutNetworking=True, nextLessonStaysInTutorials=True,
                  courseRetainedAfterActivityRecreation=True)


def open_portal(device):
    device.step = 'wait-main-portal'
    _, root, _, _ = device.wait_for(lambda root, _: device.nav_node(root, NAVIGATION[0]))
    prompt = device.find(root, ('Not Now', '暂不', '暂时不'))
    if prompt is not None:
        device.tap(prompt)


def check_navigation(device, report):
    open_portal(device)
    for destination in NAVIGATION:
        device.navigate(destination)
    device.recreate()
    wait_for_recreation(device)
    device.wait_for(lambda root, parents: (node := device.nav_node(root, NAVIGATION[3])) is not None
                    and device.selected(node, parents))
    report.update(navigationDestinations=4, selectedDestinationRetainedAfterRecreation=True)


def check_market(device, report):
    open_portal(device)
    device.navigate(NAVIGATION[1])
    _, root, parents, _ = device.wait_for(lambda root, _: device.find(root, TABS[0]))
    label_bounds = []
    for aliases in TABS:
        label = device.find(root, aliases)
        if label is None:
            raise AssertionError('Missing market tab label')
        rectangle = bounds(label)
        if rectangle[3] - rectangle[1] > 22 * device.density * device.font_scale:
            raise AssertionError('Market tab label exceeds one-line geometry')
        label_bounds.append(dict(text=label.get('text'), bounds=rectangle))
    search = device.find(root, ('Search', '搜索'))
    device.step = 'open-search'
    if search is None:
        raise AssertionError('Market search action is missing')
    device.tap(search)
    _, _, _, fields = device.wait_for(lambda root, _: device.editable(root))
    device.tap(fields[0])
    device.adb('shell', 'input', 'text', 'fmt')
    device.wait_for(lambda root, _: any(node.get('text') == 'fmt' for node in device.editable(root)))
    device.dismiss_keyboard()
    for aliases in TABS:
        device.step = 'search-tab-' + aliases[0]
        _, root, _, label = device.wait_for(lambda root, _: device.find(root, aliases))
        device.tap(label)
        device.wait_for(lambda root, parents: (node := device.find(root, aliases)) is not None
                        and device.selected(node, parents)
                        and any(field.get('text') == 'fmt' for field in device.editable(root)))
    device.recreate()
    device.step = 'recreate-market'
    wait_for_recreation(device)
    data, root, parents, _ = device.wait_for(lambda root, parents:
        any(node.get('text') == 'fmt' for node in device.editable(root))
        and (node := device.find(root, TABS[2])) is not None and device.selected(node, parents))
    device.dismiss_keyboard()
    device.step = 'find-fmt-result'
    device.step = 'find-fmt-result'
    data, root, parents, port = device.wait_for(lambda root, _:
        next((node for node in root.iter('node') if node.get('package') == APP
              and node.get('class') != 'android.widget.EditText'
              and re.match(r'^fmt(?:\s|$)', node.get('text', ''))), None))
    list_bounds = None
    parent = parents.get(port)
    while parent is not None:
        if parent.get('scrollable') == 'true':
            list_bounds = bounds(parent)
            break
        parent = parents.get(parent)
    if list_bounds is not None and device.width > device.height:
        if (list_bounds[3] - list_bounds[1]) / device.density < 120:
            raise AssertionError('Landscape results viewport is under 120 dp')
    report.update(navigationDestinations=1, labelBounds=label_bounds,
                  tabLabelGeometryFitsOneLine=True, searchRetainedAcrossThreeTabs=True,
                  searchRetainedAfterActivityRecreation=True, resultsViewportBounds=list_bounds)
    (device.args.output / 'market.xml').write_bytes(data)
    device.screenshot('market.png')
    close = device.find(root, ('Navigate back', 'Close search', 'Close', '返回', '关闭搜索', '关闭'))
    device.step = 'close-search'
    if close is None:
        raise AssertionError('Close-search action is missing')
    device.tap(close)
    device.wait_for(lambda root, _: not device.editable(root) and device.find(root, ('Search', '搜索')))
    report['searchCanBeClosed'] = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-avd', required=True)
    parser.add_argument('--driver-apk', type=Path, required=True)
    parser.add_argument('--target-apk', type=Path, required=True)
    parser.add_argument('--case', choices=('main-navigation', 'market-portrait', 'market-landscape', 'feedback-draft', 'tutorial-course'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('This state-changing regression is limited to a dedicated emulator')
    args.output.mkdir(parents=True, exist_ok=True)
    run_id = uuid.uuid4().hex
    device = Device(args, run_id)
    report = dict(case=args.case, runId=run_id, serial=args.serial, passed=False, rootRequired=False)
    process = None
    controls = {}
    network_controls = {}
    log_path = args.output / 'lifecycle.log'
    try:
        name = device.adb('emu', 'avd', 'name').decode().splitlines()[0]
        if name != args.expected_avd:
            raise ValueError('Unexpected AVD identity')
        for name in ('accelerometer_rotation', 'user_rotation'):
            controls[name] = device.adb('shell', 'settings', 'get', 'system', name).decode().strip()
        device.adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
        device.adb('shell', 'settings', 'put', 'system', 'user_rotation', '1' if args.case.endswith('landscape') else '0')
        size = device.adb('shell', 'wm', 'size').decode()
        width, height = map(int, re.findall(r'(\d+)x(\d+)', size)[-1])
        device.width, device.height = (max(width, height), min(width, height)) if args.case.endswith('landscape') else (min(width, height), max(width, height))
        device.density = int(re.findall(r'(\d+)', device.adb('shell', 'wm', 'density').decode())[-1]) / 160
        scale = device.adb('shell', 'settings', 'get', 'system', 'font_scale').decode().strip()
        device.font_scale = float(scale) if scale != 'null' else 1.0
        if args.case == 'tutorial-course':
            for name in ('wifi_on', 'mobile_data'):
                network_controls[name] = device.adb('shell', 'settings', 'get', 'global', name).decode().strip()
            device.adb('shell', 'svc', 'wifi', 'disable')
            device.adb('shell', 'svc', 'data', 'disable')
        with args.target_apk.open('rb') as stream:
            report['targetApkSha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
        paths = device.adb('shell', 'pm', 'path', APP).decode().splitlines()
        if len(paths) != 1 or not paths[0].startswith('package:/data/app/') or not paths[0].endswith('/base.apk'):
            raise ValueError('Expected one installed target APK')
        installed = device.adb('shell', 'sha256sum', paths[0][8:]).decode().split()[0]
        if installed != report['targetApkSha256']:
            raise ValueError('Installed App does not match the verified target artifact')
        report['installedApkSha256'] = installed
        result = device.adb('install', '-t', '-r', args.driver_apk)
        if b'Success' not in result:
            raise ValueError('SDK lifecycle controller installation failed')
        device.adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        with log_path.open('wb') as log:
            command = [args.adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'regressionRunId', run_id,
                       '-e', 'orientation', 'landscape' if args.case.endswith('landscape') else 'portrait']
            if args.case == 'feedback-draft':
                command += ['-e', 'activity', APP + '.settings.SettingsActivity']
            process = subprocess.Popen(command + [DRIVER], stdout=log, stderr=subprocess.STDOUT)
            if args.case == 'main-navigation':
                check_navigation(device, report)
            elif args.case == 'feedback-draft':
                check_feedback(device, report)
            elif args.case == 'tutorial-course':
                check_tutorial(device, report)
            else:
                check_market(device, report)
            device.finish()
            process.wait(timeout=max(1, device.deadline - time.monotonic()))
        text = log_path.read_text(encoding='utf-8', errors='replace')
        reports = [json.loads(line.split('TINA_LIFECYCLE_REPORT:', 1)[1]) for line in text.splitlines()
                   if 'TINA_LIFECYCLE_REPORT:' in line]
        if (len(reports) != 1 or reports[0].get('runId') != run_id or reports[0].get('passed') is not True
                or reports[0].get('recreationRequests', 0) < 1 or reports[0].get('savedStateCreations', 0) < 1):
            raise AssertionError('Incomplete or stale lifecycle proof')
        report.update(lifecycle=reports[0], width=device.width, height=device.height, density=device.density, passed=True)
    except Exception as error:
        report['failure'] = type(error).__name__ + ': ' + str(error)
        report['lastStep'] = device.step
    finally:
        if process is not None and process.poll() is None:
            subprocess.run([args.adb, '-s', args.serial, 'shell', 'am', 'force-stop', APP], capture_output=True, timeout=10)
            process.kill()
            process.wait(timeout=5)
        for name, value in controls.items():
            command = ['delete', 'system', name] if value == 'null' else ['put', 'system', name, value]
            subprocess.run([args.adb, '-s', args.serial, 'shell', 'settings', *command], capture_output=True, timeout=10, check=True)
        for name, service in (('wifi_on', 'wifi'), ('mobile_data', 'data')):
            if network_controls.get(name) == '1':
                subprocess.run([args.adb, '-s', args.serial, 'shell', 'svc', service, 'enable'], capture_output=True, timeout=10, check=True)
        (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
