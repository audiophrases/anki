"""Mirror of android/app/src/test/java/com/eugen/ankiaudio/EdgeTokenTest.kt."""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "ankiaudio"))

import edge_token  # noqa: E402


class EdgeTokenTest(unittest.TestCase):
    # Reference vectors from upstream edge-tts ef51a4d, including a 5-minute boundary.
    def test_matches_upstream_v2_tokens(self):
        cases = {
            0: "52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
            1700000000: "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            1794848400: "BA6BBA6F6C1F3C56-B8D7B394E1413FD336F8C207AA28498B176D59C944F6406F4C05945E2BFF341F",
            1794848699: "BA6BBA6F6C1F3C56-B8D7B394E1413FD336F8C207AA28498B176D59C944F6406F4C05945E2BFF341F",
            1794848700: "2ECFBA8B967132B8-D599E300E94778F6B05C09D21E54FEA35F05DC44AC043D5C5426BD451E4832BB",
        }
        for seconds, expected in cases.items():
            self.assertEqual(expected, edge_token.generate(seconds), seconds)


if __name__ == "__main__":
    unittest.main()
