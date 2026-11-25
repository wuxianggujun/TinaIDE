import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
import struct
from types import SimpleNamespace

SPEC = importlib.util.spec_from_file_location('release_ui', Path(__file__).parents[1] / 'run_release_ui_blackbox.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReleaseUiBlackboxTest(unittest.TestCase):
    def test_matching_leaf_is_present(self):
        self.assertTrue(MODULE.ui_state_found(ET.Element('node', {'text': 'Market'})))

    def test_missing_node_is_not_present(self):
        self.assertFalse(MODULE.ui_state_found(None))

    def test_false_condition_is_not_present(self):
        self.assertFalse(MODULE.ui_state_found(False))

    def test_empty_field_list_is_not_present(self):
        self.assertFalse(MODULE.ui_state_found([]))

    def test_nonempty_fields_are_present(self):
        self.assertTrue(MODULE.ui_state_found([ET.Element('node')]))

    def test_bounds_are_parsed(self):
        self.assertEqual((1, 2, 40, 60), MODULE.bounds(ET.Element('node', {'bounds': '[1,2][40,60]'})))

    def test_invalid_bounds_are_rejected(self):
        with self.assertRaises(ValueError):
            MODULE.bounds(ET.Element('node', {'bounds': 'missing'}))

    def test_screenshot_dimensions_are_verified(self):
        header = b'\x89PNG\r\n\x1a\n' + b'\0' * 8 + struct.pack('>II', 720, 1280)
        MODULE.validate_screenshot(header, 720, 1280)

    def test_wrong_screenshot_dimensions_are_rejected(self):
        header = b'\x89PNG\r\n\x1a\n' + b'\0' * 8 + struct.pack('>II', 720, 1280)
        with self.assertRaises(ValueError):
            MODULE.validate_screenshot(header, 1280, 720)

    def test_non_png_is_rejected(self):
        with self.assertRaises(ValueError):
            MODULE.validate_screenshot(b'not png', 720, 1280)

    def test_viewport_must_contain_result_and_meet_height_threshold(self):
        result = ET.Element('node', {'package': MODULE.APP, 'text': 'fmt'})
        container = ET.Element('node', {'package': MODULE.APP, 'resource-id': 'vcpkg-results-viewport',
                                        'bounds': '[160,392][1280,632]'})
        self.assertEqual((160, 392, 1280, 632), MODULE.results_viewport(result, {result: container}, 1280, 720, 2))
        with self.assertRaises(AssertionError):
            MODULE.results_viewport(result, {}, 1280, 720, 2)
        container.set('bounds', '[160,392][1280,440]')
        with self.assertRaises(AssertionError):
            MODULE.results_viewport(result, {result: container}, 1280, 720, 2)
        container.set('bounds', '[160,392][1281,700]')
        with self.assertRaises(AssertionError):
            MODULE.results_viewport(result, {result: container}, 1280, 720, 2)
        container.set('bounds', '[160,392][1280,700]')
        with self.assertRaises(AssertionError):
            MODULE.results_viewport(result, {result: container}, 1280, 720, 0)
        container.set('package', 'foreign.fixture')
        with self.assertRaises(AssertionError):
            MODULE.results_viewport(result, {result: container}, 1280, 720, 2)

    def test_related_link_and_done_do_not_identify_the_current_lesson(self):
        device = MODULE.Device(SimpleNamespace(), 'fixture')
        device.width, device.height = 720, 1280
        root = ET.Element('hierarchy')
        def node(**attributes):
            return ET.SubElement(root, 'node', {'package': MODULE.APP, **attributes})
        node(text='Learn', bounds='[0,1200][120,1278]', selected='true')
        node(text='Done')
        node(text=MODULE.LESSON_TWO[0])
        toolbar = node(**{'resource-id': 'tutorial-reader-ide_workflow'})
        self.assertFalse(MODULE.reader_is_open(device, root, {}, 'cpp_basics'))
        toolbar.set('resource-id', 'tutorial-reader-cpp_basics')
        self.assertTrue(MODULE.reader_is_open(device, root, {}, 'cpp_basics'))
        toolbar.set('package', 'foreign.fixture')
        self.assertFalse(MODULE.reader_is_open(device, root, {}, 'cpp_basics'))


if __name__ == '__main__':
    unittest.main()
