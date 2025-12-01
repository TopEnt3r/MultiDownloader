#!/usr/bin/env python3
"""
GuardaSerie headless script for Java integration.
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
    config_loader.get_site_name_from_stack = lambda: 'guardaserie'
    
    from StreamingCommunity.Api.Site.guardaserie.site import title_search, media_search_manager
    from StreamingCommunity.Api.Site.guardaserie.util.ScrapeSerie import GetSerieInfo
    from StreamingCommunity.Api.Template.Class.SearchType import MediaItem
    from StreamingCommunity.Api.Player.supervideo import VideoSource
    from StreamingCommunity import HLS_Downloader
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
    SITE_URL = config_manager.get_site('guardaserie', 'full_url').rstrip('/')
except:
    SITE_URL = 'https://guardaserie.foo'


def cmd_search(query):
    """Search for series on GuardaSerie"""
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
                'type': 'tv',
                'year': '',
                'source': 'GuardaSerie',
                'sourceAlias': 'GuardaSerie',
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


def cmd_list_episodes(url, title):
    """List all episodes for a TV series"""
    try:
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()
        
        try:
            # Create fake MediaItem
            class FakeMediaItem:
                def __init__(self, item_url, name):
                    self.url = item_url
                    self.name = name
            
            fake_item = FakeMediaItem(url, title)
            scraper = GetSerieInfo(fake_item)
            seasons_count = scraper.getNumberSeason()
            series_name = scraper.tv_name or title
            
            seasons = []
            for season_num in range(1, seasons_count + 1):
                episodes_list = scraper.getEpisodeSeasons(season_num)
                
                episodes = []
                for idx, ep in enumerate(episodes_list):
                    episodes.append({
                        'id': ep.get('url', ''),
                        'season': str(season_num),
                        'episode': str(ep.get('number', idx + 1)),
                        'title': ep.get('name', f'Episodio {idx + 1}'),
                        'url': ep.get('url', ''),
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
            class FakeMediaItem:
                def __init__(self, item_url, name):
                    self.url = item_url
                    self.name = name
            
            fake_item = FakeMediaItem(url, series_name)
            scraper = GetSerieInfo(fake_item)
            scraper.getNumberSeason()
            
            season_int = int(season)
            ep_idx = int(episode_index)
            
            obj_episode = scraper.selectEpisode(season_int, ep_idx)
            if not obj_episode:
                raise Exception(f"Episode not found")
            
            video_source = VideoSource(obj_episode.get('url'))
            master_playlist = video_source.get_playlist()
            
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


def main():
    if len(sys.argv) < 2:
        print(json.dumps({
            'status': 'error',
            'message': 'Usage: guardaserie_headless.py <command> [args]'
        }), file=sys.stderr)
        sys.exit(1)
    
    command = sys.argv[1]
    
    if command == 'search':
        if len(sys.argv) < 3:
            print(json.dumps({'status': 'error', 'message': 'Missing query'}), file=sys.stderr)
            sys.exit(1)
        cmd_search(sys.argv[2])
        
    elif command == 'list-episodes':
        if len(sys.argv) < 4:
            print(json.dumps({'status': 'error', 'message': 'Missing url and title'}), file=sys.stderr)
            sys.exit(1)
        cmd_list_episodes(sys.argv[2], sys.argv[3])
        
    elif command == 'download-episode':
        if len(sys.argv) < 7:
            print(json.dumps({'status': 'error', 'message': 'Missing arguments: url series_name season episode_index output_dir'}), file=sys.stderr)
            sys.exit(1)
        cmd_download_episode(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6])
        
    else:
        print(json.dumps({'status': 'error', 'message': f'Unknown command: {command}'}), file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
