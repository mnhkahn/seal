import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch, MagicMock

spec = importlib.util.spec_from_file_location('notify_lark', Path(__file__).parents[1] / 'notify-lark.py')
notify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notify)


class NotificationTest(unittest.TestCase):
    def test_success_links_only_after_publication(self):
        payload = notify.build_message({'RELEASE_STATUS': 'success', 'RELEASE_VERSION': '1.2.3',
            'GITHUB_RUN_ID': '42', 'GITHUB_REF_NAME': 'v1.2.3', 'PGYER_RELEASE_URL': 'https://www.pgyer.com/example',
            'PGYER_OUTCOME': 'success', 'GITHUB_RELEASE_OUTCOME': 'success'})
        text = payload['content']['text']
        self.assertIn('发布成功', text)
        self.assertIn('https://www.pgyer.com/example', text)
        self.assertIn('/releases/tag/v1.2.3', text)
        self.assertIn('/actions/runs/42', text)

    def test_early_failure_does_not_claim_a_download_exists(self):
        text = notify.build_message({'RELEASE_STATUS': 'failure', 'GITHUB_REF_NAME': 'v1.2.3',
                                    'PGYER_RELEASE_URL': 'https://www.pgyer.com/example'})['content']['text']
        self.assertIn('发布失败', text)
        self.assertIn('v1.2.3', text)
        self.assertNotIn('下载', text)

    def test_manual_release_has_no_invented_github_release(self):
        text = notify.build_message({'RELEASE_STATUS': 'success', 'RELEASE_VERSION': '1.2.3',
                                    'GITHUB_REF_NAME': 'main'})['content']['text']
        self.assertNotIn('/releases/tag/main', text)

    def test_provider_nonzero_is_a_failure_even_with_http_200(self):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b'{"code":19024}'
        with patch.object(notify, 'build_opener') as opener:
            opener.return_value.open.return_value = response
            with self.assertRaises(ValueError):
                notify.send('https://open.larksuite.com/open-apis/bot/v2/hook/example', {})

    def test_success_requires_explicit_zero_code(self):
        for body, success in [(b'{"code":0}', True), (b'{"StatusCode":0}', True), (b'{}', False)]:
            response = MagicMock()
            response.__enter__.return_value.read.return_value = body
            with patch.object(notify, 'build_opener') as opener:
                opener.return_value.open.return_value = response
                if success:
                    notify.send('https://open.larksuite.com/open-apis/bot/v2/hook/example', {})
                else:
                    with self.assertRaises(ValueError):
                        notify.send('https://open.larksuite.com/open-apis/bot/v2/hook/example', {})

    def test_rejects_non_lark_and_non_https_endpoints(self):
        for url in ['http://open.larksuite.com/open-apis/bot/v2/hook/example', 'https://example.com/hook',
                    'https://open.larksuite.com.evil.example/open-apis/bot/v2/hook/example']:
            with self.assertRaises(ValueError):
                notify.send(url, {})


if __name__ == '__main__':
    unittest.main()
