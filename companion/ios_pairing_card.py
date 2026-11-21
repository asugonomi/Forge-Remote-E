"""Display a one-use HTTPS invitation locally. No QR text leaves the PC."""
import html
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlsplit
import webbrowser

from pairing_card import qr_svg


def show_card(url, directory, open_browser=True):
    parts = urlsplit(url)
    if (parts.scheme != "https" or parts.username is not None or parts.password is not None
            or not parts.hostname or parts.path != "/" or parts.query
            or not re.fullmatch(r"pair=[A-Za-z0-9_-]{43}", parts.fragment)
            or any(ord(c) < 33 for c in url)):
        raise ValueError("Invalid HTTPS pairing link")
    content = f"""<!doctype html><html lang="ja"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
<meta name="referrer" content="no-referrer"><title>iPhone・iPadを登録 · Forge Remote E</title>
<style>*{{box-sizing:border-box}}body{{margin:0;background:#142328;color:#f0faf6;font:17px/1.65 system-ui,sans-serif}}main{{max-width:620px;margin:auto;padding:24px}}h1{{font-size:1.65rem;line-height:1.35}}svg{{display:block;width:min(100%,355px);margin:18px auto}}li{{margin:.6em 0}}textarea{{width:100%;min-height:5em}}summary{{cursor:pointer}}</style>
<main><h1>iPhone・iPadを登録</h1>
<ol><li>iPhoneのTailscaleをこのPCと同じアカウントまたは同じネットワークに接続します。</li>
<li>iPhoneのカメラでQRを読み取り、Safariで登録します。</li>
<li>Safariの共有メニューから「ホーム画面に追加」を選びます。</li></ol>
{qr_svg(url)}
<p>このQRは1台・15分間有効です。別の端末を登録するときや期限が切れたときは、PCで「Forge Remote iOS.cmd」をもう一度開いてください。</p>
<details><summary>登録用リンクをコピー</summary><textarea readonly aria-label="登録用リンク">{html.escape(url)}</textarea></details></main></html>"""
    directory = Path(directory)
    directory.mkdir(parents=True, mode=0o700, exist_ok=True)
    target = directory / "ios-pairing.html"
    with tempfile.NamedTemporaryFile(dir=directory, prefix=".ios-card-", delete=False) as f:
        tmp = Path(f.name)
    try:
        tmp.write_text(content, encoding="utf-8")
        os.replace(tmp, target)
    finally:
        tmp.unlink(missing_ok=True)
    if open_browser:
        webbrowser.open(target.resolve().as_uri())
    return target
