#!/usr/bin/env python3
"""Send a release result to a private Lark custom-bot webhook."""
import json
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener


def build_message(env):
    status = env.get('RELEASE_STATUS', 'failure')
    title = {'success': '✅ Seal 篆书识别发布成功', 'cancelled': '⏹ Seal 篆书识别发布已取消'}.get(status, '❌ Seal 篆书识别发布失败')
    repository = env.get('GITHUB_REPOSITORY', 'mnhkahn/seal')
    server = env.get('GITHUB_SERVER_URL', 'https://github.com').rstrip('/')
    run_url = f"{server}/{repository}/actions/runs/{env.get('GITHUB_RUN_ID', '')}"
    lines = [title, f"版本：{env.get('RELEASE_VERSION') or env.get('GITHUB_REF_NAME', '未知')}"]
    if env.get('RELEASE_CODE'):
        lines.append(f"版本号：{env['RELEASE_CODE']}")
    if env.get('GITHUB_SHA'):
        lines.append(f"提交：{env['GITHUB_SHA'][:8]}")
    if env.get('GITHUB_RUN_ATTEMPT'):
        lines.append(f"运行次数：{env['GITHUB_RUN_ATTEMPT']}")
    # Show download links only for publishers that actually succeeded.
    if env.get('PGYER_OUTCOME') == 'success' and env.get('PGYER_RELEASE_URL'):
        lines.append(f"蒲公英下载：{env['PGYER_RELEASE_URL']}")
    if env.get('GITHUB_RELEASE_OUTCOME') == 'success':
        tag = quote(env.get('GITHUB_REF_NAME', ''), safe='')
        lines.append(f"GitHub 下载：{server}/{repository}/releases/tag/{tag}")
    lines.append(f"构建详情：{run_url}")
    return {'msg_type': 'text', 'content': {'text': '\n'.join(lines)}}


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def send(webhook, payload):
    parsed = urlparse(webhook)
    if (parsed.scheme != 'https' or parsed.hostname not in {'open.larksuite.com', 'open.feishu.cn'}
            or not parsed.path.startswith('/open-apis/bot/v2/hook/') or parsed.username
            or parsed.password or parsed.port not in (None, 443) or parsed.query or parsed.fragment):
        raise ValueError('Invalid Lark webhook configuration')
    request = Request(webhook, data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
                      headers={'Content-Type': 'application/json; charset=utf-8'}, method='POST')
    with build_opener(NoRedirects()).open(request, timeout=20) as response:
        result = json.loads(response.read(65536).decode('utf-8'))
    code = result.get('code', result.get('StatusCode'))
    if code != 0:
        # Provider text may contain configuration details; log only the response code.
        raise ValueError(f'Lark rejected notification (code={code})')


def main():
    webhook = os.environ.get('LARK_WEBHOOK_URL', '')
    if not webhook:
        print('::error::LARK_WEBHOOK_URL is not configured')
        return 1
    try:
        send(webhook, build_message(os.environ))
    except HTTPError as error:
        print(f'::error::Lark notification HTTP failure ({error.code})')
        return 1
    except (URLError, TimeoutError, OSError):
        print('::error::Lark notification network failure')
        return 1
    except (ValueError, TypeError, AttributeError):
        print('::error::Lark notification response or configuration is invalid')
        return 1
    print('Lark notification delivered successfully')
    return 0


if __name__ == '__main__':
    sys.exit(main())
