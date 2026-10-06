"""The declared emulator matrix and its required evidence cannot drift independently."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from verification.profiles import artifact_name, artifact_requirements, load_profiles, PROFILES


class ProfileContractTest(unittest.TestCase):
    def setUp(self):
        self.original = json.loads(PROFILES.read_text())
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'profiles.json'

    def test_all_declared_profiles_have_unique_required_artifacts_and_producers(self):
        profiles = load_profiles()
        requirements = artifact_requirements(profiles)
        self.assertEqual(len(profiles), len(requirements))
        self.assertEqual(len(profiles), len({name for name, _ in requirements}))
        for profile, (name, producer) in zip(profiles, requirements):
            self.assertEqual(artifact_name(profile), name)
            self.assertIn(f"{profile['id']} / {profile['apk']}", producer)
        self.assertTrue(any(p['api'] == 29 for p in profiles))
        self.assertTrue(any(p['api'] == 36 for p in profiles))
        self.assertTrue(any(p['screen_profile'] == 'foldable' for p in profiles))
        foldable = next(profile for profile in profiles if profile['screen_profile'] == 'foldable')
        self.assertEqual('pixel_fold', foldable['device_profile'])
        large_page = [p for p in profiles if p['page_size'] == 16384]
        self.assertEqual(1, len(large_page))
        self.assertEqual(('36-16k-normal', 36, 'google_apis_ps16k', 'app-release'),
                         tuple(large_page[0][key] for key in ('id', 'api', 'target', 'apk')))
        self.assertTrue(any(p['api'] == 35 and p['page_size'] == 4096 for p in profiles))
        for profile in profiles:
            self.assertEqual(2400 if profile['api'] == 29 else 900, profile['instrumentation_timeout'])
        oldest = next(profile for profile in profiles if profile['api'] == 29)
        self.assertEqual(('default', 4096, 'macos-15', 'arm64-v8a', 'software', 900, 90),
                         tuple(oldest[key] for key in ('target', 'page_size', 'runner', 'arch', 'acceleration', 'boot_timeout', 'job_timeout')))
        for profile in profiles:
            if profile['api'] != 29:
                self.assertEqual(('ubuntu-latest', 'x86_64', 'kvm', 300, 40),
                                 tuple(profile[key] for key in ('runner', 'arch', 'acceleration', 'boot_timeout', 'job_timeout')))

    def test_default_image_is_required_only_for_native_api29(self):
        for index, target in ((0, 'google_apis'), (0, 'google_apis_ps16k'), (1, 'default')):
            contract = copy.deepcopy(self.original)
            contract['profiles'][index]['target'] = target
            self.path.write_text(json.dumps(contract))
            with self.subTest(index=index, target=target), self.assertRaisesRegex(ValueError, 'system image target'):
                load_profiles(self.path)
        for changes in ({'arch': 'x86_64'}, {'runner': 'ubuntu-latest'}, {'acceleration': 'kvm'}, {'page_size': 16384}):
            contract = copy.deepcopy(self.original)
            contract['profiles'][0].update(changes)
            self.path.write_text(json.dumps(contract))
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                load_profiles(self.path)

    def test_duplicate_id_or_identical_profile_rejected(self):
        for change_id in (False, True):
            contract = copy.deepcopy(self.original)
            extra = copy.deepcopy(contract['profiles'][0])
            if change_id:
                extra['id'] = 'another-id'
            contract['profiles'].append(extra)
            self.path.write_text(json.dumps(contract))
            with self.subTest(change_id=change_id), self.assertRaisesRegex(ValueError, 'Duplicate'):
                load_profiles(self.path)

    def test_missing_or_invalid_required_fields_rejected(self):
        for key, value in [('api', True), ('apk', '../old'), ('id', 'bad/id'),
                           ('page_size', 8192), ('target', 'google_apis_ps16k'),
                           ('screen_profile', 'unverified'), ('device_profile', 'pixel_2;other')]:
            contract = copy.deepcopy(self.original)
            contract['profiles'][0][key] = value
            self.path.write_text(json.dumps(contract))
            with self.subTest(key=key), self.assertRaises(ValueError):
                load_profiles(self.path)
        contract = copy.deepcopy(self.original)
        del contract['profiles'][0]['page_size']
        self.path.write_text(json.dumps(contract))
        with self.assertRaises(ValueError):
            load_profiles(self.path)

    def test_empty_contract_rejected(self):
        self.path.write_text(json.dumps({'schema': 1, 'profiles': []}))
        with self.assertRaises(ValueError):
            load_profiles(self.path)

    def test_invalid_provisioning_values_and_timeout_types_rejected(self):
        for key, value in [('runner', 'self-hosted'), ('arch', 'armeabi-v7a'), ('acceleration', 'automatic'),
                           ('boot_timeout', True), ('boot_timeout', '900'), ('boot_timeout', 900.0),
                           ('boot_timeout', 299), ('boot_timeout', 901), ('job_timeout', False),
                           ('job_timeout', '60'), ('job_timeout', 60.0), ('job_timeout', 39), ('job_timeout', 91)]:
            contract = copy.deepcopy(self.original)
            contract['profiles'][0][key] = value
            self.path.write_text(json.dumps(contract))
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                load_profiles(self.path)

    def test_main_and_job_budget_expansion_is_limited_to_api29_software(self):
        for index, key, value in ((0, 'instrumentation_timeout', True),
                                  (0, 'instrumentation_timeout', 2400.0),
                                  (0, 'instrumentation_timeout', '2400'),
                                  (0, 'instrumentation_timeout', 2399),
                                  (0, 'instrumentation_timeout', 2401),
                                  (1, 'instrumentation_timeout', 2400),
                                  (1, 'job_timeout', 90), (5, 'job_timeout', 90)):
            contract = copy.deepcopy(self.original)
            contract['profiles'][index][key] = value
            self.path.write_text(json.dumps(contract))
            with self.subTest(index=index, key=key, value=value), self.assertRaises(ValueError):
                load_profiles(self.path)

    def test_unsafe_or_mismatched_host_guest_acceleration_rejected(self):
        for changes in ({'runner': 'ubuntu-latest'}, {'arch': 'x86_64'}, {'acceleration': 'kvm'},
                        {'runner': 'ubuntu-latest', 'arch': 'x86_64', 'acceleration': 'kvm'}):
            contract = copy.deepcopy(self.original)
            contract['profiles'][0].update(changes)
            self.path.write_text(json.dumps(contract))
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                load_profiles(self.path)

    def test_provisioning_fields_are_required_and_cannot_be_silently_defaulted(self):
        for key in ('runner', 'arch', 'acceleration', 'boot_timeout', 'job_timeout', 'instrumentation_timeout'):
            contract = copy.deepcopy(self.original)
            del contract['profiles'][0][key]
            self.path.write_text(json.dumps(contract))
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'fields'):
                load_profiles(self.path)

    def test_exact_sdk_foldable_id_with_spaces_is_supported(self):
        contract = copy.deepcopy(self.original)
        foldable = next(profile for profile in contract['profiles'] if profile['screen_profile'] == 'foldable')
        foldable['device_profile'] = '7.6in Foldable'
        self.path.write_text(json.dumps(contract))
        actual = next(profile for profile in load_profiles(self.path) if profile['id'] == foldable['id'])
        self.assertEqual('7.6in Foldable', actual['device_profile'])

    def test_spaced_foldable_id_does_not_admit_arbitrary_or_unsafe_names(self):
        for value in ('pixel 2', 'Another Foldable', '7.6in Foldable ', ' 7.6in Foldable',
                      '7.6in  Foldable', '7.6in\tFoldable', '7.6in Foldable\n', '7.6in Foldable\r',
                      '7.6in\x00Foldable', '"7.6in Foldable"', "7.6in 'Foldable'", '7.6in Foldable;other',
                      '7.6in Foldable$(other)'):
            contract = copy.deepcopy(self.original)
            next(profile for profile in contract['profiles'] if profile['screen_profile'] == 'foldable')['device_profile'] = value
            self.path.write_text(json.dumps(contract))
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'device profile'):
                load_profiles(self.path)


if __name__ == '__main__':
    unittest.main()
