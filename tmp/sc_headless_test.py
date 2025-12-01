#!/usr/bin/env python3
import sys, os, types, importlib.util, importlib, json, re, traceback

# Args
# 1: repo root (StreamingCommunity-main)
# 2: base url (e.g., https://streamingcommunityz.lat)
# 3: mid (int)
# 4: slug (str)
# 5: season (int)
# 6: episode (int)
# 7: output path (optional) -> if provided, attempt full HLS download

try:
    root = os.path.abspath(sys.argv[1])
    base = sys.argv[2]
    mid = int(sys.argv[3])
    slug = sys.argv[4].strip()
    sn = int(sys.argv[5])
    en = int(sys.argv[6])
    out = sys.argv[7] if len(sys.argv) > 7 else None
except Exception as e:
    print(json.dumps({"ok": False, "error": f"bad_args: {e}"}))
    sys.exit(0)

try:
    pkg = os.path.join(root, 'StreamingCommunity')
    sys.path.insert(0, root)
    sys.path.insert(0, pkg)
    m = types.ModuleType('StreamingCommunity'); m.__path__ = [pkg]; sys.modules['StreamingCommunity'] = m

    # Stub telebot and TelegramHelp if imported indirectly
    sys.modules.setdefault('telebot', types.ModuleType('telebot'))
    tg_stub = types.ModuleType('StreamingCommunity.TelegramHelp.telegram_bot')
    tg_stub.get_bot_instance = lambda: None
    class _TgSession: pass
    tg_stub.TelegramSession = _TgSession
    sys.modules['StreamingCommunity.TelegramHelp.telegram_bot'] = tg_stub

    from bs4 import BeautifulSoup
    import StreamingCommunity.Util.http_client as http_client
    from StreamingCommunity.Util.headers import get_userAgent

    # Force curl client everywhere
    http_client.create_client = lambda **k: http_client.create_client_curl(**k)

    # Preload Lib package hierarchy to avoid executing package __init__ (which imports TOR/qbittorrent)
    lib_dir = os.path.join(pkg, 'Lib')
    dwn_dir = os.path.join(lib_dir, 'Downloader')
    hls_dir = os.path.join(dwn_dir, 'HLS')
    for name, path in [
        ('StreamingCommunity.Lib', lib_dir),
        ('StreamingCommunity.Lib.Downloader', dwn_dir),
        ('StreamingCommunity.Lib.Downloader.HLS', hls_dir),
    ]:
        if name not in sys.modules:
            mod = types.ModuleType(name)
            mod.__path__ = [path]
            sys.modules[name] = mod
    # Load HLS.downloader module directly from file with fully qualified name
    hls_spec = importlib.util.spec_from_file_location(
        'StreamingCommunity.Lib.Downloader.HLS.downloader', os.path.join(hls_dir, 'downloader.py')
    )
    hls_mod = importlib.util.module_from_spec(hls_spec)
    hls_spec.loader.exec_module(hls_mod)
    HLS_Downloader = hls_mod.HLS_Downloader

    # Import ScrapeSerie with patched client via direct spec to avoid package __init__ side-effects
    scrape_path = os.path.join(pkg, 'Api', 'Site', 'streamingcommunity', 'util', 'ScrapeSerie.py')
    spec = importlib.util.spec_from_file_location('ScrapeSerie', scrape_path)
    ScrapeSerie = importlib.util.module_from_spec(spec); spec.loader.exec_module(ScrapeSerie)
    GetSerieInfo = ScrapeSerie.GetSerieInfo

    # Import vixcloud AFTER patch
    vix = importlib.import_module('StreamingCommunity.Api.Player.vixcloud')
    vix.create_client = http_client.create_client_curl
    VideoSource = vix.VideoSource

    t = []
    t.append(f'py-se:start(mid={mid},slug={slug},s={sn},e={en})')

    gs = GetSerieInfo(base + '/it', mid, slug)
    gs.getNumberSeason()
    ep = gs.selectEpisode(sn, en-1)

    def _eid(x):
        try:
            return x['id']
        except Exception:
            pass
        for k in ('id','episode_id','video_id','stream_id'):
            v = getattr(x, k, None)
            if v is not None:
                return v
        return None

    eid = _eid(ep)
    t.append(f'eid={eid}')

    watch_ref = f"{base}/it/watch/{mid}?e={eid}"
    vs = VideoSource(base + '/it', True, mid)
    script = None
    resp = None
    sess = None

    try:
        t.append('iframe:request')
        vs.get_iframe(eid)
        vs.get_content()
        script = 'ok'
    except Exception:
        t.append('iframe:ERR')

    if script is None:
        sess = http_client.create_client_curl(headers={'User-Agent': get_userAgent(), 'Accept-Language':'it-IT,it;q=0.9,en;q=0.8'}, allow_redirects=True)
        t.append('embed-url')
        embed = sess.get(f"{base}/it/embed-url/{eid}").text.strip()
        sess.headers.update({'Referer': watch_ref, 'Origin': base})
        resp = sess.get(embed)
        soup = BeautifulSoup(resp.text, 'html.parser')
        scr = None
        for tag in soup.find_all('script'):
            try:
                txt = tag.text or ''
                if 'masterPlaylist' in txt or 'video' in txt:
                    scr = txt
                    break
            except Exception:
                pass
        if scr:
            t.append('script:parse')
            vs.parse_script(scr)

    pl = vs.get_playlist()
    t.append('py-pl:start')
    if not pl:
        txt = resp.text if resp is not None else ''
        m = re.search(r'https?://[^\s\x22\x27<>]+\.m3u8[^\s\x22\x27<>]*', txt)
        pl = m.group(0) if m else None
        t.append('py-pl:OK' if pl else 'py-pl:ERR')

    if not pl and sess is not None:
        try:
            wr = sess.get(watch_ref)
            txt2 = wr.text
            m2 = re.search(r'https?://[^\s\x22\x27<>]+\.m3u8[^\s\x22\x27<>]*', txt2)
            pl = m2.group(0) if m2 else None
            t.append(f'watch:request(mid={mid},e={eid})')
        except Exception:
            pass

    res = None
    ok = False
    if out and pl:
        hdr = {'User-Agent': get_userAgent(), 'Referer': watch_ref, 'Origin': base}
        res = HLS_Downloader(m3u8_url=pl, output_path=out, headers=hdr).start()
        ok = bool(res) and res.get('error') is None
    else:
        ok = pl is not None

    print(json.dumps({'ok': ok, 'pl': pl, 'trace': '->'.join(t), 'error': (res or {}).get('error') if isinstance(res, dict) else None, 'watch': watch_ref, 'eid': eid}))

except Exception as e:
    print(json.dumps({'ok': False, 'error': str(e), 'trace': traceback.format_exc()}))
