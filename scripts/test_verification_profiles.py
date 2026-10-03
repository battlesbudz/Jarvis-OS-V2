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
        self.assertTrue(any(p['page_size'] == 16384 for p in profiles))

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


if __name__ == '__main__':
    unittest.main()
