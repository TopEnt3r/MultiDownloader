#!/usr/bin/env python3
"""
StreamingWatch headless script for Java integration.
Provides JSON-based API for search, list episodes, and download.
"""

import sys
import os
import json
import io
import logging

os.environ['NO_COLOR'] = '1'
os.environ['TERM'] = 'dumb'
os.environ['FORCE_COLOR'] = '0'

logging.basicConfig(level=logging.CRITICAL)
logging.getLogger().setLevel(logging.CRITICAL)

import warnings
warnings.filterwarnings("ignore")

# Set up the project root and change working directory
PROJECT_ROOT = os.path.join(os.path.dirname(__file__), '..', 'Downloader', 'StreamingCommunity', 'StreamingCommunity-main')
PROJECT_ROOT = os.path.abspath(PROJECT_ROOT)
os.chdir(PROJECT_ROOT)
sys.path.insert(0, PROJECT_ROOT)

# Stub optional dependencies BEFORE any imports
import types
sys.modules['telebot'] = types.ModuleType('telebot')
sys.modules['qbittorrentapi'] = types.ModuleType('qbittorrentapi')
# Create proper pywidevine stub package with all submodules
pywv = types.ModuleType('pywidevine')
pywv.__path__ = []  # Make it a package
pywv.Cdm = type('Cdm', (), {'__init__': lambda self, *a, **kw: None})
pywv.Device = type('Device', (), {'__init__': lambda self, *a, **kw: None, 'load': classmethod(lambda cls, *a: cls())})
pywv_cdm = types.ModuleType('pywidevine.cdm')
pywv_cdm.Cdm = pywv.Cdm
pywv_device = types.ModuleType('pywidevine.device')
pywv_device.Device = pywv.Device
pywv_pssh = types.ModuleType('pywidevine.pssh')
pywv_pssh.PSSH = type('PSSH', (), {'__init__': lambda self, *a, **kw: None})
sys.modules['pywidevine'] = pywv
sys.modules['pywidevine.cdm'] = pywv_cdm
sys.modules['pywidevine.device'] = pywv_device
sys.modules['pywidevine.pssh'] = pywv_pssh
tg_stub = types.ModuleType('StreamingCommunity.TelegramHelp.telegram_bot')
tg_stub.get_bot_instance = lambda: None
tg_stub.TelegramSession = type('TelegramSession', (), {})
sys.modules['StreamingCommunity.TelegramHelp.telegram_bot'] = tg_stub

# Redirect ALL output during imports (including ConfigManager)
old_stderr = sys.stderr
old_stdout = sys.stdout
sys.stderr = io.StringIO()
sys.stdout = io.StringIO()

try:
    # Monkey-patch site_constant to return correct site name
    import StreamingCommunity.Api.Template.config_loader as config_loader
    config_loader.get_site_name_from_stack = lambda: 'streamingwatch'
    
    from StreamingCommunity.Api.Site.streamingwatch.site import title_search, media_search_manager
    from StreamingCommunity.Api.Site.streamingwatch.util.ScrapeSerie import GetSerieInfo
    from StreamingCommunity.Api.Player.hdplayer import VideoSource
except Exception as e:
    sys.stderr = old_stderr
    sys.stdout = old_stdout
    print(json.dumps({'status': 'error', 'message': f'Import error: {str(e)}'}))
    sys.exit(1)
import os as os_module

sys.stderr = old_stderr
sys.stdout = old_stdout

# Get the site URL from domains config
try:
    from StreamingCommunity.Util.config_json import config_manager
    SITE_URL = config_manager.get_site('streamingwatch', 'full_url').rstrip('/')
except:
    SITE_URL = 'https://streamingwatch.foo'


def cmd_search(query):
    """Search for content on StreamingWatch"""
    try:
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()
        
        try:
            count = title_search(query)
        finally:
            sys.stdout = saved_stdout
            sys.stderr = saved_stderr
        
        results = []
        for i in range(media_search_manager.get_length()):
            item = media_search_manager.get(i)
            results.append({
                'id': str(i),
                'title': getattr(item, 'name', 'Unknown'),
                'type': getattr(item, 'type', 'tv'),
                'year': getattr(item, 'date', ''),
                'source': 'StreamingWatch',
                'sourceAlias': 'StreamingWatch',
                'url': getattr(item, 'url', '')
            })
        
        print(json.dumps({
            'status': 'ok',
            'count': len(results),
            'results': results
        }))
        
    except Exception as e:
        print(json.dumps({
            'status': 'error',
            'message': str(e)
        }), file=sys.stderr)
        sys.exit(1)


def cmd_list_episodes(url):
    """List all episodes for a TV series"""
    try:
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()
        
        try:
            scraper = GetSerieInfo(url)
            seasons_count = scraper.getNumberSeason()
            series_name = scraper.series_name
            
            seasons = []
            for season_num in range(1, seasons_count + 1):
                episodes_list = scraper.getEpisodeSeasons(season_num)
                
                episodes = []
                for idx, ep in enumerate(episodes_list):
                    episodes.append({
                        'id': getattr(ep, 'url', ep.get('url', '') if isinstance(ep, dict) else ''),
                        'season': str(season_num),
                        'episode': str(getattr(ep, 'number', ep.get('number', idx + 1) if isinstance(ep, dict) else idx + 1)),
                        'title': getattr(ep, 'name', ep.get('name', f'Episodio {idx + 1}') if isinstance(ep, dict) else f'Episodio {idx + 1}'),
                        'url': getattr(ep, 'url', ep.get('url', '') if isinstance(ep, dict) else ''),
                        'index': idx
                    })
                
                seasons.append({
                    'season': str(season_num),
                    'episodes': episodes
                })
        finally:
            sys.stdout = saved_stdout
            sys.stderr = saved_stderr
        
        print(json.dumps({
            'status': 'ok',
            'series_name': series_name,
            'seasons': seasons
        }))
        
    except Exception as e:
        print(json.dumps({
            'status': 'error',
            'message': str(e)
        }), file=sys.stderr)
        sys.exit(1)


def cmd_download_episode(url, series_name, season, episode_index, output_dir):
    """Download a specific episode"""
    try:
        os_module.makedirs(output_dir, exist_ok=True)
        
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()
        
        original_dir = os_module.getcwd()
        os_module.chdir(output_dir)
        
        try:
            scraper = GetSerieInfo(url)
            scraper.getNumberSeason()
            
            season_int = int(season)
            ep_idx = int(episode_index)
            
            obj_episode = scraper.selectEpisode(season_int, ep_idx)
            if not obj_episode:
                raise Exception(f"Episode not found")
            
            ep_url = getattr(obj_episode, 'url', obj_episode.get('url', '') if isinstance(obj_episode, dict) else '')
            
            video_source = VideoSource()
            master_playlist = video_source.get_m3u8_url(ep_url)
            
            mp4_name = f"{series_name} S{str(season_int).zfill(2)}E{str(ep_idx + 1).zfill(2)}.mkv"
            mp4_path = os_module.path.join(output_dir, mp4_name)
            
            hls_process = HLS_Downloader(
                m3u8_url=master_playlist,
                output_path=mp4_path
            ).start()
            
            if hls_process['error'] is not None:
                raise Exception(f"Download failed: {hls_process['error']}")
            if hls_process['stopped']:
                raise Exception("Download was stopped")
                
            path = hls_process['path']
                
        finally:
            os_module.chdir(original_dir)
            sys.stdout = saved_stdout
            sys.stderr = saved_stderr
        
        print(json.dumps({
            'status': 'ok',
            'message': 'Download completed',
            'file': path if path else mp4_path
        }))
        
    except Exception as e:
        print(json.dumps({
            'status': 'error',
            'message': str(e)
        }), file=sys.stderr)
        sys.exit(1)


def cmd_download_film(url, title, output_dir):
    """Download a film"""
    try:
        os_module.makedirs(output_dir, exist_ok=True)
        
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()
        
        original_dir = os_module.getcwd()
        os_module.chdir(output_dir)
        
        try:
            video_source = VideoSource()
            master_playlist = video_source.get_m3u8_url(url)
            
            mp4_name = f"{title}.mkv"
            mp4_path = os_module.path.join(output_dir, mp4_name)
            
            hls_process = HLS_Downloader(
                m3u8_url=master_playlist,
                output_path=mp4_path
            ).start()
            
            if hls_process['error'] is not None:
                raise Exception(f"Download failed: {hls_process['error']}")
            if hls_process['stopped']:
                raise Exception("Download was stopped")
                
            path = hls_process['path']
                
        finally:
            os_module.chdir(original_dir)
            sys.stdout = saved_stdout
            sys.stderr = saved_stderr
        
        print(json.dumps({
            'status': 'ok',
            'message': 'Download completed',
            'file': path if path else mp4_path
        }))
        
    except Exception as e:
        print(json.dumps({
            'status': 'error',
            'message': str(e)
        }), file=sys.stderr)
        sys.exit(1)


def main():
    if len(sys.argv) < 2:
        print(json.dumps({
            'status': 'error',
            'message': 'Usage: streamingwatch_headless.py <command> [args]'
        }), file=sys.stderr)
        sys.exit(1)
    
    command = sys.argv[1]
    
    if command == 'search':
        if len(sys.argv) < 3:
            print(json.dumps({'status': 'error', 'message': 'Missing query'}), file=sys.stderr)
            sys.exit(1)
        cmd_search(sys.argv[2])
        
    elif command == 'list-episodes':
        if len(sys.argv) < 3:
            print(json.dumps({'status': 'error', 'message': 'Missing url'}), file=sys.stderr)
            sys.exit(1)
        cmd_list_episodes(sys.argv[2])
        
    elif command == 'download-episode':
        if len(sys.argv) < 7:
            print(json.dumps({'status': 'error', 'message': 'Missing arguments: url series_name season episode_index output_dir'}), file=sys.stderr)
            sys.exit(1)
        cmd_download_episode(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6])
        
    elif command == 'download-film':
        if len(sys.argv) < 5:
            print(json.dumps({'status': 'error', 'message': 'Missing arguments: url title output_dir'}), file=sys.stderr)
            sys.exit(1)
        cmd_download_film(sys.argv[2], sys.argv[3], sys.argv[4])
        
    else:
        print(json.dumps({'status': 'error', 'message': f'Unknown command: {command}'}), file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
