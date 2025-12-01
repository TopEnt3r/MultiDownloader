#!/usr/bin/env python3
"""
AnimeUnity headless script for Java integration.
Provides JSON-based API for search, list episodes, and download.
"""

import sys
import os
import json
import io
import logging

# Disable ANSI colors
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
    config_loader.get_site_name_from_stack = lambda: 'animeunity'
    
    from StreamingCommunity.Api.Site.animeunity.site import title_search, media_search_manager
    from StreamingCommunity.Api.Site.animeunity.util.ScrapeSerie import ScrapeSerieAnime
    from StreamingCommunity.Api.Player.vixcloud import VideoSourceAnime
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
    SITE_URL = config_manager.get_site('animeunity', 'full_url').rstrip('/')
except:
    SITE_URL = 'https://animeunity.so'


def cmd_search(query):
    """Search for anime on AnimeUnity"""
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
                'id': str(getattr(item, 'id', '') or ''),
                'slug': getattr(item, 'slug', ''),
                'title': getattr(item, 'name', 'Unknown'),
                'type': getattr(item, 'type', 'TV'),
                'year': '',
                'source': 'AnimeUnity',
                'sourceAlias': 'AnimeUnity',
                'episodesCount': getattr(item, 'episodes_count', 0),
                'url': f"{SITE_URL}/anime/{getattr(item, 'id', '')}-{getattr(item, 'slug', '')}"
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


def cmd_list_episodes(anime_id, slug):
    """List all episodes for an anime"""
    try:
        saved_stdout = sys.stdout
        saved_stderr = sys.stderr
        sys.stdout = io.StringIO()
        sys.stderr = io.StringIO()
        
        try:
            scraper = ScrapeSerieAnime(SITE_URL)
            scraper.setup(None, int(anime_id), slug)
            episode_count = scraper.get_count_episodes() or 0
            
            episodes = []
            for i in range(episode_count):
                ep = scraper.get_info_episode(i)
                if ep:
                    episodes.append({
                        'id': str(getattr(ep, 'id', i)),
                        'season': '1',
                        'episode': str(getattr(ep, 'number', i + 1)),
                        'title': f"Episodio {getattr(ep, 'number', i + 1)}",
                        'index': i
                    })
        finally:
            sys.stdout = saved_stdout
            sys.stderr = saved_stderr
        
        print(json.dumps({
            'status': 'ok',
            'series_name': slug,
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


def cmd_download_episode(anime_id, slug, episode_index, output_dir):
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
            scraper = ScrapeSerieAnime(SITE_URL)
            video_source = VideoSourceAnime(SITE_URL)
            scraper.setup(None, int(anime_id), slug)
            
            ep_idx = int(episode_index)
            obj_episode = scraper.selectEpisode(1, ep_idx)
            
            if not obj_episode:
                raise Exception(f"Episode {ep_idx} not found")
            
            video_source.get_embed(obj_episode.id)
            
            mp4_name = f"{slug}_EP_{str(obj_episode.number).zfill(2)}.mp4"
            mp4_path = os_module.path.join(output_dir, mp4_name)
            
            path, stopped = MP4_downloader(
                url=str(video_source.src_mp4).strip(),
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
            'message': 'Usage: animeunity_headless.py <command> [args]'
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
            print(json.dumps({'status': 'error', 'message': 'Missing anime_id and slug'}), file=sys.stderr)
            sys.exit(1)
        cmd_list_episodes(sys.argv[2], sys.argv[3])
        
    elif command == 'download-episode':
        if len(sys.argv) < 6:
            print(json.dumps({'status': 'error', 'message': 'Missing arguments: anime_id slug episode_index output_dir'}), file=sys.stderr)
            sys.exit(1)
        cmd_download_episode(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
    
    elif command == 'download-film':
        # Films are just episode 0 in AnimeUnity
        if len(sys.argv) < 5:
            print(json.dumps({'status': 'error', 'message': 'Missing arguments: anime_id slug output_dir'}), file=sys.stderr)
            sys.exit(1)
        cmd_download_episode(sys.argv[2], sys.argv[3], '0', sys.argv[4])
        
    else:
        print(json.dumps({'status': 'error', 'message': f'Unknown command: {command}'}), file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
