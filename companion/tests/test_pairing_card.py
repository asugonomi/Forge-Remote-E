from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from gateway_discovery import _names, announce
from pairing_card import show_pairing_card


class PairingCardTest(unittest.TestCase):
    def test_html_stays_on_the_pc(self):
        sample = "FER1." + "A" * 100
        with TemporaryDirectory() as folder, patch("webbrowser.open") as opener:
            path = show_pairing_card(sample, Path(folder), open_browser=False)
            card = path.read_text(encoding="utf-8")
            self.assertIn(sample, card)
            self.assertIn('viewBox="0 0 ', card)
            self.assertNotRegex(card, r"(?i)<script|<iframe|(?:src|href)=|https://")
            self.assertIn("default-src 'none'", card)
            opener.assert_not_called()
            with self.assertRaises(ValueError):
                show_pairing_card("<html>", Path(folder))

    def test_discovery_off_and_stable_service_name(self):
        service, host = _names("00000000-0000-4000-8000-000000000123")
        self.assertTrue(service.endswith("._forgeremote._tcp.local."))
        self.assertTrue(host.endswith(".local."))
        with patch("subprocess.Popen") as start, announce("unneeded", None, 17873):
            start.assert_not_called()


if __name__ == "__main__":
    unittest.main()
