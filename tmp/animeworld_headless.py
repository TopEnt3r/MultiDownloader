#!/usr/bin/env python3
"""
AnimeWorld headless script for Java integration.
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

# Add StreamingCommunity to path - try multiple locations
script_dir = os.path.dirname(os.path.abspath(__file__))
possible_paths = [
    os.path.join(script_dir, '..', 'StreamingCommunity'),
    os.path.join(script_dir, '..', 'StreamingCommunity', 'StreamingCommunity'),
    os.path.join(script_dir, '..', 'Downloader', 'StreamingCommunity', 'StreamingCommunity-main'),  # Dev
    os.path.join(os.getcwd(), 'StreamingCommunity'),  # CWD
]
for p in possible_paths:
    if os.path.exists(p):
        sys.path.insert(0, p)
        os.chdir(p)
        break

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
    config_loader.get_site_name_from_stack = lambda: 'animeworld'
    
    from StreamingCommunity.Api.Site.animeworld.site import title_search, media_search_manager
    from StreamingCommunity.Api.Site.animeworld.util.ScrapeSerie import ScrapSerie
    from StreamingCommunity.Api.Player.sweetpixel import VideoSource
    from StreamingCommunity import MP4_downloader
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
    SITE_URL = config_manager.get_site('animeworld', 'full_url').rstrip('/')
except:
    SITE_URL = 'https://animeworld.so'


def cmd_search(query):
    """Search for anime on AnimeWorld"""
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
                'type': getattr(item, 'type', 'TV'),
                'year': '',
                'source': 'AnimeWorld',
                'sourceAlias': 'AnimeWorld',
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
    """List all episodes for an anime"""
    try:
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()
        
        try:
            scraper = ScrapSerie(url, SITE_URL)
            series_name = scraper.get_name()
            episodes_list = scraper.get_episodes()
            
            episodes = []
            for idx, ep in enumerate(episodes_list):
                episodes.append({
                    'id': ep.get('link', ''),
                    'season': '1',
                    'episode': str(ep.get('number', idx + 1)),
                    'title': f"Episodio {ep.get('number', idx + 1)}",
                    'index': idx
                })
        finally:
            sys.stdout = saved_stdout
            sys.stderr = saved_stderr
        
        print(json.dumps({
            'status': 'ok',
            'series_name': series_name,
            'seasons': [{
                'season': '1',
                'episodes': episodes
            }]
        }))
        
    except Exception as e:
        print(json.dumps({
            'status': 'error',
            'message': str(e)
        }), file=sys.stderr)
        sys.exit(1)


def cmd_download_episode(url, episode_index, output_dir):
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
            scraper = ScrapSerie(url, SITE_URL)
            series_name = scraper.get_name()
            
            ep_idx = int(episode_index)
            episode_data = scraper.selectEpisode(1, ep_idx)
            
            if not episode_data:
                raise Exception(f"Episode {ep_idx} not found")
            
            video_source = VideoSource(SITE_URL, episode_data, scraper.session_id, scraper.csrf_token)
            mp4_link = video_source.get_playlist()
            
            mp4_name = f"{series_name}_EP_{str(ep_idx + 1).zfill(2)}.mp4"
            mp4_path = os_module.path.join(output_dir, mp4_name)
            
            path, stopped = MP4_downloader(
                url=str(mp4_link).strip(),
                path=mp4_path
            )
            
            if stopped:
                raise Exception("Download was stopped")
                
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
            'message': 'Usage: animeworld_headless.py <command> [args]'
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
        if len(sys.argv) < 5:
            print(json.dumps({'status': 'error', 'message': 'Missing arguments: url episode_index output_dir'}), file=sys.stderr)
            sys.exit(1)
        cmd_download_episode(sys.argv[2], sys.argv[3], sys.argv[4])
    
    elif command == 'download-film':
        # Films are just episode 0 in AnimeWorld
        if len(sys.argv) < 4:
            print(json.dumps({'status': 'error', 'message': 'Missing arguments: url output_dir'}), file=sys.stderr)
            sys.exit(1)
        cmd_download_episode(sys.argv[2], '0', sys.argv[3])
        
    else:
        print(json.dumps({'status': 'error', 'message': f'Unknown command: {command}'}), file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
